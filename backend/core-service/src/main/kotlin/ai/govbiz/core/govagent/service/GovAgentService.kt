package ai.govbiz.core.govagent.service

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.account.domain.Account
import ai.govbiz.core.admin.service.exception.AdminAccessDeniedException
import ai.govbiz.core.assistant.service.AssistantPiiMasker
import ai.govbiz.core.govagent.client.AiGovAgentClient
import ai.govbiz.core.govagent.client.dto.AiGovAgentRequest
import ai.govbiz.core.govagent.domain.GovAgentQuestion
import ai.govbiz.core.govagent.service.dto.GovAgentOutcome
import ai.govbiz.core.govagent.service.dto.GovAgentResult
import ai.govbiz.core.planusage.domain.PlanUsageFeature
import ai.govbiz.core.planusage.service.PlanUsageService
import ai.govbiz.core.supportprogram.service.conversation.SupportProgramConversationService
import ai.govbiz.core.supportprogram.service.evidence.SupportProgramEvidenceService
import org.springframework.stereotype.Service

/** 한 턴에 한 업무만 위임한다. 검색은 조건 제안까지만 준비하고 사용자 확인 뒤 기존 검색 API가 실행한다. */
@Service
class GovAgentService(
    private val client: AiGovAgentClient,
    private val conversationService: SupportProgramConversationService,
    private val evidenceService: SupportProgramEvidenceService,
    private val planUsageService: PlanUsageService,
) {
    fun answer(account: Account, clientIp: String, question: GovAgentQuestion): GovAgentResult {
        if (!account.isAdmin) throw AdminAccessDeniedException()
        val decision = client.decide(AiGovAgentRequest(
            AssistantPiiMasker.mask(question.message), question.selectedProgram != null,
            question.context.query?.let(AssistantPiiMasker::mask),
            question.pendingClarification?.question?.let(AssistantPiiMasker::mask),
        ))
        return when (decision.action) {
            "SEARCH" -> GovAgentResult(GovAgentOutcome.SEARCH, interpretation = conversationService.interpret(
                question.message, question.context, question.pendingClarification, question.pendingProposal, question.lastSearch,
            ))
            "EVIDENCE" -> {
                val program = question.selectedProgram ?: return GovAgentResult(
                    GovAgentOutcome.NEEDS_PROGRAM, message = "검색 결과에서 ‘이 공고 질문’을 눌러 공고를 선택한 뒤 질문해 주세요.",
                )
                // AI가 만든 공고 ID·URL로 호출하지 않는다. 사용자가 선택한 복합 식별자로 기존 근거 검증을 거친다.
                val evidence = planUsageService.consume(account, clientIp, PlanUsageFeature.EVIDENCE_QUESTION) {
                    evidenceService.answer(program.sourceCode, program.sourceProgramId, question.message)
                }
                GovAgentResult(GovAgentOutcome.EVIDENCE, evidence = evidence, program = program)
            }
            "UNSUPPORTED" -> GovAgentResult(GovAgentOutcome.UNSUPPORTED,
                message = "현재 Gov 에이전트에서는 지원사업 검색과 선택한 공고의 원문 질문을 처리합니다. 신청서 작성·비교·저장 등 다른 기능은 해당 화면에서 이용해 주세요. 여러 작업은 하나씩 요청해 주세요.")
            else -> throw AiServiceCallException.invalidResponse("Unknown Gov agent action", null)
        }
    }
}
