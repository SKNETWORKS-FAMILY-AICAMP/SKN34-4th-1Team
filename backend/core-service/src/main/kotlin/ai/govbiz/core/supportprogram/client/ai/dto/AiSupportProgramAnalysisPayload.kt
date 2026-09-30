package ai.govbiz.core.supportprogram.client.ai.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * AI Service 공고 분석 원본 응답입니다. 필수 필드 누락은 거부하고 값 검증은 Mapper가 수행합니다.
 * v2 목록 필드는 버전이 다른 응답을 버전 불일치로 구분할 수 있도록 역직렬화 단계에서는 선택으로 읽고,
 * 버전을 확인한 뒤 Mapper가 필수로 검사합니다.
 */
data class AiSupportProgramAnalysisPayload(
    @param:JsonProperty(required = true) val analysisVersion: String?,
    @param:JsonProperty(required = true) val model: String?,
    @param:JsonProperty(required = true) val summaryLine: String?,
    @param:JsonProperty(required = true) val supportTypes: List<String?>?,
    @param:JsonProperty(required = true) val supportAmount: AiSupportProgramAnalysisAmountPayload?,
    @param:JsonProperty(required = true) val selectionScale: AiSupportProgramAnalysisTextPayload?,
    @param:JsonProperty(required = true) val conditions: List<AiSupportProgramAnalysisConditionPayload?>?,
    @param:JsonProperty(required = true) val contact: AiSupportProgramAnalysisTextPayload?,
    @param:JsonProperty(required = true) val discardedItemCount: Int?,
    val requiredDocuments: List<AiSupportProgramAnalysisRequiredDocumentPayload?>? = null,
    val selectionSteps: List<AiSupportProgramAnalysisSelectionStepPayload?>? = null,
    val evaluationCriteria: List<AiSupportProgramAnalysisEvaluationCriterionPayload?>? = null,
    val schedule: List<AiSupportProgramAnalysisScheduleItemPayload?>? = null,
)

data class AiSupportProgramAnalysisEvidencePayload(
    @param:JsonProperty(required = true) val field: String?,
    @param:JsonProperty(required = true) val quote: String?,
    val attachmentName: String? = null,
)

data class AiSupportProgramAnalysisTextPayload(
    @param:JsonProperty(required = true) val text: String?,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)

data class AiSupportProgramAnalysisAmountPayload(
    @param:JsonProperty(required = true) val text: String?,
    @param:JsonProperty(required = true) val maxAmountKrw: Long?,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)

data class AiSupportProgramAnalysisConditionPayload(
    @param:JsonProperty(required = true) val kind: String?,
    @param:JsonProperty(required = true) val category: String?,
    @param:JsonProperty(required = true) val text: String?,
    @param:JsonProperty(required = true) val values: AiSupportProgramAnalysisConditionValuesPayload?,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)

data class AiSupportProgramAnalysisConditionValuesPayload(
    val regions: List<String?>? = null,
    val minYears: Double? = null,
    val maxYears: Double? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null,
)

data class AiSupportProgramAnalysisRequiredDocumentPayload(
    @param:JsonProperty(required = true) val name: String?,
    @param:JsonProperty(required = true) val requirement: String?,
    val note: String? = null,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)

data class AiSupportProgramAnalysisSelectionStepPayload(
    @param:JsonProperty(required = true) val name: String?,
    val note: String? = null,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)

data class AiSupportProgramAnalysisEvaluationCriterionPayload(
    @param:JsonProperty(required = true) val item: String?,
    val points: Double? = null,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)

/** date는 `YYYY-MM-DD` 문자열이며 Mapper가 실제 날짜인지 검사합니다. */
data class AiSupportProgramAnalysisScheduleItemPayload(
    @param:JsonProperty(required = true) val label: String?,
    val date: String? = null,
    @param:JsonProperty(required = true) val text: String?,
    @param:JsonProperty(required = true) val evidence: AiSupportProgramAnalysisEvidencePayload?,
)
