package ai.govbiz.core.supportprogram.repository.mapper

import java.time.LocalDateTime

/**
 * MyBatis가 support_program_analysis 한 행을 읽고 쓰기 위한 값입니다.
 * currentProgramFingerprint는 조회 시점 공고 내용으로 계산한 읽기 전용 값이며 저장하지 않습니다.
 */
data class SupportProgramAnalysisDbRow(
    var sourceCode: String = "",
    var sourceProgramId: String = "",
    var programFingerprint: String = "",
    var currentProgramFingerprint: String? = null,
    var status: String = "PENDING",
    var analysisVersion: String? = null,
    var model: String? = null,
    var analysisJson: String? = null,
    var failureCode: String? = null,
    var attemptCount: Int = 0,
    var nextAttemptAt: LocalDateTime? = null,
    var lastAttemptAt: LocalDateTime? = null,
    var leaseToken: String? = null,
    var leaseUntil: LocalDateTime? = null,
    var analyzedAt: LocalDateTime? = null,
    var updatedAt: LocalDateTime? = null,
)
