package ai.govbiz.core.govagent.service.dto

import ai.govbiz.core.govagent.domain.GovAgentProgram
import ai.govbiz.core.supportprogram.service.dto.SupportProgramConversationResult
import ai.govbiz.core.supportprogram.service.dto.SupportProgramEvidenceAnswerResult

enum class GovAgentOutcome { SEARCH, EVIDENCE, NEEDS_PROGRAM, UNSUPPORTED }

data class GovAgentResult(
    val outcome: GovAgentOutcome,
    val interpretation: SupportProgramConversationResult? = null,
    val evidence: SupportProgramEvidenceAnswerResult? = null,
    val program: GovAgentProgram? = null,
    val message: String? = null,
)
