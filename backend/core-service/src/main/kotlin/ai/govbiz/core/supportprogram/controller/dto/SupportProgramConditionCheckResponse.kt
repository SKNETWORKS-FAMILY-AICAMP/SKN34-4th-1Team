package ai.govbiz.core.supportprogram.controller.dto

import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConditionCheckResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConditionCheckStatus

/**
 * 내 기업 프로필로 확인한 공고 분석 조건입니다. conditions[].index는 같은 공고 상세 `analysis.conditions`의 위치이며,
 * CHECKED가 아니면 conditions는 빈 목록, overall은 null입니다. analyzedAt은 상세 분석과 같은 형식입니다.
 */
data class SupportProgramConditionCheckResponse(
    val status: SupportProgramConditionCheckStatus,
    val analyzedAt: String?,
    val referenceDate: String,
    val profile: SupportProgramConditionCheckProfileResponse?,
    val overall: SupportProgramConditionResult?,
    val conditions: List<SupportProgramConditionCheckItemResponse>,
) {
    companion object {
        fun from(result: SupportProgramConditionCheckResult) = SupportProgramConditionCheckResponse(
            status = result.status,
            analyzedAt = result.analyzedAt?.let(SupportProgramAnalysisResponse::formatAnalyzedAt),
            referenceDate = result.referenceDate.toString(),
            profile = result.profile?.let { SupportProgramConditionCheckProfileResponse(it.region, it.foundedYear) },
            overall = result.evaluation?.overall,
            conditions = result.evaluation?.conditions.orEmpty().mapIndexed { index, verdict ->
                SupportProgramConditionCheckItemResponse(index, verdict.result, verdict.reason)
            },
        )
    }
}

/** 확인에 쓴 프로필입니다. region은 시·도 약칭이며 알 수 없는 소재지는 null입니다. */
data class SupportProgramConditionCheckProfileResponse(
    val region: String?,
    val foundedYear: Int?,
)

data class SupportProgramConditionCheckItemResponse(
    val index: Int,
    val result: SupportProgramConditionResult,
    val reason: SupportProgramConditionReason,
)
