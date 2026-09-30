package ai.govbiz.core.supportprogram.repository.mapper

import java.time.LocalDate
import java.time.LocalDateTime
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 공고 분석 결과와 분석 실행권 SQL을 실행하는 MyBatis Mapper입니다. */
@Mapper
interface SupportProgramAnalysisMapper {
    fun countAttemptedSince(@Param("since") since: LocalDateTime): Long

    /** 잠금 없이 다음 분석 후보 공고 하나의 식별자와 현재 지문을 읽습니다. */
    fun findNextCandidate(
        @Param("today") today: LocalDate,
        @Param("now") now: LocalDateTime,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("expectedVersion") expectedVersion: String,
    ): SupportProgramAnalysisDbRow?

    /** 새 공고의 PENDING 행을 만들며 이미 있으면 바꾸지 않습니다. */
    fun insertPendingIfAbsent(row: SupportProgramAnalysisDbRow): Int

    /** 후보 조건을 잠금 상태에서 다시 확인합니다. 다른 Worker가 먼저 선점했으면 null입니다. */
    fun lockClaimable(
        @Param("sourceCode") sourceCode: String,
        @Param("sourceProgramId") sourceProgramId: String,
        @Param("now") now: LocalDateTime,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("expectedVersion") expectedVersion: String,
    ): SupportProgramAnalysisDbRow?

    /** lockClaimable로 잠근 행의 실행권 선점 값을 기록합니다. */
    fun updateClaim(row: SupportProgramAnalysisDbRow): Int

    /** 같은 실행권일 때만 완료 결과를 기록하고 실행권을 반납합니다. */
    fun completeIfLeased(
        @Param("sourceCode") sourceCode: String,
        @Param("sourceProgramId") sourceProgramId: String,
        @Param("leaseToken") leaseToken: String,
        @Param("analysisVersion") analysisVersion: String,
        @Param("model") model: String,
        @Param("analysisJson") analysisJson: String,
        @Param("now") now: LocalDateTime,
    ): Int

    /** 같은 실행권의 이전 버전 완료 결과 재분석이 실패하면 결과는 두고 다음 재시도 시각만 기록합니다. */
    fun deferReanalysisIfLeased(
        @Param("sourceCode") sourceCode: String,
        @Param("sourceProgramId") sourceProgramId: String,
        @Param("leaseToken") leaseToken: String,
        @Param("nextAttemptAt") nextAttemptAt: LocalDateTime?,
        @Param("now") now: LocalDateTime,
    ): Int

    /** 같은 실행권일 때만 실패와 다음 재시도 시각을 기록하고 실행권을 반납합니다. 재시도하지 않으면 nextAttemptAt은 null입니다. */
    fun failIfLeased(
        @Param("sourceCode") sourceCode: String,
        @Param("sourceProgramId") sourceProgramId: String,
        @Param("leaseToken") leaseToken: String,
        @Param("failureCode") failureCode: String,
        @Param("nextAttemptAt") nextAttemptAt: LocalDateTime?,
        @Param("now") now: LocalDateTime,
    ): Int

    /** 현재 노출 중인 공고의 분석 행과 현재 공고 지문을 함께 읽습니다. */
    fun findWithCurrentFingerprint(
        @Param("sourceCode") sourceCode: String,
        @Param("sourceProgramId") sourceProgramId: String,
    ): SupportProgramAnalysisDbRow?

    /**
     * keys의 복합 식별자 중 현재 노출 중이고 저장 지문이 현재 공고 지문과 같은 COMPLETED 분석 행만 읽습니다.
     * keys에는 source_code·source_program_id만 채우며 비어 있으면 안 됩니다.
     */
    fun findCurrentCompleted(@Param("keys") keys: List<SupportProgramAnalysisDbRow>): List<SupportProgramAnalysisDbRow>
}
