package ai.govbiz.core.govagent.controller

import ai.govbiz.core._common.exception.ApiExceptionHandler
import ai.govbiz.core.account.domain.AccountRole
import ai.govbiz.core.account.helper.AccountTestHelper
import ai.govbiz.core.account.service.AccountSessionService
import ai.govbiz.core.admin.web.AdminPrincipalArgumentResolver
import ai.govbiz.core.govagent.domain.GovAgentQuestion
import ai.govbiz.core.govagent.service.GovAgentService
import ai.govbiz.core.govagent.service.dto.GovAgentOutcome
import ai.govbiz.core.govagent.service.dto.GovAgentResult
import ai.govbiz.core.supportprogram.domain.SupportProgramCompanyConditions
import ai.govbiz.core.supportprogram.domain.SupportProgramConversationContext
import ai.govbiz.core.supportprogram.service.admission.SupportProgramRequestAdmissionService
import ai.govbiz.core.supportprogram.service.admission.config.SupportProgramRequestAdmissionProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class GovAgentControllerTest {
    private val sessions = mock(AccountSessionService::class.java)
    private val service = mock(GovAgentService::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(GovAgentController(service,
        SupportProgramRequestAdmissionService(SupportProgramRequestAdmissionProperties())))
        .setCustomArgumentResolvers(AdminPrincipalArgumentResolver { sessions })
        .setControllerAdvice(ApiExceptionHandler()).build()
    private val admin = AccountTestHelper.account(id = 7, role = AccountRole.ADMIN)
    private val body = """{"conversation":{"message":"지원 대상은?","context":{"query":null,"acceptingOnly":true,
        "companyConditions":{"region":null,"industry":null,"establishedOn":null,"supportPurpose":null}}},"selectedProgram":null}"""

    @Test
    fun memberCannotUseAdminEndpoint() {
        doReturn(admin.copy(role = AccountRole.USER)).`when`(sessions).requireAccount("test-session")
        mvc.perform(post("/api/v1/gov-agent/messages").header("Authorization", "Bearer test-session")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden())
        verifyNoInteractions(service)
    }

    @Test
    fun adminGetsTypedSelectionRequestWithoutCaching() {
        doReturn(admin).`when`(sessions).requireAccount("test-session")
        val question = GovAgentQuestion("지원 대상은?", SupportProgramConversationContext(null, true, SupportProgramCompanyConditions()), null, null, null, null)
        doReturn(GovAgentResult(GovAgentOutcome.NEEDS_PROGRAM, message = "공고를 선택해 주세요.")).`when`(service).answer(admin, "127.0.0.1", question)
        mvc.perform(post("/api/v1/gov-agent/messages").header("Authorization", "Bearer test-session")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.outcome").value("NEEDS_PROGRAM"))
    }

    @Test
    fun rejectsInvalidCompositeIdentityBeforeAnyExecution() {
        doReturn(admin).`when`(sessions).requireAccount("test-session")
        val invalid = body.replace("\"selectedProgram\":null", "\"selectedProgram\":{\"sourceCode\":\"https://invalid\",\"sourceProgramId\":\"P001\"}")
        mvc.perform(post("/api/v1/gov-agent/messages").header("Authorization", "Bearer test-session")
            .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest())
        verifyNoInteractions(service)
    }
}
