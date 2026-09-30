package ai.govbiz.core.supportprogram.service.dto

import ai.govbiz.core.supportprogram.domain.SupportProgramApplicantProfile
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionEvaluation
import java.time.LocalDate
import java.time.LocalDateTime

/** 조건 확인 상태입니다. 현재 완료 분석이 없으면 기업 등록 여부와 관계없이 NOT_ANALYZED입니다. */
enum class SupportProgramConditionCheckStatus {
    CHECKED,
    NO_COMPANY,
    NOT_ANALYZED,
}

/**
 * 로그인한 회원의 기업 프로필로 공고의 현재 완료 분석 조건을 확인한 결과입니다.
 * analyzedAt은 현재 완료 분석이 있을 때만, profile은 기업이 있을 때만, evaluation은 CHECKED일 때만 있습니다.
 */
data class SupportProgramConditionCheckResult(
    val status: SupportProgramConditionCheckStatus,
    val analyzedAt: LocalDateTime?,
    val referenceDate: LocalDate,
    val profile: SupportProgramApplicantProfile?,
    val evaluation: SupportProgramConditionEvaluation?,
) {
    init {
        require((status == SupportProgramConditionCheckStatus.CHECKED) == (evaluation != null)) {
            "only a checked result has an evaluation"
        }
        require(status != SupportProgramConditionCheckStatus.NO_COMPANY || profile == null) {
            "a result without a company must not have a profile"
        }
        require(status != SupportProgramConditionCheckStatus.CHECKED || profile != null) {
            "a checked result must have the profile it used"
        }
        require((status == SupportProgramConditionCheckStatus.NOT_ANALYZED) == (analyzedAt == null)) {
            "only a result with a current completed analysis has analyzedAt"
        }
    }
}
