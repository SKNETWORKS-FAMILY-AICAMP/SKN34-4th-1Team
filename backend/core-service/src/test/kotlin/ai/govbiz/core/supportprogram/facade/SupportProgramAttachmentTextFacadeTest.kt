package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core.supportprogram.client.bizinfo.BizInfoAttachmentClient
import ai.govbiz.core.supportprogram.client.cntradenotice.CnTradeNoticeAttachmentClient
import ai.govbiz.core.supportprogram.client.document.SupportProgramAttachment
import ai.govbiz.core.supportprogram.client.document.SupportProgramAttachments
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentBlock
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentException
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentException.Reason
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentParser
import ai.govbiz.core.supportprogram.client.kstartup.KStartupAttachmentClient
import ai.govbiz.core.supportprogram.client.msit.MsitAttachmentClient
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentText
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentTexts
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SupportProgramAttachmentTextFacadeTest {
    @Mock private lateinit var bizInfo: BizInfoAttachmentClient
    @Mock private lateinit var msit: MsitAttachmentClient
    @Mock private lateinit var kStartup: KStartupAttachmentClient
    @Mock private lateinit var cnTradeNotice: CnTradeNoticeAttachmentClient
    @Mock private lateinit var parser: SupportProgramDocumentParser

    private val program = SupportProgramTestHelper.catalogProgram("PBLN_1").program
    private lateinit var facade: SupportProgramAttachmentTextFacade

    @BeforeEach
    fun setUp() {
        facade = SupportProgramAttachmentTextFacade(bizInfo, msit, kStartup, cnTradeNotice, parser)
    }

    @Test
    fun extractsTextInOriginalOrderAndSkipsFilesThatCannotBeRead() {
        val files = listOf(file("공고문.hwp", "HWP"), file("스캔본.pdf", "PDF"), file("큰 양식.xlsx", "XLSX"), file("신청서.docx", "DOCX"))
        doReturn(SupportProgramAttachments("공고", files, emptyList())).`when`(bizInfo).collect("BIZINFO", "PBLN_1")
        doReturn(listOf(SupportProgramDocumentBlock("p1", "공고 첫 문단"), SupportProgramDocumentBlock("p2", "공고 둘째 문단")))
            .`when`(parser).parse(files[0].bytes, "HWP")
        doThrow(SupportProgramDocumentException(Reason.UNSUPPORTED)).`when`(parser).parse(files[1].bytes, "PDF")
        doThrow(SupportProgramDocumentException(Reason.TOO_LARGE)).`when`(parser).parse(files[2].bytes, "XLSX")
        doReturn(listOf(SupportProgramDocumentBlock("p1", "신청서 본문"))).`when`(parser).parse(files[3].bytes, "DOCX")

        val result = facade.load(program)

        assertEquals(
            SupportProgramAttachmentTexts(
                listOf(
                    SupportProgramAttachmentText("공고문.hwp", "공고 첫 문단\n공고 둘째 문단"),
                    SupportProgramAttachmentText("신청서.docx", "신청서 본문"),
                ),
                skippedCount = 2,
            ),
            result,
        )
    }

    @ParameterizedTest
    @EnumSource(value = Reason::class, names = ["UNSUPPORTED", "NOT_FOUND", "INVALID", "TOO_LARGE"])
    fun analyzesWithoutAttachmentsWhenTheListIsMissingOrUnusable(reason: Reason) {
        doThrow(SupportProgramDocumentException(reason)).`when`(bizInfo).collect("BIZINFO", "PBLN_1")
        assertEquals(SupportProgramAttachmentTexts.NONE, facade.load(program))
        verifyNoInteractions(parser)
    }

    @Test
    fun providerConnectionFailuresAreReportedToTheCaller() {
        doThrow(SupportProgramDocumentException(Reason.UNAVAILABLE)).`when`(bizInfo).collect("BIZINFO", "PBLN_1")
        val error = assertThrows(SupportProgramDocumentException::class.java) { facade.load(program) }
        assertEquals(Reason.UNAVAILABLE, error.reason)
    }

    @Test
    fun usesEachProvidersOwnAttachmentClient() {
        val kStartupProgram = program.copy(sourceCode = "KSTARTUP", id = "177911", sourceUrl = "https://www.k-startup.go.kr/x")
        doReturn(SupportProgramAttachments("공고", emptyList(), emptyList())).`when`(kStartup)
            .collect("KSTARTUP", "177911", "https://www.k-startup.go.kr/x")
        assertEquals(SupportProgramAttachmentTexts.NONE, facade.load(kStartupProgram))

        assertEquals(SupportProgramAttachmentTexts.NONE, facade.load(program.copy(sourceCode = "UNKNOWN")))
        verifyNoInteractions(bizInfo, msit, cnTradeNotice)
    }

    private fun file(name: String, format: String) =
        SupportProgramAttachment("https://www.bizinfo.go.kr/file/$name", name, format, name.toByteArray())
}
