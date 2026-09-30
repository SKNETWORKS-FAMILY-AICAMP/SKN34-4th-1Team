import asyncio
import json
import logging
from collections.abc import Callable, Iterable
from datetime import date
from hashlib import sha256
from time import perf_counter

from langchain_openai import ChatOpenAI
from openai import APITimeoutError, OpenAIError
from pydantic import BaseModel

from app.config import LangfuseSettings
from app.support_program_llm import (
    get_support_program_usage_details,
    invoke_support_program_model,
    validate_support_program_output,
)
from app.tracing import LLMTracing

from app.support_program_analysis.errors import SupportProgramAnalysisError, SupportProgramAnalysisTimeoutError
from app.support_program_analysis.models import (
    ANALYSIS_VERSION,
    SupportProgramAnalysisOutput,
    SupportProgramAnalysisRequest,
    SupportProgramAnalysisResponse,
    SupportProgramCondition,
    SupportProgramModelEvidence,
    SupportProgramScheduleItem,
)
from app.support_program_analysis.prompt import SUPPORT_PROGRAM_ANALYSIS_INSTRUCTIONS


logger = logging.getLogger(__name__)
MAX_OUTPUT_TOKENS = 12_000
PROMPT_SHA256 = sha256(SUPPORT_PROGRAM_ANALYSIS_INSTRUCTIONS.encode()).hexdigest()


def _has_valid_values(condition: SupportProgramCondition) -> bool:
    """분류에 허용된 값만 있고 범위가 뒤집히지 않았는지 확인한다."""
    values = condition.values
    regions = values.regions
    if regions is not None and (
        condition.category != "REGION" or not regions or len(set(regions)) != len(regions)
        or ("전국" in regions and len(regions) > 1)
    ):
        return False
    for allowed_category, minimum, maximum in (
        ("BUSINESS_AGE", values.min_years, values.max_years),
        ("FOUNDER_AGE", values.min_age, values.max_age),
    ):
        if (minimum is not None or maximum is not None) and condition.category != allowed_category:
            return False
        if minimum is not None and maximum is not None and minimum > maximum:
            return False
    return True


def _has_calendar_date(item: SupportProgramScheduleItem) -> bool:
    """형식만 맞는 2026-02-30 같은 값은 실제 날짜가 아니므로 제외한다."""
    if item.date is None:
        return True
    try:
        date.fromisoformat(item.date)
    except ValueError:
        return False
    return True


def _item_key(item: BaseModel) -> str:
    """근거를 제외한 값과 공백을 정규화한 문자열이 같으면 같은 항목으로 본다."""
    data = item.model_dump(mode="json", exclude={"evidence"})
    return json.dumps(
        {key: " ".join(value.split()) if isinstance(value, str) else value for key, value in data.items()},
        ensure_ascii=False, sort_keys=True,
    )


def _verify_output(request: SupportProgramAnalysisRequest, output: SupportProgramAnalysisOutput) -> tuple[dict, int]:
    """원문에서 확인할 수 없는 항목을 제외한 응답 필드(camelCase)와 제외 개수를 반환한다."""
    sources = {
        "SUMMARY": request.summary,
        "TARGET_DESCRIPTION": request.target_description,
        "APPLICATION_METHOD": request.application_method,
        "DETAIL_TEXT": request.detail_text,
    }
    discarded = 0

    def resolve(evidence: SupportProgramModelEvidence) -> dict | None:
        """인용이 지정 원문의 정확한 부분 문자열이면 첨부 번호를 이름으로 바꾼 근거를 반환한다."""
        if evidence.field == "ATTACHMENT":
            index = evidence.attachment_index
            if index is None or index >= len(request.attachments):
                return None
            source, attachment_name = request.attachments[index].text, request.attachments[index].name
        else:
            if evidence.attachment_index is not None:
                return None
            source, attachment_name = sources[evidence.field], None
        if source is None or not evidence.quote.strip() or evidence.quote not in source:
            return None
        return {"field": evidence.field, "quote": evidence.quote, "attachmentName": attachment_name}

    def verified(item: BaseModel | None, valid: bool = True) -> dict | None:
        nonlocal discarded
        if item is None:
            return None
        evidence = resolve(item.evidence) if valid else None
        if evidence is None:
            discarded += 1
            return None
        return item.model_dump(mode="json", by_alias=True) | {"evidence": evidence}

    def verified_list(items: Iterable[BaseModel], valid: Callable[[BaseModel], bool] = lambda _: True) -> list[dict]:
        nonlocal discarded
        kept: list[dict] = []
        seen: set[str] = set()
        for item in items:
            key = _item_key(item)
            if key in seen:
                discarded += 1
                continue
            data = verified(item, valid(item))
            if data is not None:
                seen.add(key)
                kept.append(data)
        return kept

    fields = {
        "summaryLine": output.summary_line if output.summary_line and output.summary_line.strip() else None,
        # 지원 유형은 근거 없는 분류 코드이므로 중복만 제거하고 제외 개수에 넣지 않는다.
        "supportTypes": list(dict.fromkeys(output.support_types)),
        "supportAmount": verified(output.support_amount),
        "selectionScale": verified(output.selection_scale),
        "conditions": verified_list(output.conditions, _has_valid_values),
        "contact": verified(output.contact),
        "requiredDocuments": verified_list(output.required_documents),
        "selectionSteps": verified_list(output.selection_steps),
        "evaluationCriteria": verified_list(output.evaluation_criteria),
        "schedule": verified_list(output.schedule, _has_calendar_date),
    }
    return fields, discarded


class SupportProgramAnalysisAgent:
    """한 번의 structured LLM 호출로 공고 원문·첨부의 지원 내용·신청 조건·절차를 근거와 함께 추출한다."""

    def __init__(
        self,
        *,
        model: ChatOpenAI,
        model_timeout_seconds: float,
        run_timeout_seconds: float,
        tracing: LLMTracing | None = None,
    ) -> None:
        self._tracing = tracing or LLMTracing(LangfuseSettings())
        self._model_name = model.model_name
        self._model_timeout_seconds = model_timeout_seconds
        self._run_timeout_seconds = run_timeout_seconds
        self._model = model.bind(
            max_tokens=MAX_OUTPUT_TOKENS, store=False,
            reasoning={"effort": "none"},
            timeout=model_timeout_seconds,
        )

    async def analyze(self, request: SupportProgramAnalysisRequest) -> SupportProgramAnalysisResponse:
        started = perf_counter()
        # 식별자는 추출에 필요하지 않으므로 원문 필드만 전달하고, 첨부는 근거가 가리킬 번호를 붙인다.
        payload = request.model_dump(mode="json", by_alias=True, exclude={"source_code", "source_program_id"})
        payload["attachments"] = [
            {"index": index, **attachment} for index, attachment in enumerate(payload["attachments"])
        ]
        try:
            async with asyncio.timeout(self._run_timeout_seconds):
                with self._tracing.observation(
                    "analysis.model", as_type="generation", model=self._model_name,
                    model_parameters={"max_tokens": MAX_OUTPUT_TOKENS, "reasoning_effort": "none", "max_retries": 0},
                    metadata={"prompt_sha256": PROMPT_SHA256, "usage_reported": False},
                ) as generation:
                    result = await invoke_support_program_model(
                        self._model, instructions=SUPPORT_PROGRAM_ANALYSIS_INSTRUCTIONS, payload=payload,
                        output_type=SupportProgramAnalysisOutput, timeout_seconds=self._model_timeout_seconds,
                    )
                    usage = getattr(result, "usage_metadata", None)
                    self._tracing.update(generation, usage_details={
                        key: value for key, value in (
                            ("input", (usage or {}).get("input_tokens")),
                            ("output", (usage or {}).get("output_tokens")),
                        ) if value is not None
                    }, metadata={"usage_reported": usage is not None})
                    output = validate_support_program_output(result, SupportProgramAnalysisOutput)
        except (APITimeoutError, TimeoutError) as error:
            self._log_failure("timeout", started)
            raise SupportProgramAnalysisTimeoutError() from error
        except (OpenAIError, ValueError) as error:
            self._log_failure("failed", started)
            raise SupportProgramAnalysisError() from error
        except asyncio.CancelledError:
            self._log_failure("cancelled", started)
            raise

        model_finished = perf_counter()
        fields, discarded = _verify_output(request, output)
        # 원문·인용·첨부 이름·공고 ID는 기록하지 않고 개수와 사용량만 남긴다.
        cached_input_tokens, reasoning_tokens = get_support_program_usage_details(usage)
        logger.info(
            "support_program_analysis_completed model_ms=%d elapsed_ms=%d attachment_count=%d condition_count=%d "
            "document_count=%d schedule_count=%d discarded_item_count=%d detail_text_present=%s usage_reported=%s "
            "input_tokens=%s output_tokens=%s cached_input_tokens=%s reasoning_tokens=%s",
            round((model_finished - started) * 1000), round((perf_counter() - started) * 1000),
            len(request.attachments), len(fields["conditions"]), len(fields["requiredDocuments"]),
            len(fields["schedule"]), discarded, request.detail_text is not None, usage is not None,
            usage.get("input_tokens") if usage is not None else None,
            usage.get("output_tokens") if usage is not None else None,
            cached_input_tokens, reasoning_tokens,
        )
        return SupportProgramAnalysisResponse.model_validate({
            "analysisVersion": ANALYSIS_VERSION,
            "model": self._model_name,
            "discardedItemCount": discarded,
            **fields,
        })

    def _log_failure(self, outcome: str, started: float) -> None:
        logger.info(
            "support_program_analysis_model_failed outcome=%s model_ms=%d",
            outcome, round((perf_counter() - started) * 1000),
        )
