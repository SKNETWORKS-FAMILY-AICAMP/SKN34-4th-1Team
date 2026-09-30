package ai.govbiz.core.supportprogram.service.conditioncheck

import ai.govbiz.core.account.repository.CompanyRepository
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramApplicantProfile
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionCheck
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.detail.exception.SupportProgramNotFoundException
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConditionCheckResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConditionCheckStatus
import java.time.Clock
import java.time.LocalDate
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * 로그인한 회원의 기업 프로필(소재지·설립연도)로 공고의 현재 완료 분석 조건을 하나씩 확인합니다.
 * 저장된 분석만 읽으며 AI를 호출하거나 결과를 저장하지 않습니다. 기준일은 서울 날짜입니다.
 */
@Service
class SupportProgramConditionCheckService(
    private val supportProgramRepository: SupportProgramRepository,
    private val analysisRepository: SupportProgramAnalysisRepository,
    private val companyRepository: CompanyRepository,
    @param:Qualifier("seoulClock") private val clock: Clock,
) {
    fun check(accountId: Long, sourceCode: String, sourceProgramId: String): SupportProgramConditionCheckResult {
        val program = supportProgramRepository.findPresentBySourceAndProgramId(sourceCode, sourceProgramId)?.program
            ?: throw SupportProgramNotFoundException()
        val analysis = analysisRepository.findCurrent(program.sourceCode, program.id)
        val profile = companyRepository.findByAccountId(accountId)?.profile?.let {
            SupportProgramApplicantProfile(SupportProgramConditionCheck.regionName(it.region), it.foundedYear)
        }
        val referenceDate = LocalDate.now(clock)
        val content = analysis.content
        if (analysis.status != SupportProgramAnalysisStatus.COMPLETED || content == null) {
            return SupportProgramConditionCheckResult(SupportProgramConditionCheckStatus.NOT_ANALYZED, null, referenceDate, profile, null)
        }
        if (profile == null) {
            return SupportProgramConditionCheckResult(
                SupportProgramConditionCheckStatus.NO_COMPANY, analysis.analyzedAt, referenceDate, null, null,
            )
        }
        return SupportProgramConditionCheckResult(
            status = SupportProgramConditionCheckStatus.CHECKED,
            analyzedAt = analysis.analyzedAt,
            referenceDate = referenceDate,
            profile = profile,
            evaluation = SupportProgramConditionCheck.check(content.conditions, profile, referenceDate),
        )
    }
}
