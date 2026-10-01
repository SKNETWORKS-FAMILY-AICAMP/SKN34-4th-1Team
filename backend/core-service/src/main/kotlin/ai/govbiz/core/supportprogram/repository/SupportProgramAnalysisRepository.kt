package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisLease
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisOutput
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSummary
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramAnalysisDbRow
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramAnalysisMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * 공고별 AI 분석 결과와 분석 실행권을 저장합니다. 외부 호출은 이 Repository 밖에서 수행하고,
 * 실행권 선점만 짧은 transaction으로 처리합니다. 결과 저장은 같은 실행권일 때만 반영됩니다.
 */
@Repository
class SupportProgramAnalysisRepository(
    private val mapper: SupportProgramAnalysisMapper,
    private val objectMapper: ObjectMapper,
) {
    /** 일일 호출 한도 계산용으로 since 이후 분석을 시작한 공고 수를 셉니다. */
    fun countAttemptedSince(since: LocalDateTime): Long = mapper.countAttemptedSince(since)

    /**
     * 마감 임박 순으로 분석이 필요한 공고 하나의 실행권을 얻습니다.
     *
     * 후보는 잠금 없이 고르고, 행을 만든 뒤 같은 후보 조건을 잠금 상태에서 다시 확인합니다.
     * 공고 내용이 바뀌었으면 이전 결과와 시도 횟수를 비우고 새 지문으로 시작합니다. 공고 내용은 같고 완료 결과의
     * 버전만 expectedVersion과 다르면 이전 결과를 남긴 채 다시 분석합니다([SupportProgramAnalysisLease.keepsPreviousResult]).
     */
    @Transactional
    fun claimNext(
        today: LocalDate,
        now: LocalDateTime,
        maxAttempts: Int,
        leaseUntil: LocalDateTime,
        expectedVersion: String,
        /** 이 제공처의 공고만 고릅니다. `null`이면 제한하지 않습니다. */
        sourceCodes: Set<String>? = null,
    ): SupportProgramAnalysisLease? {
        val candidate = mapper.findNextCandidate(today, now, maxAttempts, expectedVersion, sourceCodes) ?: return null
        val fingerprint = requireNotNull(candidate.currentProgramFingerprint) { "candidate fingerprint is missing" }
        mapper.insertPendingIfAbsent(SupportProgramAnalysisDbRow(
            sourceCode = candidate.sourceCode,
            sourceProgramId = candidate.sourceProgramId,
            programFingerprint = fingerprint,
            updatedAt = now,
        ))
        val row = mapper.lockClaimable(candidate.sourceCode, candidate.sourceProgramId, now, maxAttempts, expectedVersion)
            ?: return null
        val current = requireNotNull(row.currentProgramFingerprint) { "current fingerprint is missing" }
        if (row.programFingerprint != current) {
            row.programFingerprint = current
            row.status = PENDING
            row.analysisVersion = null
            row.model = null
            row.analysisJson = null
            row.failureCode = null
            row.analyzedAt = null
            row.attemptCount = 0
        }
        row.attemptCount += 1
        // 이전 버전 완료 결과의 재분석은 실행 중 프로세스가 사라져도 실행권 만료 뒤 다시 고를 수 있게 재시도 시각을 둡니다.
        row.nextAttemptAt = if (row.status == COMPLETED) leaseUntil else null
        row.lastAttemptAt = now
        row.leaseToken = UUID.randomUUID().toString()
        row.leaseUntil = leaseUntil
        row.updatedAt = now
        check(mapper.updateClaim(row) == 1) { "support program analysis claim was not updated" }
        return SupportProgramAnalysisLease(
            sourceCode = row.sourceCode,
            sourceProgramId = row.sourceProgramId,
            programFingerprint = row.programFingerprint,
            leaseToken = requireNotNull(row.leaseToken),
            attemptCount = row.attemptCount,
            keepsPreviousResult = row.status == COMPLETED,
        )
    }

    /** 실행권이 그대로일 때만 완료 결과를 저장합니다. 다른 Worker가 다시 선점했으면 false입니다. */
    fun complete(lease: SupportProgramAnalysisLease, output: SupportProgramAnalysisOutput, now: LocalDateTime): Boolean =
        mapper.completeIfLeased(
            sourceCode = lease.sourceCode,
            sourceProgramId = lease.sourceProgramId,
            leaseToken = lease.leaseToken,
            analysisVersion = output.analysisVersion,
            model = output.model,
            analysisJson = objectMapper.writeValueAsString(output.content),
            now = now,
        ) == 1

    /**
     * 실행권이 그대로일 때만 실패 코드와 다음 재시도 시각을 저장합니다. nextAttemptAt이 null이면 공고가 바뀔 때까지
     * 재시도하지 않습니다. 이전 버전 완료 결과를 다시 분석하던 실행권이면 그 결과를 지우지 않고 재시도 시각만 기록하며,
     * 실패 코드는 저장하지 않습니다(호출자가 로그로 남깁니다).
     */
    fun fail(lease: SupportProgramAnalysisLease, failureCode: String, nextAttemptAt: LocalDateTime?, now: LocalDateTime): Boolean =
        if (lease.keepsPreviousResult) {
            mapper.deferReanalysisIfLeased(
                sourceCode = lease.sourceCode,
                sourceProgramId = lease.sourceProgramId,
                leaseToken = lease.leaseToken,
                nextAttemptAt = nextAttemptAt,
                now = now,
            ) == 1
        } else {
            mapper.failIfLeased(
                sourceCode = lease.sourceCode,
                sourceProgramId = lease.sourceProgramId,
                leaseToken = lease.leaseToken,
                failureCode = failureCode,
                nextAttemptAt = nextAttemptAt,
                now = now,
            ) == 1
        }

    /** 현재 공고 내용과 지문이 같은 결과만 COMPLETED·FAILED로 돌려주고, 그 밖에는 NOT_ANALYZED입니다. */
    fun findCurrent(sourceCode: String, sourceProgramId: String): SupportProgramAnalysis {
        val row = mapper.findWithCurrentFingerprint(sourceCode, sourceProgramId)
            ?: return SupportProgramAnalysis.NOT_ANALYZED
        if (row.programFingerprint != row.currentProgramFingerprint) return SupportProgramAnalysis.NOT_ANALYZED
        return when (row.status) {
            COMPLETED -> SupportProgramAnalysis(
                status = SupportProgramAnalysisStatus.COMPLETED,
                analyzedAt = requireNotNull(row.analyzedAt) { "completed analysis must have analyzedAt" },
                content = readContent(row),
            )
            FAILED -> SupportProgramAnalysis.FAILED
            else -> SupportProgramAnalysis.NOT_ANALYZED
        }
    }

    /**
     * 목록·검색 카드용으로 여러 공고의 현재 완료 분석 요약을 한 번의 조회로 읽습니다. [findCurrent]와 같은 지문 기준이며
     * 현재 완료 분석이 없는 공고는 결과에 없습니다. 키는 [SupportProgram.sourceQualifiedId]입니다.
     * 호출자는 한 응답에 싣는 공고(최대 한 페이지)만 넘깁니다.
     */
    fun findCurrentSummaries(programs: List<SupportProgram>): Map<String, SupportProgramAnalysisSummary> {
        if (programs.isEmpty()) return emptyMap()
        val keys = programs
            .map { SupportProgramAnalysisDbRow(sourceCode = it.sourceCode, sourceProgramId = it.id) }
            .distinct()
        val summaries = mapper.findCurrentCompleted(keys).associate { row ->
            (row.sourceCode to row.sourceProgramId) to SupportProgramAnalysisSummary.from(readContent(row))
        }
        return programs.mapNotNull { program ->
            summaries[program.sourceCode to program.id]?.let { program.sourceQualifiedId to it }
        }.toMap()
    }

    private fun readContent(row: SupportProgramAnalysisDbRow): SupportProgramAnalysisContent =
        objectMapper.readValue(
            requireNotNull(row.analysisJson) { "completed analysis must have analysis_json" },
            SupportProgramAnalysisContent::class.java,
        )

    private companion object {
        const val PENDING = "PENDING"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
    }
}
