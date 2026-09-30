from typing import Annotated
import logging
from time import perf_counter

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.support_program_analysis.agent import SupportProgramAnalysisAgent
from app.support_program_analysis.errors import SupportProgramAnalysisError, SupportProgramAnalysisTimeoutError
from app.support_program_analysis.models import SupportProgramAnalysisRequest, SupportProgramAnalysisResponse


router = APIRouter(prefix="/internal/v1/support-program-analyses", tags=["internal"])
logger = logging.getLogger(__name__)


def get_support_program_analysis_agent(request: Request) -> SupportProgramAnalysisAgent:
    return request.app.state.container.support_program_analysis_agent


@router.post("/analyze", response_model=SupportProgramAnalysisResponse, summary="지원사업 공고 구조화 추출")
async def analyze_support_program(
    payload: SupportProgramAnalysisRequest,
    agent: Annotated[SupportProgramAnalysisAgent, Depends(get_support_program_analysis_agent)],
) -> SupportProgramAnalysisResponse:
    started = perf_counter()
    try:
        return await agent.analyze(payload)
    except SupportProgramAnalysisError as error:
        timed_out = isinstance(error, SupportProgramAnalysisTimeoutError)
        # 허용된 진단값만 기록한다. 공고 ID·원문·모델 출력·오류 메시지·traceback은 남기지 않는다.
        logger.warning(
            "support_program_analysis_failed failure_kind=%s error_type=%s detail_text_present=%s attachment_count=%d "
            "elapsed_ms=%d",
            "timeout" if timed_out else "execution",
            type(error.__cause__ or error).__name__,
            payload.detail_text is not None,
            len(payload.attachments),
            round((perf_counter() - started) * 1_000),
        )
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT if timed_out else status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Support program analysis timed out." if timed_out
            else "Support program analysis is temporarily unavailable.",
        ) from error
