package ai.govbiz.core.govagent.client.dto

data class AiGovAgentRequest(
    val message: String,
    val hasSelectedProgram: Boolean,
    val searchQuery: String?,
    val pendingSearchQuestion: String?,
)

data class AiGovAgentPayload(val action: String? = null)
