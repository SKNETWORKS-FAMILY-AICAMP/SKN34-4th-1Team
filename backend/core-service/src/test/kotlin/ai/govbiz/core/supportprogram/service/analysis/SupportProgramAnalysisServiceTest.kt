package ai.govbiz.core.supportprogram.service.analysis

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramAnalysisClient
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisRejectedException
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisVersionMismatchException
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentException
import ai.govbiz.core.supportprogram.client.ai.mapper.AiSupportProgramAnalysisMapper
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisLease
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisOutput
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentText
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentTexts
import ai.govbiz.core.supportprogram.domain.SupportProgramSourceDocument
import ai.govbiz.core.supportprogram.facade.SupportProgramAttachmentTextFacade
import ai.govbiz.core.supportprogram.helper.SupportProgramContentHashHelper
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.analysis.config.SupportProgramAnalysisProperties
import ai.govbiz.core.supportprogram.service.evidence.SupportProgramEvidenceService
import ai.govbiz.core.supportprogram.service.evidence.exception.SupportProgramEvidenceUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SupportProgramAnalysisServiceTest {
    @Mock private lateinit var repository: SupportProgramAnalysisRepository
    @Mock private lateinit var programs: SupportProgramRepository
    @Mock private lateinit var evidenceService: SupportProgramEvidenceService
    @Mock private lateinit var client: AiSupportProgramAnalysisClient
    @Mock private lateinit var attachments: SupportProgramAttachmentTextFacade

    private val now = LocalDateTime.of(2026, 10, 1, 9, 30)
    private val clock = Clock.fixed(Instant.parse("2026-10-01T00:30:00Z"), ZoneId.of("Asia/Seoul"))
    private val properties = SupportProgramAnalysisProperties(enabled = true, dailyLimit = 5, leaseSeconds = 300, maxAttempts = 3)
    private val catalogProgram = SupportProgramTestHelper.catalogProgram("PBLN_1")
    private val lease = SupportProgramAnalysisLease("BIZINFO", "PBLN_1", "a".repeat(64), "lease-1", 1)
    private val output = SupportProgramAnalysisOutput(
        analysisVersion = VERSION,
        model = "gpt-test",
        content = SupportProgramAnalysisContent("요약", emptyList(), null, null, emptyList(), null),
        discardedItemCount = 0,
    )
    private lateinit var service: SupportProgramAnalysisService

    @BeforeEach
    fun setUp() {
        service = SupportProgramAnalysisService(repository, programs, evidenceService, attachments, client, properties, clock)
    }

    @Test
    fun skipsTheRunWithoutClaimingWhenTheSeoulDailyLimitIsReached() {
        doReturn(5L).`when`(repository).countAttemptedSince(LocalDate.of(2026, 10, 1).atStartOfDay())

        assertFalse(service.runNext())

        verify(repository, never()).claimNext(LocalDate.of(2026, 10, 1), now, 3, now.plusSeconds(300), VERSION)
        verifyNoInteractions(client)
    }

    @Test
    fun returnsFalseWhenNoProgramNeedsAnalysis() {
        assertFalse(service.runNext())
        verify(repository).claimNext(LocalDate.of(2026, 10, 1), now, 3, now.plusSeconds(300), VERSION)
        verifyNoInteractions(client, evidenceService)
    }

    @Test
    fun analyzesBizInfoWithTheOfficialDetailTextAndSavesUnderTheSameLease() {
        claim()
        val document = document("상세 원문 내용")
        doReturn(document).`when`(evidenceService).sourceDocument(catalogProgram.program)
        val request = AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "상세 원문 내용")
        doReturn(output).`when`(client).analyze(request)
        doReturn(true).`when`(repository).complete(lease, output, now)

        assertTrue(service.runNext())

        verify(repository).complete(lease, output, now)
        verify(repository, never()).fail(lease, "AI_TIMEOUT", null, now)
    }

    @Test
    fun otherSourcesAreAnalyzedWithoutDetailText() {
        val other = catalogProgram.copy(program = catalogProgram.program.copy(sourceCode = "KSTARTUP"))
        val otherLease = lease.copy(sourceCode = "KSTARTUP")
        doReturn(0L).`when`(repository).countAttemptedSince(now.toLocalDate().atStartOfDay())
        doReturn(otherLease).`when`(repository).claimNext(now.toLocalDate(), now, 3, now.plusSeconds(300), VERSION)
        doReturn(other).`when`(programs).findPresentBySourceAndProgramId("KSTARTUP", "PBLN_1")
        doReturn(SupportProgramAttachmentTexts.NONE).`when`(attachments).load(other.program)
        doReturn(output).`when`(client).analyze(AiSupportProgramAnalysisMapper.toRequest(other.program, null))
        doReturn(true).`when`(repository).complete(otherLease, output, now)

        assertTrue(service.runNext())

        verifyNoInteractions(evidenceService)
    }

    @Test
    fun recordsAiFailureWithExponentialBackoffFromTheClaimedAttempt() {
        val secondAttempt = lease.copy(attemptCount = 2)
        claim(secondAttempt)
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        doThrow(AiServiceCallException.timeout(null)).`when`(client)
            .analyze(AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "원문"))

        assertTrue(service.runNext())

        verify(repository).fail(secondAttempt, "AI_TIMEOUT", now.plusHours(2), now)
    }

    @Test
    fun rejectedRequestsAreNotRetriedUntilTheProgramChanges() {
        claim()
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        doThrow(AiSupportProgramAnalysisRejectedException()).`when`(client)
            .analyze(AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "원문"))

        assertTrue(service.runNext())

        verify(repository).fail(lease, "AI_REQUEST_REJECTED", null, now)
    }

    @Test
    fun sourceDocumentFailureIsRecordedWithoutCallingAi() {
        claim()
        doThrow(SupportProgramEvidenceUnavailableException(IllegalStateException("down")))
            .`when`(evidenceService).sourceDocument(catalogProgram.program)

        assertTrue(service.runNext())

        verify(repository).fail(lease, "SOURCE_UNAVAILABLE", now.plusHours(1), now)
        verifyNoInteractions(client)
    }

    @Test
    fun aLostLeaseDoesNotOverwriteOrFail() {
        claim()
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        doReturn(output).`when`(client).analyze(AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "원문"))
        doReturn(false).`when`(repository).complete(lease, output, now)

        assertTrue(service.runNext())

        verify(repository).complete(lease, output, now)
        verify(repository, never()).fail(lease, "LEASE_LOST", null, now)
    }

    @Test
    fun unexpectedErrorsPropagateSoTheLeaseExpires() {
        claim()
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        doThrow(IllegalStateException("bug")).`when`(client)
            .analyze(AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "원문"))

        assertThrows(IllegalStateException::class.java) { service.runNext() }
    }

    @Test
    fun sendsReadableAttachmentsAndStoresTheirNamesThroughTheRequest() {
        claim()
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        val files = listOf(SupportProgramAttachmentText("신청서.hwp", "신청서 본문"), SupportProgramAttachmentText("모집공고.pdf", "공고 본문"))
        doReturn(SupportProgramAttachmentTexts(files, skippedCount = 1)).`when`(attachments).load(catalogProgram.program)
        val request = AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "원문", files)
        doReturn(output).`when`(client).analyze(request)
        doReturn(true).`when`(repository).complete(lease, output, now)

        assertTrue(service.runNext())

        assertEquals(listOf("모집공고.pdf", "신청서.hwp"), request.attachments.map { it.name })
        verify(client).analyze(request)
    }

    @Test
    fun attachmentProviderFailureIsARetryableSourceFailure() {
        claim()
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        doThrow(SupportProgramDocumentException(SupportProgramDocumentException.Reason.UNAVAILABLE))
            .`when`(attachments).load(catalogProgram.program)

        assertTrue(service.runNext())

        verify(repository).fail(lease, "SOURCE_UNAVAILABLE", now.plusHours(1), now)
        verifyNoInteractions(client)
    }

    @Test
    fun versionMismatchIsRecordedWithoutRetryAndKeepsAPreviousVersionResult() {
        val reanalysis = lease.copy(keepsPreviousResult = true)
        claim(reanalysis)
        doReturn(document("원문")).`when`(evidenceService).sourceDocument(catalogProgram.program)
        doThrow(AiSupportProgramAnalysisVersionMismatchException()).`when`(client)
            .analyze(AiSupportProgramAnalysisMapper.toRequest(catalogProgram.program, "원문"))
        doReturn(true).`when`(repository).fail(reanalysis, "AI_VERSION_MISMATCH", null, now)

        assertTrue(service.runNext())

        verify(repository).fail(reanalysis, "AI_VERSION_MISMATCH", null, now)
    }

    @Test
    fun backoffDoublesFromOneHourAndIsCappedAtOneDay() {
        assertEquals(Duration.ofHours(1), SupportProgramAnalysisService.backoff(1))
        assertEquals(Duration.ofHours(2), SupportProgramAnalysisService.backoff(2))
        assertEquals(Duration.ofHours(4), SupportProgramAnalysisService.backoff(3))
        assertEquals(Duration.ofHours(24), SupportProgramAnalysisService.backoff(6))
        assertEquals(Duration.ofHours(24), SupportProgramAnalysisService.backoff(30))
    }

    private fun claim(claimed: SupportProgramAnalysisLease = lease) {
        doReturn(0L).`when`(repository).countAttemptedSince(now.toLocalDate().atStartOfDay())
        doReturn(claimed).`when`(repository).claimNext(now.toLocalDate(), now, 3, now.plusSeconds(300), VERSION)
        doReturn(catalogProgram).`when`(programs).findPresentBySourceAndProgramId("BIZINFO", "PBLN_1")
        // 원문 준비에서 먼저 실패하는 경우에는 첨부를 읽지 않습니다.
        lenient().doReturn(SupportProgramAttachmentTexts.NONE).`when`(attachments).load(catalogProgram.program)
    }

    private fun document(content: String) = SupportProgramSourceDocument(
        sourceCode = "BIZINFO",
        sourceProgramId = "PBLN_1",
        sourceUrl = catalogProgram.program.sourceUrl,
        content = content,
        contentHash = SupportProgramContentHashHelper.sha256(content),
        fetchedAt = now,
    )

    private companion object {
        const val VERSION = AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION
    }
}
