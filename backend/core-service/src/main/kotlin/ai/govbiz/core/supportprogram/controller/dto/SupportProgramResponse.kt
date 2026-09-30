package ai.govbiz.core.supportprogram.controller.dto

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSummary
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramEligibilityReview
import ai.govbiz.core.supportprogram.domain.SupportProgramEligibilityReviewStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramEligibilityStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramEligibilityAssessment
import ai.govbiz.core.supportprogram.domain.SupportProgramEligibilityEvidenceField

/**
 * 검색·복원·목록·관심 공고함이 함께 쓰는 공고 한 건입니다. analysisSummary는 검색·복원·목록에서 현재 공고 내용 기준
 * 완료 분석이 있을 때만 채우며, 관심 공고함은 싣지 않아 항상 null입니다.
 */
data class SupportProgramResponse(
    val id: String,
    val sourceCode: String,
    val title: String,
    val organization: String,
    val summary: String,
    val categories: List<String>,
    val regions: List<String>,
    val targetDescription: String,
    val applicationPeriod: String,
    val applicationStartDate: String?,
    val applicationEndDate: String?,
    val status: SupportProgramStatus,
    val sourceName: String,
    val sourceUrl: String,
    val matchedReasons: List<String>,
    val recommendationScore: Int?,
    val eligibilityReview: SupportProgramEligibilityReviewResponse? = null,
    val analysisSummary: SupportProgramAnalysisSummaryResponse? = null,
) {
    companion object {
        fun from(program: SupportProgram, analysisSummary: SupportProgramAnalysisSummary? = null): SupportProgramResponse =
            SupportProgramResponse(
                id = program.id,
                sourceCode = program.sourceCode,
                title = program.title,
                organization = program.organization,
                summary = program.summary,
                categories = program.categories,
                regions = program.regions,
                targetDescription = program.targetDescription,
                applicationPeriod = program.applicationPeriod,
                applicationStartDate = program.applicationStartDate?.toString(),
                applicationEndDate = program.applicationEndDate?.toString(),
                status = program.status,
                sourceName = program.sourceName,
                sourceUrl = program.sourceUrl,
                matchedReasons = program.matchedReasons,
                recommendationScore = program.recommendationScore,
                eligibilityReview = program.eligibilityReview?.let(SupportProgramEligibilityReviewResponse::from),
                analysisSummary = analysisSummary?.let(SupportProgramAnalysisSummaryResponse::from),
            )
    }
}

/** 카드 한 줄 정보용 AI 분석 요약입니다. 근거·조건은 상세 `analysis`에만 있습니다. */
data class SupportProgramAnalysisSummaryResponse(
    val summaryLine: String?,
    val supportAmountText: String?,
    val maxAmountKrw: Long?,
    val supportTypes: List<SupportProgramAnalysisSupportType>,
) {
    companion object {
        fun from(summary: SupportProgramAnalysisSummary) = SupportProgramAnalysisSummaryResponse(
            summary.summaryLine, summary.supportAmountText, summary.maxAmountKrw, java.util.List.copyOf(summary.supportTypes),
        )
    }
}

data class SupportProgramEligibilityReviewResponse(
    val status: SupportProgramEligibilityReviewStatus,
    val basis: String,
    val target: SupportProgramEligibilityAssessmentResponse,
    val region: SupportProgramEligibilityAssessmentResponse,
) {
    companion object {
        fun from(review: SupportProgramEligibilityReview): SupportProgramEligibilityReviewResponse =
            SupportProgramEligibilityReviewResponse(
                review.status,
                "OFFICIAL_API_TEXT",
                SupportProgramEligibilityAssessmentResponse.from(review.target),
                SupportProgramEligibilityAssessmentResponse.from(review.region),
            )
    }
}

data class SupportProgramEligibilityAssessmentResponse(
    val status: SupportProgramEligibilityStatus,
    val explanation: String,
    val evidence: List<SupportProgramEligibilityEvidenceResponse>,
) {
    companion object {
        fun from(assessment: SupportProgramEligibilityAssessment): SupportProgramEligibilityAssessmentResponse =
            SupportProgramEligibilityAssessmentResponse(
                assessment.status,
                assessment.explanation,
                java.util.List.copyOf(assessment.evidence.map { SupportProgramEligibilityEvidenceResponse(it.field, it.quote) }),
            )
    }
}

data class SupportProgramEligibilityEvidenceResponse(
    val field: SupportProgramEligibilityEvidenceField,
    val quote: String,
)
