from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, model_validator


ANALYSIS_VERSION = "govbiz-support-program-analysis-v2"
MAX_CONDITIONS = 20
MAX_ATTACHMENTS = 8
MAX_ATTACHMENT_TEXT_LENGTH = 40_000

SupportType = Literal[
    "GRANT", "LOAN", "GUARANTEE", "VOUCHER", "CONSULTING", "EDUCATION",
    "SPACE", "MARKETING", "RND", "EXPORT", "HR", "OTHER",
]
EvidenceField = Literal["SUMMARY", "TARGET_DESCRIPTION", "APPLICATION_METHOD", "DETAIL_TEXT", "ATTACHMENT"]
ConditionKind = Literal["REQUIRED", "EXCLUDED", "PREFERRED"]
ConditionCategory = Literal[
    "REGION", "BUSINESS_AGE", "FOUNDER_AGE", "INDUSTRY", "COMPANY_SIZE", "LEGAL_FORM", "CERTIFICATION", "OTHER",
]
DocumentRequirement = Literal["REQUIRED", "OPTIONAL", "CONDITIONAL"]
# 17개 시·도 약칭과 전국만 허용한다. 시·군·구 등 하위 지역은 조건 text에만 남긴다.
Region = Literal[
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기",
    "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주", "전국",
]


class SupportProgramAnalysisAttachment(BaseModel):
    """Core가 공고 첨부(HWP·PDF 등)에서 추출해 잘라 보낸 본문."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    name: str = Field(min_length=1, max_length=255)
    text: str = Field(min_length=1, max_length=MAX_ATTACHMENT_TEXT_LENGTH)


class SupportProgramAnalysisRequest(BaseModel):
    """Core가 한 공고의 저장된 원문과 첨부 본문을 보내는 내부 분석 계약."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    source_code: str = Field(alias="sourceCode", min_length=1, max_length=64)
    source_program_id: str = Field(alias="sourceProgramId", min_length=1, max_length=255)
    title: str = Field(min_length=1, max_length=500)
    organization: str = Field(max_length=255)
    summary: str = Field(max_length=20_000)
    target_description: str = Field(alias="targetDescription", max_length=8_000)
    application_period: str = Field(alias="applicationPeriod", max_length=1_000)
    application_method: str | None = Field(default=None, alias="applicationMethod", max_length=8_000)
    detail_text: str | None = Field(default=None, alias="detailText", max_length=30_000)
    attachments: list[SupportProgramAnalysisAttachment] = Field(default_factory=list, max_length=MAX_ATTACHMENTS)

    @model_validator(mode="after")
    def limit_total_attachment_text(self) -> "SupportProgramAnalysisRequest":
        if sum(len(attachment.text) for attachment in self.attachments) > MAX_ATTACHMENT_TEXT_LENGTH:
            raise ValueError("attachment text must not exceed 40000 characters in total")
        return self


class SupportProgramModelEvidence(BaseModel):
    """모델이 반환하는 근거. 첨부 근거는 이름 대신 요청 attachments의 번호로 가리킨다."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    field: EvidenceField
    quote: str = Field(min_length=1, max_length=300)
    attachment_index: int | None = Field(alias="attachmentIndex", ge=0, le=MAX_ATTACHMENTS - 1)


class SupportProgramAnalysisEvidence(BaseModel):
    """서버가 정확한 부분 문자열임을 확인하고 첨부 번호를 첨부 이름으로 바꾼 근거."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    field: EvidenceField
    quote: str = Field(min_length=1, max_length=300)
    attachment_name: str | None = Field(alias="attachmentName", min_length=1, max_length=255)


# 모델 출력과 Core 응답은 근거 형식만 다르므로 같은 항목 정의를 근거 타입으로 매개변수화한다.
Evidence = TypeVar("Evidence", SupportProgramModelEvidence, SupportProgramAnalysisEvidence)


class SupportProgramSupportAmount(BaseModel, Generic[Evidence]):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    text: str = Field(min_length=1, max_length=120)
    max_amount_krw: int | None = Field(alias="maxAmountKrw", ge=1, le=1_000_000_000_000)
    evidence: Evidence


class SupportProgramSelectionScale(BaseModel, Generic[Evidence]):
    model_config = ConfigDict(extra="forbid", frozen=True)

    text: str = Field(min_length=1, max_length=120)
    evidence: Evidence


class SupportProgramConditionValues(BaseModel):
    """조건 분류별 구조화 값. 분류에 맞지 않는 값은 Agent가 항목째 제외한다."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    regions: list[Region] | None = Field(max_length=18)
    min_years: float | None = Field(alias="minYears", ge=0, le=100)
    max_years: float | None = Field(alias="maxYears", ge=0, le=100)
    min_age: int | None = Field(alias="minAge", ge=0, le=120)
    max_age: int | None = Field(alias="maxAge", ge=0, le=120)


class SupportProgramCondition(BaseModel, Generic[Evidence]):
    model_config = ConfigDict(extra="forbid", frozen=True)

    kind: ConditionKind
    category: ConditionCategory
    text: str = Field(min_length=1, max_length=160)
    values: SupportProgramConditionValues
    evidence: Evidence


class SupportProgramContact(BaseModel, Generic[Evidence]):
    model_config = ConfigDict(extra="forbid", frozen=True)

    text: str = Field(min_length=1, max_length=200)
    evidence: Evidence


class SupportProgramRequiredDocument(BaseModel, Generic[Evidence]):
    """제출 서류. CONDITIONAL은 해당자만 제출하는 서류다."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    name: str = Field(min_length=1, max_length=120)
    requirement: DocumentRequirement
    note: str | None = Field(min_length=1, max_length=200)
    evidence: Evidence


class SupportProgramSelectionStep(BaseModel, Generic[Evidence]):
    model_config = ConfigDict(extra="forbid", frozen=True)

    name: str = Field(min_length=1, max_length=80)
    note: str | None = Field(min_length=1, max_length=160)
    evidence: Evidence


class SupportProgramEvaluationCriterion(BaseModel, Generic[Evidence]):
    model_config = ConfigDict(extra="forbid", frozen=True)

    item: str = Field(min_length=1, max_length=120)
    points: float | None = Field(ge=0, le=1_000)
    evidence: Evidence


class SupportProgramScheduleItem(BaseModel, Generic[Evidence]):
    """일정. date는 원문에 완전한 날짜가 명시된 경우만 채우며 실제 날짜 여부는 Agent가 검사한다."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    label: str = Field(min_length=1, max_length=80)
    date: str | None = Field(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    text: str = Field(min_length=1, max_length=120)
    evidence: Evidence


class SupportProgramAnalysisFields(BaseModel, Generic[Evidence]):
    """추출 항목 전체. null·빈 목록은 공고 원문에 명시 없음을 뜻한다."""

    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    summary_line: str | None = Field(alias="summaryLine", min_length=1, max_length=80)
    support_types: list[SupportType] = Field(alias="supportTypes", max_length=12)
    support_amount: SupportProgramSupportAmount[Evidence] | None = Field(alias="supportAmount")
    selection_scale: SupportProgramSelectionScale[Evidence] | None = Field(alias="selectionScale")
    conditions: list[SupportProgramCondition[Evidence]] = Field(max_length=MAX_CONDITIONS)
    contact: SupportProgramContact[Evidence] | None
    required_documents: list[SupportProgramRequiredDocument[Evidence]] = Field(alias="requiredDocuments", max_length=30)
    selection_steps: list[SupportProgramSelectionStep[Evidence]] = Field(alias="selectionSteps", max_length=10)
    evaluation_criteria: list[SupportProgramEvaluationCriterion[Evidence]] = Field(
        alias="evaluationCriteria", max_length=20,
    )
    schedule: list[SupportProgramScheduleItem[Evidence]] = Field(max_length=15)


class SupportProgramAnalysisOutput(SupportProgramAnalysisFields[SupportProgramModelEvidence]):
    """모델의 strict structured output."""


class SupportProgramAnalysisResponse(SupportProgramAnalysisFields[SupportProgramAnalysisEvidence]):
    """근거 검증을 통과한 항목만 담아 Core에 반환하는 분석 결과."""

    analysis_version: Literal[ANALYSIS_VERSION] = Field(alias="analysisVersion")
    model: str = Field(min_length=1)
    discarded_item_count: int = Field(alias="discardedItemCount", ge=0)
