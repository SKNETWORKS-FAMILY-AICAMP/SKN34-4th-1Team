package ai.govbiz.core.supportprogram.service.conditioncheck

import ai.govbiz.core.account.domain.Company
import ai.govbiz.core.account.domain.CompanyProfileInput
import ai.govbiz.core.account.repository.CompanyRepository
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisCondition
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionValues
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidence
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramApplicantProfile
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionEvaluation
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionResult
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionVerdict
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.detail.exception.SupportProgramNotFoundException
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConditionCheckResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConditionCheckStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class SupportProgramConditionCheckServiceTest {
    private val programs = Mockito.mock(SupportProgramRepository::class.java)
    private val analyses = Mockito.mock(SupportProgramAnalysisRepository::class.java)
    private val companies = Mockito.mock(CompanyRepository::class.java)
    // 2026-09-30T15:30Z는 서울 기준 2026-10-01 00:30입니다. 기준일은 UTC가 아니라 서울 날짜여야 합니다.
    private val clock = Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ZoneId.of("Asia/Seoul"))
    private val service = SupportProgramConditionCheckService(programs, analyses, companies, clock)
    private val today = LocalDate.of(2026, 10, 1)
    private val analyzedAt = LocalDateTime.of(2026, 9, 30, 10, 0)

    @Test
    fun checksTheCurrentCompletedAnalysisWithTheNormalizedCompanyProfileOnTheSeoulDate() {
        stubProgram()
        stubAnalysis(completed(
            condition(SupportProgramAnalysisConditionKind.REQUIRED, SupportProgramAnalysisConditionCategory.REGION, regions = listOf("서울")),
            condition(SupportProgramAnalysisConditionKind.REQUIRED, SupportProgramAnalysisConditionCategory.BUSINESS_AGE, maxYears = 7.0),
            condition(SupportProgramAnalysisConditionKind.PREFERRED, SupportProgramAnalysisConditionCategory.INDUSTRY),
        ))
        stubCompany("서울특별시", 2021)

        val result = service.check(ACCOUNT_ID, "BIZINFO", "PBLN_TEST")

        assertEquals(
            SupportProgramConditionCheckResult(
                status = SupportProgramConditionCheckStatus.CHECKED,
                analyzedAt = analyzedAt,
                referenceDate = today,
                profile = SupportProgramApplicantProfile("서울", 2021),
                evaluation = SupportProgramConditionEvaluation(
                    SupportProgramConditionResult.MET,
                    listOf(
                        SupportProgramConditionVerdict(SupportProgramConditionResult.MET, SupportProgramConditionReason.REGION_MATCH),
                        SupportProgramConditionVerdict(SupportProgramConditionResult.MET, SupportProgramConditionReason.BUSINESS_AGE_WITHIN),
                        SupportProgramConditionVerdict(SupportProgramConditionResult.UNKNOWN, SupportProgramConditionReason.NOT_COMPARABLE),
                    ),
                ),
            ),
            result,
        )
        Mockito.verify(analyses).findCurrent("BIZINFO", "PBLN_TEST")
        Mockito.verify(companies).findByAccountId(ACCOUNT_ID)
    }

    @Test
    fun accountWithoutACompanyIsNoCompanyWithTheAnalysisTimeButNoProfileOrVerdicts() {
        stubProgram()
        stubAnalysis(completed(
            condition(SupportProgramAnalysisConditionKind.REQUIRED, SupportProgramAnalysisConditionCategory.REGION, regions = listOf("서울")),
        ))

        assertEquals(
            SupportProgramConditionCheckResult(SupportProgramConditionCheckStatus.NO_COMPANY, analyzedAt, today, null, null),
            service.check(ACCOUNT_ID, "BIZINFO", "PBLN_TEST"),
        )
    }

    @Test
    fun failedOrMissingAnalysisIsNotAnalyzedEvenWithoutACompany() {
        stubProgram()
        for (analysis in listOf(SupportProgramAnalysis.NOT_ANALYZED, SupportProgramAnalysis.FAILED)) {
            stubAnalysis(analysis)
            assertEquals(
                SupportProgramConditionCheckResult(SupportProgramConditionCheckStatus.NOT_ANALYZED, null, today, null, null),
                service.check(ACCOUNT_ID, "BIZINFO", "PBLN_TEST"),
            )
        }

        stubCompany("부산광역시", 2019)
        stubAnalysis(SupportProgramAnalysis.FAILED)
        assertEquals(
            SupportProgramConditionCheckResult(
                SupportProgramConditionCheckStatus.NOT_ANALYZED, null, today, SupportProgramApplicantProfile("부산", 2019), null,
            ),
            service.check(ACCOUNT_ID, "BIZINFO", "PBLN_TEST"),
        )
    }

    @Test
    fun completedAnalysisWithoutConditionsIsCheckedWithAnUnknownOverall() {
        stubProgram()
        stubAnalysis(completed())
        stubCompany("서울특별시", 2021)

        val result = service.check(ACCOUNT_ID, "BIZINFO", "PBLN_TEST")

        assertEquals(SupportProgramConditionCheckStatus.CHECKED, result.status)
        assertEquals(SupportProgramConditionEvaluation(SupportProgramConditionResult.UNKNOWN, emptyList()), result.evaluation)
    }

    @Test
    fun unknownProgramIsNotFoundWithoutReadingAnalysisOrCompany() {
        assertThrows(SupportProgramNotFoundException::class.java) { service.check(ACCOUNT_ID, "BIZINFO", "PBLN_MISSING") }
        Mockito.verifyNoInteractions(analyses, companies)
    }

    private fun stubProgram() {
        Mockito.`when`(programs.findPresentBySourceAndProgramId("BIZINFO", "PBLN_TEST"))
            .thenReturn(SupportProgramTestHelper.catalogProgram("PBLN_TEST"))
    }

    private fun stubAnalysis(analysis: SupportProgramAnalysis) {
        Mockito.`when`(analyses.findCurrent("BIZINFO", "PBLN_TEST")).thenReturn(analysis)
    }

    private fun stubCompany(region: String, foundedYear: Int) {
        val at = LocalDateTime.of(2026, 1, 1, 0, 0)
        Mockito.`when`(companies.findByAccountId(ACCOUNT_ID)).thenReturn(Company(
            id = 3L, accountId = ACCOUNT_ID, businessNumber = "1234567890", companyName = "테스트 주식회사",
            businessStatus = "계속사업자", businessStatusCode = "01",
            profile = CompanyProfileInput(region, "정보통신업", foundedYear, null),
            businessVerifiedAt = at, createdAt = at, updatedAt = at,
        ))
    }

    private fun completed(vararg conditions: SupportProgramAnalysisCondition) = SupportProgramAnalysis(
        status = SupportProgramAnalysisStatus.COMPLETED,
        analyzedAt = analyzedAt,
        content = SupportProgramAnalysisContent(
            summaryLine = null, supportTypes = emptyList(), supportAmount = null, selectionScale = null,
            conditions = conditions.toList(), contact = null,
        ),
    )

    private fun condition(
        kind: SupportProgramAnalysisConditionKind,
        category: SupportProgramAnalysisConditionCategory,
        regions: List<String>? = null,
        maxYears: Double? = null,
    ) = SupportProgramAnalysisCondition(
        kind, category, "조건", SupportProgramAnalysisConditionValues(regions = regions, maxYears = maxYears),
        SupportProgramAnalysisEvidence(SupportProgramAnalysisEvidenceField.TARGET_DESCRIPTION, "공고 원문"),
    )

    private companion object {
        const val ACCOUNT_ID = 7L
    }
}
