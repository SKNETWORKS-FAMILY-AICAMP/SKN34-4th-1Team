package ai.govbiz.core.supportprogram.facade

import ai.govbiz.core.supportprogram.client.bizinfo.BizInfoAttachmentClient
import ai.govbiz.core.supportprogram.client.cntradenotice.CnTradeNoticeAttachmentClient
import ai.govbiz.core.supportprogram.client.document.SupportProgramAttachments
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentException
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentParser
import ai.govbiz.core.supportprogram.client.kstartup.KStartupAttachmentClient
import ai.govbiz.core.supportprogram.client.msit.MsitAttachmentClient
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentText
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentTexts
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 공고의 공식 첨부를 제공처별 기존 첨부 Client로 수집하고 [SupportProgramDocumentParser]로 본문을 추출하는 단일 진입점입니다.
 *
 * 제공처 연결 실패·시간 초과([SupportProgramDocumentException.Reason.UNAVAILABLE])만 예외로 전달합니다.
 * 첨부가 없거나 목록을 검증할 수 없으면 빈 결과이고, 파일별 파싱 실패(미지원·크기 초과·본문 없음)는 그 파일만 제외하고 셉니다.
 */
@Component
class SupportProgramAttachmentTextFacade(
    private val bizInfoAttachments: BizInfoAttachmentClient,
    private val msitAttachments: MsitAttachmentClient,
    private val kStartupAttachments: KStartupAttachmentClient,
    private val cnTradeNoticeAttachments: CnTradeNoticeAttachmentClient,
    private val parser: SupportProgramDocumentParser,
) {
    fun load(program: SupportProgram): SupportProgramAttachmentTexts {
        val collected = try {
            collect(program) ?: return SupportProgramAttachmentTexts.NONE
        } catch (error: SupportProgramDocumentException) {
            if (error.reason == SupportProgramDocumentException.Reason.UNAVAILABLE) throw error
            // 첨부 없음(UNSUPPORTED)·목록 한도 초과·페이지 검증 실패는 첨부 없이 분석합니다.
            logger.info("support_program_attachment_text outcome=NO_ATTACHMENTS reason={}", error.reason.name)
            return SupportProgramAttachmentTexts.NONE
        }
        var skipped = 0
        val files = collected.files.mapNotNull { file ->
            val text = try {
                parser.parse(file.bytes, file.format).joinToString("\n") { it.text }.trim()
            } catch (_: SupportProgramDocumentException) {
                skipped++
                return@mapNotNull null
            }
            if (text.isBlank()) {
                skipped++
                null
            } else {
                SupportProgramAttachmentText(file.fileName, text)
            }
        }
        return SupportProgramAttachmentTexts(files, skipped)
    }

    private fun collect(program: SupportProgram): SupportProgramAttachments? = when (program.sourceCode) {
        "BIZINFO" -> bizInfoAttachments.collect(program.sourceCode, program.id)
        "MSIT" -> msitAttachments.collect(program.sourceCode, program.id, program.sourceUrl)
        "KSTARTUP" -> kStartupAttachments.collect(program.sourceCode, program.id, program.sourceUrl)
        "CNTRADE_NOTICE" -> cnTradeNoticeAttachments.collect(
            program.sourceCode, program.id, program.title, program.targetDescription,
        )
        else -> null
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SupportProgramAttachmentTextFacade::class.java)
    }
}
