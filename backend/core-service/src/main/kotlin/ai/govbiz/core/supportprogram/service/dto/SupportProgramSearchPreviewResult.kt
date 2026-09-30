package ai.govbiz.core.supportprogram.service.dto

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSummary
import ai.govbiz.core.supportprogram.domain.SupportProgramConversationContext
import java.time.Instant

/**
 * 응답에 싣는 검색 결과입니다. analysisSummaries는 programs 중 현재 완료 분석이 있는 공고의 요약이며
 * 키는 [SupportProgram.sourceQualifiedId]입니다. 복원용 Redis 스냅샷에는 저장하지 않습니다.
 */
data class SupportProgramSearchPreviewResult(
    val query: String,
    val programs: List<SupportProgram>,
    val totalCount: Int,
    val resultToken: String? = null,
    val expiresAt: Instant? = null,
    val analysisSummaries: Map<String, SupportProgramAnalysisSummary> = emptyMap(),
)

data class SupportProgramSearchRestoredResult(
    val result: SupportProgramSearchPreviewResult,
    val context: SupportProgramConversationContext,
)
