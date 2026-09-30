package ai.govbiz.core.supportprogram.controller

import ai.govbiz.core._common.exception.ApiExceptionHandler
import ai.govbiz.core.account.domain.Account
import ai.govbiz.core.account.domain.AccountRole
import ai.govbiz.core.account.domain.Company
import ai.govbiz.core.account.domain.CompanyProfileInput
import ai.govbiz.core.account.repository.CompanyRepository
import ai.govbiz.core.account.service.AccountSessionService
import ai.govbiz.core.account.service.exception.AuthenticationRequiredException
import ai.govbiz.core.account.web.AuthenticatedAccountArgumentResolver
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisCondition
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionValues
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidence
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.conditioncheck.SupportProgramConditionCheckService
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SupportProgramConditionCheckControllerTest {
    private val programs = Mockito.mock(SupportProgramRepository::class.java)
    private val analyses = Mockito.mock(SupportProgramAnalysisRepository::class.java)
    private val companies = Mockito.mock(CompanyRepository::class.java)
    private val sessions = Mockito.mock(AccountSessionService::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(SupportProgramConditionCheckController(SupportProgramConditionCheckService(programs, analyses, companies, clock)))
        .setCustomArgumentResolvers(AuthenticatedAccountArgumentResolver(sessions))
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    init {
        Mockito.doAnswer { call ->
            if (call.getArgument<String?>(0) != "member-one") throw AuthenticationRequiredException()
            Account(ACCOUNT_ID, "member@example.invalid", AccountRole.USER, null, null, LocalDateTime.parse("2026-01-01T00:00:00"))
        }.`when`(sessions).requireAccount(Mockito.nullable(String::class.java))
    }

    @Test
    fun returnsTheStableCheckedContractInTheDetailConditionOrder() {
        stubProgram()
        stubCompletedAnalysis(
            condition(SupportProgramAnalysisConditionKind.REQUIRED, SupportProgramAnalysisConditionCategory.REGION, regions = listOf("서울", "경기")),
            condition(SupportProgramAnalysisConditionKind.REQUIRED, SupportProgramAnalysisConditionCategory.BUSINESS_AGE, maxYears = 7.0),
            condition(SupportProgramAnalysisConditionKind.EXCLUDED, SupportProgramAnalysisConditionCategory.BUSINESS_AGE, maxYears = 0.0),
            condition(SupportProgramAnalysisConditionKind.PREFERRED, SupportProgramAnalysisConditionCategory.INDUSTRY),
        )
        stubCompany("서울특별시", 2019)

        mvc.perform(checkRequest().member())
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {
                  "status": "CHECKED",
                  "analyzedAt": "2026-09-30T10:00:00",
                  "referenceDate": "2026-10-01",
                  "profile": { "region": "서울", "foundedYear": 2019 },
                  "overall": "UNKNOWN",
                  "conditions": [
                    { "index": 0, "result": "MET", "reason": "REGION_MATCH" },
                    { "index": 1, "result": "UNKNOWN", "reason": "BOUNDARY_YEAR" },
                    { "index": 2, "result": "MET", "reason": "PRE_STARTUP_ONLY" },
                    { "index": 3, "result": "UNKNOWN", "reason": "NOT_COMPARABLE" }
                  ]
                }
            """.trimIndent(), JsonCompareMode.STRICT))
    }

    @Test
    fun accountWithoutACompanyReceivesNoCompanyWithEmptyConditions() {
        stubProgram()
        stubCompletedAnalysis(
            condition(SupportProgramAnalysisConditionKind.REQUIRED, SupportProgramAnalysisConditionCategory.REGION, regions = listOf("서울")),
        )

        mvc.perform(checkRequest().member())
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {
                  "status": "NO_COMPANY",
                  "analyzedAt": "2026-09-30T10:00:00",
                  "referenceDate": "2026-10-01",
                  "profile": null,
                  "overall": null,
                  "conditions": []
                }
            """.trimIndent(), JsonCompareMode.STRICT))
    }

    @Test
    fun programWithoutACurrentCompletedAnalysisIsNotAnalyzed() {
        stubProgram()
        Mockito.`when`(analyses.findCurrent("BIZINFO", "PBLN_TEST")).thenReturn(SupportProgramAnalysis.FAILED)
        stubCompany("경기도", 2024)

        mvc.perform(checkRequest().member())
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {
                  "status": "NOT_ANALYZED",
                  "analyzedAt": null,
                  "referenceDate": "2026-10-01",
                  "profile": { "region": "경기", "foundedYear": 2024 },
                  "overall": null,
                  "conditions": []
                }
            """.trimIndent(), JsonCompareMode.STRICT))
    }

    @Test
    fun unknownProgramUsesTheDetailNotFoundProblem() {
        mvc.perform(checkRequest(sourceProgramId = "PBLN_MISSING").member())
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:govbiz:problem:support-program-not-found"))
            .andExpect(jsonPath("$.code").value("SUPPORT_PROGRAM_NOT_FOUND"))
            .andExpect(jsonPath("$.instance").value(PATH))
    }

    @Test
    fun missingOrInvalidSessionIsUnauthorizedBeforeAnyLookup() {
        mvc.perform(checkRequest()).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        mvc.perform(checkRequest().member("expired-session")).andExpect(status().isUnauthorized())
        Mockito.verifyNoInteractions(programs, analyses, companies)
    }

    @Test
    fun invalidCompositeIdentityIsRejectedBeforeAnyLookup() {
        for (request in listOf(
            checkRequest(sourceCode = "other:source"),
            checkRequest(sourceCode = " "),
            checkRequest(sourceProgramId = " PBLN_TEST "),
            get(PATH).queryParam("sourceCode", "BIZINFO"),
        )) {
            mvc.perform(request.member())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
        }
        Mockito.verifyNoInteractions(programs, analyses, companies)
    }

    private fun checkRequest(sourceCode: String = "BIZINFO", sourceProgramId: String = "PBLN_TEST") =
        get(PATH).queryParam("sourceCode", sourceCode).queryParam("sourceProgramId", sourceProgramId)

    private fun MockHttpServletRequestBuilder.member(token: String = "member-one") = cookie(Cookie("govbiz_session", token))

    private fun stubProgram() {
        Mockito.`when`(programs.findPresentBySourceAndProgramId("BIZINFO", "PBLN_TEST"))
            .thenReturn(SupportProgramTestHelper.catalogProgram("PBLN_TEST"))
    }

    private fun stubCompletedAnalysis(vararg conditions: SupportProgramAnalysisCondition) {
        Mockito.`when`(analyses.findCurrent("BIZINFO", "PBLN_TEST")).thenReturn(SupportProgramAnalysis(
            status = SupportProgramAnalysisStatus.COMPLETED,
            analyzedAt = LocalDateTime.of(2026, 9, 30, 10, 0, 0, 987_000_000),
            content = SupportProgramAnalysisContent(
                summaryLine = null, supportTypes = emptyList(), supportAmount = null, selectionScale = null,
                conditions = conditions.toList(), contact = null,
            ),
        ))
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
        const val PATH = "/api/v1/me/support-programs/condition-check"
        const val ACCOUNT_ID = 7L
    }
}
