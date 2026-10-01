package ai.govbiz.core.supportprogram.service.analysis

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.AiSupportProgramAnalysisClient
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisRejectedException
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisVersionMismatchException
import ai.govbiz.core.supportprogram.client.ai.mapper.AiSupportProgramAnalysisMapper
import ai.govbiz.core.supportprogram.client.document.SupportProgramDocumentException
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisLease
import ai.govbiz.core.supportprogram.facade.SupportProgramAttachmentTextFacade
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.analysis.config.SupportProgramAnalysisProperties
import ai.govbiz.core.supportprogram.service.evidence.SupportProgramEvidenceService
import ai.govbiz.core.supportprogram.service.evidence.exception.SupportProgramEvidenceUnavailableException
import ai.govbiz.core.supportprogram.service.projection.CatalogProjectionProgress
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * 모집 중·예정 공고를 하나씩 AI로 분석해 저장합니다.
 *
 * 흐름: 일일 한도 확인 → 시작 후 Catalog 투영을 마친 제공처 확인 → 실행권 선점(짧은 DB transaction) → 공고·기업마당 원문·공식 첨부 본문 준비 → AI Service 호출
 * (모두 DB transaction 밖) → 같은 실행권일 때만 결과 저장. 예상하지 못한 오류는 감추지 않고 전파하며,
 * 이 경우 실행권 만료 뒤 최대 시도 횟수 안에서 다시 선택됩니다.
 */
@Service
class SupportProgramAnalysisService(
    private val repository: SupportProgramAnalysisRepository,
    private val programs: SupportProgramRepository,
    private val evidenceService: SupportProgramEvidenceService,
    private val attachments: SupportProgramAttachmentTextFacade,
    private val client: AiSupportProgramAnalysisClient,
    private val properties: SupportProgramAnalysisProperties,
    private val projection: CatalogProjectionProgress,
    @param:Qualifier("seoulClock") private val clock: Clock,
) {
    /** 한 공고를 처리했으면 true, 한도 초과나 후보 없음으로 건너뛰었으면 false입니다. */
    fun runNext(): Boolean {
        val now = LocalDateTime.now(clock)
        val attemptedToday = repository.countAttemptedSince(now.toLocalDate().atStartOfDay())
        if (attemptedToday >= properties.dailyLimit) {
            logger.info("support_program_analysis outcome=DAILY_LIMIT_REACHED attempted_today={}", attemptedToday)
            return false
        }
        val sourceCodes = projection.readySources()
        if (sourceCodes != null && sourceCodes.isEmpty()) {
            logger.info("support_program_analysis outcome=WAITING_FOR_CATALOG_PROJECTION")
            return false
        }
        val lease = repository.claimNext(
            today = now.toLocalDate(),
            now = now,
            maxAttempts = properties.maxAttempts,
            leaseUntil = now.plusSeconds(properties.leaseSeconds),
            expectedVersion = AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION,
            sourceCodes = sourceCodes,
        ) ?: return false

        val program = programs.findPresentBySourceAndProgramId(lease.sourceCode, lease.sourceProgramId)?.program
        if (program == null) {
            // 선점과 조회 사이에 비노출된 공고는 후보에서 빠지므로 재시도 없이 실패로만 기록합니다.
            fail(lease, "PROGRAM_NOT_PRESENT", retry = false)
            return true
        }
        val output = try {
            val detailText = if (program.sourceCode == SupportProgramEvidenceService.BIZINFO_SOURCE_CODE) {
                evidenceService.sourceDocument(program).content
            } else {
                null
            }
            val files = attachments.load(program)
            val request = AiSupportProgramAnalysisMapper.toRequest(program, detailText, files.files)
            logger.info(
                "support_program_analysis_input attachments_sent={} attachments_skipped={} attachments_dropped={}",
                request.attachments.size, files.skippedCount, files.files.size - request.attachments.size,
            )
            client.analyze(request)
        } catch (_: SupportProgramEvidenceUnavailableException) {
            fail(lease, "SOURCE_UNAVAILABLE")
            return true
        } catch (_: SupportProgramDocumentException) {
            // 첨부 목록·파일을 제공처에서 받지 못한 경우(연결 실패·시간 초과)만 여기로 옵니다.
            fail(lease, "SOURCE_UNAVAILABLE")
            return true
        } catch (_: AiSupportProgramAnalysisRejectedException) {
            // 같은 입력은 다시 보내도 거부되므로 공고 내용이 바뀔 때까지 재시도하지 않습니다.
            fail(lease, "AI_REQUEST_REJECTED", retry = false)
            return true
        } catch (_: AiSupportProgramAnalysisVersionMismatchException) {
            // 두 서비스의 배포가 어긋난 상태입니다. 같은 공고를 반복 호출하지 않도록 재시도하지 않습니다.
            fail(lease, "AI_VERSION_MISMATCH", retry = false)
            return true
        } catch (exception: AiServiceCallException) {
            fail(lease, "AI_${exception.failure.name}")
            return true
        }

        val saved = repository.complete(lease, output, LocalDateTime.now(clock))
        logger.info(
            "support_program_analysis outcome={} attempt={} conditions={} discarded={}",
            if (saved) "COMPLETED" else "LEASE_LOST", lease.attemptCount,
            output.content.conditions.size, output.discardedItemCount,
        )
        return true
    }

    private fun fail(lease: SupportProgramAnalysisLease, failureCode: String, retry: Boolean = true) {
        val now = LocalDateTime.now(clock)
        val saved = repository.fail(lease, failureCode, if (retry) now.plus(backoff(lease.attemptCount)) else null, now)
        // 이전 버전 완료 결과의 재분석 실패는 결과를 지우지 않으므로 FAILED 대신 REANALYSIS_DEFERRED로 기록합니다.
        val outcome = when {
            !saved -> "LEASE_LOST"
            lease.keepsPreviousResult -> "REANALYSIS_DEFERRED"
            else -> "FAILED"
        }
        logger.info("support_program_analysis outcome={} failure_code={} attempt={}", outcome, failureCode, lease.attemptCount)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SupportProgramAnalysisService::class.java)
        private val MAX_BACKOFF: Duration = Duration.ofHours(24)

        /** 1시간에서 시작해 시도마다 두 배로 늘리고 24시간을 넘기지 않습니다. */
        internal fun backoff(attemptCount: Int): Duration {
            val exponent = (attemptCount - 1).coerceIn(0, 5)
            return Duration.ofHours(1L shl exponent).coerceAtMost(MAX_BACKOFF)
        }
    }
}
