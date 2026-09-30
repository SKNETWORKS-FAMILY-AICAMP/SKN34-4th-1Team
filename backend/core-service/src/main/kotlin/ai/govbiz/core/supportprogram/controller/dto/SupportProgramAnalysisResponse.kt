package ai.govbiz.core.supportprogram.controller.dto

import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisAmount
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisCondition
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisDocumentRequirement
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvaluationCriterion
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidence
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisRequiredDocument
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisScheduleItem
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSelectionStep
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisText
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 공고 상세의 AI 분석입니다. 항상 포함되며 COMPLETED가 아니면 내용 필드는 null 또는 빈 목록입니다.
 * analyzedAt은 서울 기준 현지 시각(초 단위, 오프셋 없음)입니다. v2 목록과 sourceAttachmentNames는 v1 분석이면 빈 목록입니다.
 */
data class SupportProgramAnalysisResponse(
    val status: SupportProgramAnalysisStatus,
    val analyzedAt: String?,
    val summaryLine: String?,
    val supportTypes: List<SupportProgramAnalysisSupportType>,
    val supportAmount: SupportProgramAnalysisAmountResponse?,
    val selectionScale: SupportProgramAnalysisTextResponse?,
    val conditions: List<SupportProgramAnalysisConditionResponse>,
    val contact: SupportProgramAnalysisTextResponse?,
    val requiredDocuments: List<SupportProgramAnalysisRequiredDocumentResponse>,
    val selectionSteps: List<SupportProgramAnalysisSelectionStepResponse>,
    val evaluationCriteria: List<SupportProgramAnalysisEvaluationCriterionResponse>,
    val schedule: List<SupportProgramAnalysisScheduleItemResponse>,
    val sourceAttachmentNames: List<String>,
) {
    companion object {
        fun from(analysis: SupportProgramAnalysis): SupportProgramAnalysisResponse {
            val content = analysis.content
            return SupportProgramAnalysisResponse(
                status = analysis.status,
                analyzedAt = analysis.analyzedAt?.let(::formatAnalyzedAt),
                summaryLine = content?.summaryLine,
                supportTypes = content?.supportTypes.orEmpty(),
                supportAmount = content?.supportAmount?.let(SupportProgramAnalysisAmountResponse::from),
                selectionScale = content?.selectionScale?.let(SupportProgramAnalysisTextResponse::from),
                conditions = content?.conditions.orEmpty().map(SupportProgramAnalysisConditionResponse::from),
                contact = content?.contact?.let(SupportProgramAnalysisTextResponse::from),
                requiredDocuments = content?.requiredDocuments.orEmpty().map(SupportProgramAnalysisRequiredDocumentResponse::from),
                selectionSteps = content?.selectionSteps.orEmpty().map(SupportProgramAnalysisSelectionStepResponse::from),
                evaluationCriteria = content?.evaluationCriteria.orEmpty().map(SupportProgramAnalysisEvaluationCriterionResponse::from),
                schedule = content?.schedule.orEmpty().map(SupportProgramAnalysisScheduleItemResponse::from),
                sourceAttachmentNames = content?.sourceAttachmentNames.orEmpty(),
            )
        }

        /** 분석 시각은 서울 현지 시각을 초 단위까지, 오프셋 없이 씁니다. 조건 확인 응답도 같은 형식을 씁니다. */
        fun formatAnalyzedAt(analyzedAt: LocalDateTime): String =
            analyzedAt.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}

/** attachmentName은 ATTACHMENT 근거의 첨부 이름이며 그 밖에는 항상 null로 포함됩니다. */
data class SupportProgramAnalysisEvidenceResponse(
    val field: SupportProgramAnalysisEvidenceField,
    val quote: String,
    val attachmentName: String?,
) {
    companion object {
        fun from(evidence: SupportProgramAnalysisEvidence) =
            SupportProgramAnalysisEvidenceResponse(evidence.field, evidence.quote, evidence.attachmentName)
    }
}

data class SupportProgramAnalysisRequiredDocumentResponse(
    val name: String,
    val requirement: SupportProgramAnalysisDocumentRequirement,
    val note: String?,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(value: SupportProgramAnalysisRequiredDocument) = SupportProgramAnalysisRequiredDocumentResponse(
            value.name, value.requirement, value.note, SupportProgramAnalysisEvidenceResponse.from(value.evidence),
        )
    }
}

/** 선정 절차 한 단계입니다. 목록 순서가 절차 순서입니다. */
data class SupportProgramAnalysisSelectionStepResponse(
    val name: String,
    val note: String?,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(value: SupportProgramAnalysisSelectionStep) = SupportProgramAnalysisSelectionStepResponse(
            value.name, value.note, SupportProgramAnalysisEvidenceResponse.from(value.evidence),
        )
    }
}

data class SupportProgramAnalysisEvaluationCriterionResponse(
    val item: String,
    val points: Double?,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(value: SupportProgramAnalysisEvaluationCriterion) = SupportProgramAnalysisEvaluationCriterionResponse(
            value.item, value.points, SupportProgramAnalysisEvidenceResponse.from(value.evidence),
        )
    }
}

/** date는 `YYYY-MM-DD` 문자열이며 원문에 완전한 날짜가 없으면 null입니다. */
data class SupportProgramAnalysisScheduleItemResponse(
    val label: String,
    val date: String?,
    val text: String,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(value: SupportProgramAnalysisScheduleItem) = SupportProgramAnalysisScheduleItemResponse(
            value.label, value.date?.toString(), value.text, SupportProgramAnalysisEvidenceResponse.from(value.evidence),
        )
    }
}

data class SupportProgramAnalysisTextResponse(
    val text: String,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(value: SupportProgramAnalysisText) =
            SupportProgramAnalysisTextResponse(value.text, SupportProgramAnalysisEvidenceResponse.from(value.evidence))
    }
}

data class SupportProgramAnalysisAmountResponse(
    val text: String,
    val maxAmountKrw: Long?,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(value: SupportProgramAnalysisAmount) = SupportProgramAnalysisAmountResponse(
            value.text, value.maxAmountKrw, SupportProgramAnalysisEvidenceResponse.from(value.evidence),
        )
    }
}

data class SupportProgramAnalysisConditionValuesResponse(
    val regions: List<String>?,
    val minYears: Double?,
    val maxYears: Double?,
    val minAge: Int?,
    val maxAge: Int?,
)

data class SupportProgramAnalysisConditionResponse(
    val kind: SupportProgramAnalysisConditionKind,
    val category: SupportProgramAnalysisConditionCategory,
    val text: String,
    val values: SupportProgramAnalysisConditionValuesResponse,
    val evidence: SupportProgramAnalysisEvidenceResponse,
) {
    companion object {
        fun from(condition: SupportProgramAnalysisCondition) = SupportProgramAnalysisConditionResponse(
            kind = condition.kind,
            category = condition.category,
            text = condition.text,
            values = condition.values.let {
                SupportProgramAnalysisConditionValuesResponse(it.regions, it.minYears, it.maxYears, it.minAge, it.maxAge)
            },
            evidence = SupportProgramAnalysisEvidenceResponse.from(condition.evidence),
        )
    }
}
