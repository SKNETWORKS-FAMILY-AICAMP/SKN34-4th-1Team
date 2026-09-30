package ai.govbiz.core.supportprogram.service.search

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSummary
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import ai.govbiz.core.supportprogram.domain.SupportProgramCompanyConditions
import ai.govbiz.core.supportprogram.domain.SupportProgramConversationContext
import ai.govbiz.core.supportprogram.domain.SupportProgramSearchSnapshot
import ai.govbiz.core.supportprogram.domain.SupportProgramStatus
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramSearchResultRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramSearchResult
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

/**
 * 검색·복원 응답의 카드용 분석 요약 부착을 Redis 없이 확인합니다. 저장·복원 자체의 동작은
 * [SupportProgramSearchPreviewServiceTest]가 실제 Redis로 검증합니다.
 */
class SupportProgramSearchPreviewAnalysisSummaryTest {
    private val search = Mockito.mock(SupportProgramSearchService::class.java)
    private val results = Mockito.mock(SupportProgramSearchResultRepository::class.java)
    private val analyses = Mockito.mock(SupportProgramAnalysisRepository::class.java)
    private val service = SupportProgramSearchPreviewService(search, results, analyses)
    private val programs = (1..5).map(::program)
    private val summary = SupportProgramAnalysisSummary("최대 5천만원 사업화 지원", "최대 5천만원", 50_000_000, listOf(SupportProgramAnalysisSupportType.GRANT))

    @Test
    fun guestPreviewReadsSummariesOnlyForTheVisibleProgramsAndNeverSavesThem() {
        Mockito.`when`(search.search("무역", false, null)).thenReturn(SupportProgramSearchResult("무역", programs))
        Mockito.`when`(results.save(Mockito.anyString(), Mockito.any(SupportProgramSearchSnapshot::class.java) ?: FALLBACK_SNAPSHOT))
            .thenReturn(Instant.parse("2026-10-01T01:30:00Z"))
        Mockito.`when`(analyses.findCurrentSummaries(programs.take(2))).thenReturn(mapOf("BIZINFO:program-1" to summary))

        val result = service.search("무역", false, null, null)

        assertEquals(programs.take(2), result.programs)
        assertEquals(mapOf("BIZINFO:program-1" to summary), result.analysisSummaries)
        Mockito.verify(analyses, Mockito.times(1)).findCurrentSummaries(programs.take(2))
        Mockito.verifyNoMoreInteractions(analyses)
        val saved = ArgumentCaptor.forClass(SupportProgramSearchSnapshot::class.java)
        Mockito.verify(results).save(Mockito.anyString(), saved.capture() ?: FALLBACK_SNAPSHOT)
        assertEquals(programs, saved.value.programs)
    }

    @Test
    fun memberSearchReadsSummariesForEveryReturnedProgramInOneCall() {
        Mockito.`when`(search.search("무역", false, null)).thenReturn(SupportProgramSearchResult("무역", programs + program(6)))
        Mockito.`when`(analyses.findCurrentSummaries(programs)).thenReturn(mapOf("BIZINFO:program-5" to summary))

        val result = service.search("무역", false, null, 1L)

        assertEquals(programs, result.programs)
        assertEquals(mapOf("BIZINFO:program-5" to summary), result.analysisSummaries)
        Mockito.verify(analyses, Mockito.times(1)).findCurrentSummaries(programs)
        Mockito.verifyNoMoreInteractions(analyses)
        Mockito.verifyNoInteractions(results)
    }

    @Test
    fun restoreReadsTheCurrentSummariesAgainForTheRestoredPrograms() {
        val context = SupportProgramConversationContext("무역", false, SupportProgramCompanyConditions())
        Mockito.`when`(results.claim(TOKEN, 1L)).thenReturn(SupportProgramSearchSnapshot("무역", programs, context))
        Mockito.`when`(analyses.findCurrentSummaries(programs)).thenReturn(mapOf("BIZINFO:program-3" to summary))

        val restored = service.restore(TOKEN, 1L)

        assertEquals(programs, restored.result.programs)
        assertEquals(mapOf("BIZINFO:program-3" to summary), restored.result.analysisSummaries)
        Mockito.verify(analyses, Mockito.times(1)).findCurrentSummaries(programs)
        Mockito.verifyNoInteractions(search)
    }

    private fun program(index: Int) = SupportProgram(
        id = "program-$index", sourceCode = "BIZINFO", title = "공고 $index", organization = "기관",
        summary = "무역 지원 $index", categories = listOf("수출"), regions = listOf("대구"), targetDescription = "중소기업",
        applicationPeriod = "상시 접수", applicationStartDate = null, applicationEndDate = null,
        status = SupportProgramStatus.OPEN, sourceName = "기업마당", sourceUrl = "https://example.invalid/$index",
        matchedReasons = listOf("추천 $index"), recommendationScore = 100 - index,
    )

    private companion object {
        const val TOKEN = "00000000-0000-4000-8000-000000000000"
        val FALLBACK_SNAPSHOT = SupportProgramSearchSnapshot(
            "", emptyList(), SupportProgramConversationContext(null, false, SupportProgramCompanyConditions()),
        )
    }
}
