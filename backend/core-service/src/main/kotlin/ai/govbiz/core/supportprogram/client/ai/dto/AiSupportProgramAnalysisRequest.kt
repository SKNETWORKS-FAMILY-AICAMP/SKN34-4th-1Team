package ai.govbiz.core.supportprogram.client.ai.dto

/**
 * AI Service 공고 분석 요청(v2)입니다. 필드별 최대 길이는 AI Service 계약에 맞춰 호출 전에 자릅니다.
 * attachments는 첨부가 없으면 빈 목록으로 보냅니다(null은 AI Service가 거부합니다).
 */
data class AiSupportProgramAnalysisRequest(
    val sourceCode: String,
    val sourceProgramId: String,
    val title: String,
    val organization: String,
    val summary: String,
    val targetDescription: String,
    val applicationPeriod: String,
    val applicationMethod: String?,
    val detailText: String?,
    val attachments: List<AiSupportProgramAnalysisAttachmentRequest> = emptyList(),
)

/** 첨부 한 건의 이름(최대 255자)과 추출 본문입니다. 전체 첨부 본문 합계는 40,000자 이하입니다. */
data class AiSupportProgramAnalysisAttachmentRequest(
    val name: String,
    val text: String,
)
