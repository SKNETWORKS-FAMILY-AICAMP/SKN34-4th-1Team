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

/** 한 턴에 한 업무만 위임한다. 검색·신청 준비는 안내 뒤 사용자의 명시적인 실행을 기다린다. */
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
                    GovAgentOutcome.NEEDS_PROGRAM, message = "검색 결과에서 공고를 선택한 뒤 질문해 주세요.",
                )
                // AI가 만든 공고 ID·URL로 호출하지 않는다. 사용자가 선택한 복합 식별자로 기존 근거 검증을 거친다.
                val evidence = planUsageService.consume(account, clientIp, PlanUsageFeature.EVIDENCE_QUESTION) {
                    evidenceService.answer(program.sourceCode, program.sourceProgramId, question.message)
                }
                GovAgentResult(GovAgentOutcome.EVIDENCE, evidence = evidence, program = program)
            }
            "APPLICATION" -> {
                val program = question.selectedProgram ?: return GovAgentResult(
                    GovAgentOutcome.NEEDS_PROGRAM, message = "신청서를 준비할 공고를 검색 결과에서 선택한 뒤 요청해 주세요.",
                )
                GovAgentResult(GovAgentOutcome.APPLICATION, program = program,
                    message = "신청 양식과 지원 분야를 확인한 뒤 작성을 시작해 주세요. 저장된 양식이 없으면 분석을 요청할 수 있습니다.")
            }
            "UNSUPPORTED" -> GovAgentResult(GovAgentOutcome.UNSUPPORTED,
                message = "현재 Gov 에이전트에서는 지원사업 검색, 선택한 공고의 원문 질문과 신청서 준비를 지원합니다. 외부 기관 제출·삭제·여러 작업 일괄 실행은 지원하지 않습니다. 비교·저장 등 다른 기능은 해당 화면에서 이용해 주세요.")
            else -> throw AiServiceCallException.invalidResponse("Unknown Gov agent action", null)
        }
    }
}
