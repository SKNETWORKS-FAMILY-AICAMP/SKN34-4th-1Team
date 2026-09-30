package ai.govbiz.core.supportprogram.domain

/** 공고 첨부 한 건에서 추출한 본문입니다. 원래 첨부 순서를 유지합니다. */
data class SupportProgramAttachmentText(
    val name: String,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "attachment text must not be blank" }
    }
}

/**
 * 공고 첨부 본문 수집 결과입니다. skippedCount는 형식 미지원·크기 초과·본문 없음 등으로 읽지 못해 제외한 첨부 수이며,
 * 첨부가 없거나 목록 자체를 쓸 수 없으면 files는 비어 있습니다.
 */
data class SupportProgramAttachmentTexts(
    val files: List<SupportProgramAttachmentText>,
    val skippedCount: Int,
) {
    init {
        require(skippedCount >= 0) { "skippedCount must not be negative" }
    }

    companion object {
        val NONE = SupportProgramAttachmentTexts(emptyList(), 0)
    }
}
