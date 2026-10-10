package ai.govbiz.core.govagent.service

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.account.domain.AccountRole
import ai.govbiz.core.account.helper.AccountTestHelper
import ai.govbiz.core.admin.service.exception.AdminAccessDeniedException
import ai.govbiz.core.govagent.client.AiGovAgentClient
import ai.govbiz.core.govagent.client.dto.AiGovAgentPayload
import ai.govbiz.core.govagent.client.dto.AiGovAgentRequest
import ai.govbiz.core.govagent.domain.GovAgentProgram
import ai.govbiz.core.govagent.domain.GovAgentQuestion
import ai.govbiz.core.govagent.service.dto.GovAgentOutcome
import ai.govbiz.core.planusage.domain.PlanUsageFeature
import ai.govbiz.core.planusage.service.PlanUsageService
import ai.govbiz.core.supportprogram.domain.SupportProgramCompanyConditions
import ai.govbiz.core.supportprogram.domain.SupportProgramConversationContext
import ai.govbiz.core.supportprogram.domain.SupportProgramConversationStatus
import ai.govbiz.core.supportprogram.service.conversation.SupportProgramConversationService
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConversationResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramEvidenceAnswerResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramEvidenceAnswerStatus
import ai.govbiz.core.supportprogram.service.evidence.SupportProgramEvidenceService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class GovAgentServiceTest {
    private val client = mock(AiGovAgentClient::class.java)
    private val conversation = mock(SupportProgramConversationService::class.java)
    private val evidence = mock(SupportProgramEvidenceService::class.java)
    private val usage = mock(PlanUsageService::class.java)
    private val service = GovAgentService(client, conversation, evidence, usage)
    private val admin = AccountTestHelper.account(id = 7, role = AccountRole.ADMIN)
    private val context = SupportProgramConversationContext(null, true, SupportProgramCompanyConditions(null, null, null, null))
    private val program = GovAgentProgram("BIZINFO", "P001")
    private fun question(selected: GovAgentProgram? = program) = GovAgentQuestion("신청 방법은?", context, null, null, null, selected)
    private fun decide(action: String, selected: Boolean = true) {
        doReturn(AiGovAgentPayload(action)).`when`(client).decide(AiGovAgentRequest("신청 방법은?", selected, null, null))
    }

    @Test
    fun rejectsMembersBeforeAnyModelOrToolCall() {
        assertThrows(AdminAccessDeniedException::class.java) { service.answer(admin.copy(role = AccountRole.USER), "127.0.0.1", question()) }
        verifyNoInteractions(client, conversation, evidence, usage)
    }

    @Test
    fun searchOnlyPreparesAProposalAndDoesNotChargeOrAskEvidence() {
        decide("SEARCH")
        val proposed = SupportProgramConversationResult(SupportProgramConversationStatus.READY, context.copy(query = "창업 지원"), null, emptyList(), null)
        doReturn(proposed).`when`(conversation).interpret("신청 방법은?", context, null, null, null)
        assertEquals(proposed, service.answer(admin, "127.0.0.1", question()).interpretation)
        verifyNoInteractions(evidence, usage)
    }

    @Test
    fun asksForSelectionWithoutInventingProgramOrCharging() {
        decide("EVIDENCE", selected = false)
        assertEquals(GovAgentOutcome.NEEDS_PROGRAM, service.answer(admin, "127.0.0.1", question(null)).outcome)
        verifyNoInteractions(conversation, evidence, usage)
    }

    @Test
    fun evidenceUsesSelectedCompositeIdentityAndExistingQuotaBoundary() {
        decide("EVIDENCE")
        val answer = SupportProgramEvidenceAnswerResult("근거 부족", SupportProgramEvidenceAnswerStatus.INSUFFICIENT_EVIDENCE, emptyList())
        doReturn(answer).`when`(evidence).answer("BIZINFO", "P001", "신청 방법은?")
        doAnswer { invocation -> invocation.getArgument<() -> SupportProgramEvidenceAnswerResult>(3).invoke() }
            .`when`(usage).consume(eq(admin), eq("127.0.0.1") ?: "", eq(PlanUsageFeature.EVIDENCE_QUESTION) ?: PlanUsageFeature.EVIDENCE_QUESTION,
                any<() -> SupportProgramEvidenceAnswerResult>() ?: { answer })
        val result = service.answer(admin, "127.0.0.1", question())
        assertEquals(answer, result.evidence)
        assertEquals(program, result.program)
        verify(evidence).answer("BIZINFO", "P001", "신청 방법은?")
        verifyNoInteractions(conversation)
    }

    @Test
    fun unsupportedRequestsDoNotExecuteAnyFeature() {
        decide("UNSUPPORTED")
        assertEquals(GovAgentOutcome.UNSUPPORTED, service.answer(admin, "127.0.0.1", question()).outcome)
        verifyNoInteractions(conversation, evidence, usage)
    }

    @Test
    fun unknownDecisionFailsInsteadOfSearching() {
        decide("DELETE")
        assertThrows(AiServiceCallException::class.java) { service.answer(admin, "127.0.0.1", question()) }
        verifyNoInteractions(conversation, evidence, usage)
    }
}
