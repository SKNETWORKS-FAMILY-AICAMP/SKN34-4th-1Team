package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core._common.test.MySqlTestContainerConfig
import ai.govbiz.core.supportprogram.client.ai.mapper.AiSupportProgramAnalysisMapper
import ai.govbiz.core.supportprogram.domain.CatalogSupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisAmount
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisCondition
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionValues
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisDocumentRequirement
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidence
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisOutput
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisRequiredDocument
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisScheduleItem
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSummary
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisText
import ai.govbiz.core.supportprogram.domain.SupportProgramApplicationRoute
import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    properties = [
        "app.account.jwt-secret=test-jwt-secret-0123456789abcdef0123456789",
        "app.ai-service.base-url=http://127.0.0.1:1",
        "app.ai-service.connect-timeout=10ms",
        "app.ai-service.read-timeout=10ms",
        "app.bizinfo.sync.enabled=false",
        "app.kstartup.sync.enabled=false",
        "app.msit.sync.enabled=false",
        "app.cntrade-notice.sync.enabled=false",
        "app.support-program-index.enabled=false",
    ],
)
@Import(MySqlTestContainerConfig::class)
class SupportProgramAnalysisRepositoryIntegrationTest {
    @Autowired private lateinit var repository: SupportProgramAnalysisRepository
    @Autowired private lateinit var programs: SupportProgramRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val today = LocalDate.of(2026, 10, 1)
    private val now = LocalDateTime.of(2026, 10, 1, 9, 0)

    @BeforeEach
    fun isolateCandidates() {
        cleanUp()
        // 후보 선택은 전체 제공처를 보므로 다른 테스트가 남긴 공고를 이 테스트 동안 비노출로 둡니다.
        jdbc.update("UPDATE support_program SET is_source_present = FALSE WHERE is_source_present = TRUE")
    }

    @AfterEach
    fun cleanUp() {
        // 다른 테스트의 support_program 삭제가 FK에 막히지 않도록 분석 행을 먼저 지웁니다.
        jdbc.update("DELETE FROM support_program_analysis")
        jdbc.update("DELETE FROM support_program WHERE source_code = ?", SOURCE)
    }

    @Test
    fun claimsSoonestOpenDeadlineFirstAndSkipsClosedHiddenAndLeasedPrograms() {
        insert("later", today.plusDays(30))
        insert("sooner", today)
        insert("always", null)
        insert("closed", today.minusDays(1))
        insert("hidden", today.plusDays(1))
        jdbc.update("UPDATE support_program SET is_source_present = FALSE WHERE source_code = ? AND source_program_id = 'hidden'", SOURCE)

        val first = claim()!!
        assertEquals("sooner", first.sourceProgramId)
        assertEquals(1, first.attemptCount)
        assertEquals(1, repository.countAttemptedSince(today.atStartOfDay()))
        assertEquals(SupportProgramAnalysisStatus.NOT_ANALYZED, repository.findCurrent(SOURCE, "sooner").status)

        assertEquals("later", claim()!!.sourceProgramId)
        assertEquals("always", claim()!!.sourceProgramId)
        assertNull(claim())
        assertEquals(3, repository.countAttemptedSince(today.atStartOfDay()))
        assertEquals(0, repository.countAttemptedSince(today.plusDays(1).atStartOfDay()))
    }

    @Test
    fun claimsOnlyProgramsFromTheGivenSourcesWhenSourcesAreLimited() {
        insert("p1", today.plusDays(3))

        assertNull(repository.claimNext(today, now, MAX_ATTEMPTS, now.plusSeconds(300), VERSION, setOf("OTHER_SOURCE")))
        assertEquals(0, repository.countAttemptedSince(today.atStartOfDay()))
        assertEquals("p1", repository.claimNext(today, now, MAX_ATTEMPTS, now.plusSeconds(300), VERSION, setOf(SOURCE))!!.sourceProgramId)
    }

    @Test
    fun completedAnalysisRoundTripsKoreanSpecialCharactersAndIsNotReanalyzed() {
        insert("p1", today.plusDays(3))
        val lease = claim()!!

        assertTrue(repository.complete(lease, OUTPUT, now.plusMinutes(1)))

        val analysis = repository.findCurrent(SOURCE, "p1")
        assertEquals(SupportProgramAnalysisStatus.COMPLETED, analysis.status)
        assertEquals(now.plusMinutes(1), analysis.analyzedAt)
        assertEquals(OUTPUT.content, analysis.content)
        assertEquals("gpt-test", jdbc.queryForObject("SELECT model FROM support_program_analysis", String::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM support_program_analysis", Int::class.java))
        assertNull(claim(now.plusDays(1)))
    }

    @Test
    fun changedProgramContentHidesTheOldResultAndIsSelectedAgainWithAttemptsReset() {
        val original = insert("p1", today.plusDays(3))
        val first = claim()!!
        assertTrue(repository.complete(first, OUTPUT, now))

        programs.upsert(original.copy(program = original.program.copy(summary = "변경된 요약")))

        assertEquals(SupportProgramAnalysis.NOT_ANALYZED, repository.findCurrent(SOURCE, "p1"))
        val second = claim(now.plusMinutes(1))!!
        assertNotEquals(first.programFingerprint, second.programFingerprint)
        assertEquals(1, second.attemptCount)
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM support_program_analysis", String::class.java))
        assertNull(jdbc.queryForObject("SELECT analysis_json FROM support_program_analysis", String::class.java))

        // 신청 방법만 바뀌어도 분석 입력이므로 지문이 달라집니다.
        assertTrue(repository.complete(second, OUTPUT, now.plusMinutes(2)))
        programs.upsert(original.copy(program = original.program.copy(
            summary = "변경된 요약", applicationRoute = SupportProgramApplicationRoute(method = "온라인 접수"))))
        assertNotNull(claim(now.plusMinutes(3)))
    }

    @Test
    fun failedAnalysisIsRetriedAfterBackoffUntilTheAttemptLimit() {
        insert("p1", today.plusDays(3))
        val first = claim()!!
        assertTrue(repository.fail(first, "AI_TIMEOUT", now.plusHours(1), now))
        assertEquals(SupportProgramAnalysis.FAILED, repository.findCurrent(SOURCE, "p1"))
        assertNull(claim(now.plusMinutes(59)))

        val second = claim(now.plusHours(1))!!
        assertEquals(2, second.attemptCount)
        assertTrue(repository.fail(second, "AI_UNAVAILABLE", now.plusHours(3), now.plusHours(1)))
        val third = claim(now.plusHours(3))!!
        assertEquals(3, third.attemptCount)
        assertTrue(repository.fail(third, "AI_UNAVAILABLE", now.plusHours(7), now.plusHours(3)))

        assertNull(claim(now.plusDays(1)))
        assertEquals("AI_UNAVAILABLE", jdbc.queryForObject("SELECT failure_code FROM support_program_analysis", String::class.java))
    }

    @Test
    fun nonRetryableFailureWaitsForAProgramChange() {
        insert("p1", today.plusDays(3))
        assertTrue(repository.fail(claim()!!, "AI_REQUEST_REJECTED", null, now))
        assertNull(claim(now.plusDays(1)))
    }

    @Test
    fun onlyTheCurrentLeaseHolderCanSaveAndExpiredLeasesAreReclaimed() {
        insert("p1", today.plusDays(3))
        val first = claim()!!
        assertFalse(repository.complete(first.copy(leaseToken = "00000000-0000-0000-0000-000000000000"), OUTPUT, now))
        assertNull(claim(now.plusSeconds(299)))

        val second = claim(now.plusSeconds(301))!!
        assertEquals(2, second.attemptCount)
        assertNotEquals(first.leaseToken, second.leaseToken)
        assertFalse(repository.complete(first, OUTPUT, now.plusSeconds(302)))
        assertFalse(repository.fail(first, "AI_TIMEOUT", now.plusHours(1), now.plusSeconds(302)))
        assertTrue(repository.complete(second, OUTPUT, now.plusSeconds(303)))
    }

    @Test
    fun crashedPendingClaimsStopAtTheAttemptLimit() {
        insert("p1", today.plusDays(3))
        claim()!!
        claim(now.plusMinutes(10))!!
        claim(now.plusMinutes(20))!!
        assertNull(claim(now.plusMinutes(30)))
    }

    @Test
    fun summariesReadOnlyCurrentCompletedAnalysesOfTheRequestedCompositeIdentities() {
        val completed = insert("completed", today.plusDays(1))
        val stale = insert("stale", today.plusDays(2))
        val failed = insert("failed", today.plusDays(3))
        val hidden = insert("hidden", today.plusDays(4))
        val pending = insert("pending", today.plusDays(5))
        val leases = (1..5).map { claim()!! }
        assertEquals(listOf("completed", "stale", "failed", "hidden", "pending"), leases.map { it.sourceProgramId })
        assertTrue(repository.complete(leases[0], OUTPUT, now))
        assertTrue(repository.complete(leases[1], OUTPUT, now))
        assertTrue(repository.fail(leases[2], "AI_TIMEOUT", now.plusHours(1), now))
        assertTrue(repository.complete(leases[3], OUTPUT, now))
        programs.upsert(stale.copy(program = stale.program.copy(summary = "변경된 요약")))
        jdbc.update("UPDATE support_program SET is_source_present = FALSE WHERE source_code = ? AND source_program_id = 'hidden'", SOURCE)

        val requested = listOf(completed, stale, failed, hidden, pending, completed).map { it.program } +
            // 같은 원본 ID라도 다른 제공처의 공고에는 분석이 섞이지 않습니다.
            completed.program.copy(sourceCode = "ANALYSISOTHER") +
            completed.program.copy(id = "missing")

        assertEquals(
            mapOf("$SOURCE:completed" to SupportProgramAnalysisSummary("서울 AI 기업에 최대 5천만원 지원", "최대 5천만원", 50_000_000,
                listOf(SupportProgramAnalysisSupportType.GRANT, SupportProgramAnalysisSupportType.RND))),
            repository.findCurrentSummaries(requested),
        )
        assertEquals(emptyMap<String, SupportProgramAnalysisSummary>(), repository.findCurrentSummaries(emptyList()))
    }

    @Test
    fun databaseRejectsInconsistentAnalysisRows() {
        insert("p1", today.plusDays(3))
        assertThrows(DataAccessException::class.java) {
            jdbc.update("""
                INSERT INTO support_program_analysis (source_code, source_program_id, program_fingerprint, status, attempt_count, updated_at)
                VALUES (?, 'p1', REPEAT('a', 64), 'COMPLETED', 0, CURRENT_TIMESTAMP(6))
            """.trimIndent(), SOURCE)
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update("""
                INSERT INTO support_program_analysis (source_code, source_program_id, program_fingerprint, status, attempt_count, updated_at)
                VALUES (?, 'missing', REPEAT('a', 64), 'PENDING', 0, CURRENT_TIMESTAMP(6))
            """.trimIndent(), SOURCE)
        }
    }

    @Test
    fun olderAnalysisVersionIsReanalyzedWhileKeepingItsResultUntilReplaced() {
        insert("p1", today.plusDays(3))
        val old = OUTPUT.copy(analysisVersion = OLD_VERSION, content = OUTPUT.content.copy(
            requiredDocuments = emptyList(), schedule = emptyList(), sourceAttachmentNames = emptyList()))
        assertTrue(repository.complete(claim()!!, old, now))

        val first = claim(now.plusMinutes(1))!!
        assertTrue(first.keepsPreviousResult)
        assertEquals(1, first.attemptCount)
        // 재분석 중에도 이전 버전 결과를 그대로 보여 줍니다.
        assertEquals(old.content, repository.findCurrent(SOURCE, "p1").content)

        assertTrue(repository.fail(first, "AI_TIMEOUT", now.plusHours(1), now.plusMinutes(2)))
        assertEquals(old.content, repository.findCurrent(SOURCE, "p1").content)
        assertNull(jdbc.queryForObject("SELECT failure_code FROM support_program_analysis", String::class.java))
        assertNull(claim(now.plusMinutes(59)))

        val second = claim(now.plusHours(1))!!
        assertTrue(second.keepsPreviousResult)
        assertEquals(2, second.attemptCount)
        // 버전 불일치처럼 재시도하지 않는 실패는 이전 결과를 남기고 더 선택하지 않습니다.
        assertTrue(repository.fail(second, "AI_VERSION_MISMATCH", null, now.plusHours(1)))
        assertNull(claim(now.plusDays(1)))
        assertEquals(SupportProgramAnalysisStatus.COMPLETED, repository.findCurrent(SOURCE, "p1").status)
        assertEquals(OLD_VERSION, jdbc.queryForObject("SELECT analysis_version FROM support_program_analysis", String::class.java))
    }

    @Test
    fun reanalysisReplacesTheOlderVersionAndACrashedReanalysisIsRetriedAfterTheLease() {
        insert("p1", today.plusDays(3))
        assertTrue(repository.complete(claim()!!, OUTPUT.copy(analysisVersion = OLD_VERSION), now))

        val crashed = claim(now.plusMinutes(1))!!
        assertNull(claim(now.plusMinutes(2)))
        val retried = claim(now.plusMinutes(1).plusSeconds(301))!!
        assertNotEquals(crashed.leaseToken, retried.leaseToken)
        assertEquals(2, retried.attemptCount)

        assertTrue(repository.complete(retried, OUTPUT, now.plusMinutes(10)))
        assertEquals(VERSION, jdbc.queryForObject("SELECT analysis_version FROM support_program_analysis", String::class.java))
        assertEquals(OUTPUT.content, repository.findCurrent(SOURCE, "p1").content)
        assertNull(claim(now.plusDays(1)))
    }

    @Test
    fun nonRetryableFailureWithoutAPreviousResultIsStoredAsFailed() {
        insert("p1", today.plusDays(3))
        val lease = claim()!!
        assertFalse(lease.keepsPreviousResult)
        assertTrue(repository.fail(lease, "AI_VERSION_MISMATCH", null, now))
        assertEquals(SupportProgramAnalysis.FAILED, repository.findCurrent(SOURCE, "p1"))
        assertNull(claim(now.plusDays(1)))
    }

    private fun claim(at: LocalDateTime = now) =
        repository.claimNext(at.toLocalDate(), at, MAX_ATTEMPTS, at.plusSeconds(300), VERSION)

    private fun insert(id: String, endDate: LocalDate?): CatalogSupportProgram {
        val base = SupportProgramTestHelper.catalogProgram(id, summary = "서울 \"AI\" 기업 지원 🚀")
        val program = base.copy(program = base.program.copy(
            sourceCode = SOURCE,
            applicationEndDate = endDate,
        ))
        programs.upsert(program)
        return program
    }

    private companion object {
        const val SOURCE = "ANALYSISTEST"
        const val MAX_ATTEMPTS = 3
        const val VERSION = AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION
        const val OLD_VERSION = "govbiz-support-program-analysis-v1"
        const val ATTACHMENT = "2026년 모집 공고문.hwp"
        val EVIDENCE = SupportProgramAnalysisEvidence(
            SupportProgramAnalysisEvidenceField.DETAIL_TEXT, "\"따옴표\" \\ 역슬래시 🚀 <tag> & '홑따옴표'")
        val OUTPUT = SupportProgramAnalysisOutput(
            analysisVersion = VERSION,
            model = "gpt-test",
            content = SupportProgramAnalysisContent(
                summaryLine = "서울 AI 기업에 최대 5천만원 지원",
                supportTypes = listOf(SupportProgramAnalysisSupportType.GRANT, SupportProgramAnalysisSupportType.RND),
                supportAmount = SupportProgramAnalysisAmount("최대 5천만원", 50_000_000, EVIDENCE),
                selectionScale = SupportProgramAnalysisText("10개사 내외", EVIDENCE),
                conditions = listOf(
                    SupportProgramAnalysisCondition(
                        SupportProgramAnalysisConditionKind.REQUIRED,
                        SupportProgramAnalysisConditionCategory.REGION,
                        "서울·경기 소재 기업",
                        SupportProgramAnalysisConditionValues(regions = listOf("서울", "경기")),
                        EVIDENCE,
                    ),
                    SupportProgramAnalysisCondition(
                        SupportProgramAnalysisConditionKind.EXCLUDED,
                        SupportProgramAnalysisConditionCategory.BUSINESS_AGE,
                        "업력 7.5년 초과 제외",
                        SupportProgramAnalysisConditionValues(maxYears = 7.5, minAge = 19, maxAge = 39),
                        EVIDENCE,
                    ),
                ),
                contact = null,
                requiredDocuments = listOf(SupportProgramAnalysisRequiredDocument(
                    "사업계획서", SupportProgramAnalysisDocumentRequirement.REQUIRED, "서식 1",
                    SupportProgramAnalysisEvidence(SupportProgramAnalysisEvidenceField.ATTACHMENT, "사업계획서(서식 1) 제출", ATTACHMENT),
                )),
                schedule = listOf(SupportProgramAnalysisScheduleItem("접수 마감", LocalDate.of(2026, 10, 31), "10월 31일 18시", EVIDENCE)),
                sourceAttachmentNames = listOf(ATTACHMENT),
            ),
            discardedItemCount = 1,
        )
    }
}
