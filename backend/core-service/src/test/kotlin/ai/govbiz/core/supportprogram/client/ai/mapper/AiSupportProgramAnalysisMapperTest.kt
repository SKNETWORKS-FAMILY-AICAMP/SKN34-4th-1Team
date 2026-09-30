package ai.govbiz.core.supportprogram.client.ai.mapper

import ai.govbiz.core.supportprogram.domain.SupportProgramApplicationRoute
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentText
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AiSupportProgramAnalysisMapperTest {
    private val program = SupportProgramTestHelper.catalogProgram("PBLN_1").program

    @Test
    fun clipsEveryFieldToTheAiContractByCodePointsWithoutBreakingSurrogatePairs() {
        val emoji = "🚀"
        val request = AiSupportProgramAnalysisMapper.toRequest(
            program.copy(
                title = emoji.repeat(501),
                organization = "가".repeat(300),
                summary = emoji.repeat(20_001),
                targetDescription = "나".repeat(8_001),
                applicationPeriod = "다".repeat(1_001),
                applicationRoute = SupportProgramApplicationRoute(method = emoji.repeat(8_001)),
            ),
            detailText = emoji.repeat(30_001),
        )

        assertEquals(emoji.repeat(500), request.title)
        assertEquals("가".repeat(255), request.organization)
        assertEquals(emoji.repeat(20_000), request.summary)
        assertEquals("나".repeat(8_000), request.targetDescription)
        assertEquals("다".repeat(1_000), request.applicationPeriod)
        assertEquals(emoji.repeat(8_000), request.applicationMethod)
        assertEquals(emoji.repeat(30_000), request.detailText)
        assertEquals("BIZINFO", request.sourceCode)
        assertEquals("PBLN_1", request.sourceProgramId)
    }

    @Test
    fun keepsShortValuesAndSendsNullForMissingOptionalText() {
        val request = AiSupportProgramAnalysisMapper.toRequest(program, detailText = "  ")

        assertEquals(program.title, request.title)
        assertEquals(program.summary, request.summary)
        assertNull(request.applicationMethod)
        assertNull(request.detailText)
    }

    @Test
    fun sendsNoticeAttachmentsFirstThenOriginalOrderAndAnEmptyListWhenThereAreNone() {
        val request = AiSupportProgramAnalysisMapper.toRequest(program, null, listOf(
            SupportProgramAttachmentText("신청서.hwp", "신청서 본문"),
            SupportProgramAttachmentText("2026 모집공고.pdf", "공고 본문"),
            SupportProgramAttachmentText("붙임.docx", "붙임 본문"),
            SupportProgramAttachmentText("공고 요약.hwpx", "요약 본문"),
        ))

        assertEquals(listOf("2026 모집공고.pdf", "공고 요약.hwpx", "신청서.hwp", "붙임.docx"), request.attachments.map { it.name })
        assertEquals("공고 본문", request.attachments.first().text)
        assertEquals(emptyList<Any>(), AiSupportProgramAnalysisMapper.toRequest(program, null).attachments)
    }

    @Test
    fun capsAttachmentsAtEightAndTheTotalTextBudgetByCodePoints() {
        val many = (1..10).map { SupportProgramAttachmentText("첨부$it.pdf", "본문 $it") }
        assertEquals((1..8).map { "첨부$it.pdf" }, AiSupportProgramAnalysisMapper.toRequest(program, null, many).attachments.map { it.name })

        val emoji = "🚀"
        val request = AiSupportProgramAnalysisMapper.toRequest(program, null, listOf(
            SupportProgramAttachmentText("a.pdf", emoji.repeat(30_000)),
            SupportProgramAttachmentText("b.pdf", emoji.repeat(15_000)),
            SupportProgramAttachmentText("c.pdf", "예산 소진 뒤 첨부"),
        ))

        assertEquals(listOf("a.pdf", "b.pdf"), request.attachments.map { it.name })
        assertEquals(emoji.repeat(30_000), request.attachments[0].text)
        assertEquals(emoji.repeat(10_000), request.attachments[1].text)
        assertEquals(40_000, request.attachments.sumOf { it.text.codePointCount(0, it.text.length) })
    }

    @Test
    fun clipsLongNamesAndNamesBlankAttachments() {
        val request = AiSupportProgramAnalysisMapper.toRequest(program, null, listOf(
            SupportProgramAttachmentText("  ", "이름 없는 첨부"),
            SupportProgramAttachmentText("가".repeat(300), "긴 이름"),
        ))

        assertEquals(listOf("첨부 1", "가".repeat(255)), request.attachments.map { it.name })
    }
}
