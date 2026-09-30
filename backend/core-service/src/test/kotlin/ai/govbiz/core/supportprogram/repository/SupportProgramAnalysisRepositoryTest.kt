package ai.govbiz.core.supportprogram.repository

import ai.govbiz.core._common.config.JsonDeserializationConfig
import ai.govbiz.core.supportprogram.controller.dto.SupportProgramAnalysisResponse
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisDocumentRequirement
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvaluationCriterion
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidence
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisRequiredDocument
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisScheduleItem
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSelectionStep
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramAnalysisDbRow
import ai.govbiz.core.supportprogram.repository.mapper.SupportProgramAnalysisMapper
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.junit.jupiter.MockitoExtension
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** DB 없이 저장 JSON의 v1 하위 호환과 v2 왕복을 확인합니다. 실제 MySQL 동작은 통합 테스트가 확인합니다. */
@ExtendWith(MockitoExtension::class)
class SupportProgramAnalysisRepositoryTest {
    @Mock private lateinit var mapper: SupportProgramAnalysisMapper

    private val objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).also {
        JsonDeserializationConfig().strictJsonRequestTypes().customize(it)
    }.build()

    @Test
    fun v1AnalysisJsonStillReadsWithEmptyV2ListsAndNullAttachmentNames() {
        stored(V1_JSON)

        val analysis = SupportProgramAnalysisRepository(mapper, objectMapper).findCurrent("BIZINFO", "PBLN_1")

        assertEquals(SupportProgramAnalysisStatus.COMPLETED, analysis.status)
        val content = analysis.content!!
        assertEquals(listOf(SupportProgramAnalysisSupportType.GRANT), content.supportTypes)
        assertNull(content.supportAmount!!.evidence.attachmentName)
        assertEquals(emptyList<Any>(), content.requiredDocuments)
        assertEquals(emptyList<Any>(), content.schedule)
        assertEquals(emptyList<Any>(), content.sourceAttachmentNames)

        val json = objectMapper.readTree(objectMapper.writeValueAsString(SupportProgramAnalysisResponse.from(analysis)))
        assertEquals("COMPLETED", json["status"].stringValue())
        for (field in listOf("requiredDocuments", "selectionSteps", "evaluationCriteria", "schedule", "sourceAttachmentNames")) {
            assertEquals(0, json[field].size(), field)
        }
        assertEquals(true, json["supportAmount"]["evidence"].has("attachmentName"))
        assertEquals(true, json["supportAmount"]["evidence"]["attachmentName"].isNull)
        assertEquals("서울", json["conditions"][0]["values"]["regions"][0].stringValue())
    }

    @Test
    fun v2ContentRoundTripsThroughTheStoredJson() {
        val attachment = SupportProgramAnalysisEvidence(SupportProgramAnalysisEvidenceField.ATTACHMENT, "\"제출\" 서류 🚀", "공고문.hwp")
        val summary = SupportProgramAnalysisEvidence(SupportProgramAnalysisEvidenceField.SUMMARY, "요약 인용")
        val content = SupportProgramAnalysisContent(
            summaryLine = "요약",
            supportTypes = emptyList(),
            supportAmount = null,
            selectionScale = null,
            conditions = emptyList(),
            contact = null,
            requiredDocuments = listOf(SupportProgramAnalysisRequiredDocument("사업계획서", SupportProgramAnalysisDocumentRequirement.OPTIONAL, null, attachment)),
            selectionSteps = listOf(SupportProgramAnalysisSelectionStep("서류평가", "1차", attachment)),
            evaluationCriteria = listOf(SupportProgramAnalysisEvaluationCriterion("기술성", 12.5, summary)),
            schedule = listOf(SupportProgramAnalysisScheduleItem("마감", LocalDate.of(2026, 10, 31), "10월 31일", summary)),
            sourceAttachmentNames = listOf("공고문.hwp"),
        )
        val json = objectMapper.writeValueAsString(content)
        stored(json)

        assertEquals(content, SupportProgramAnalysisRepository(mapper, objectMapper).findCurrent("BIZINFO", "PBLN_1").content)
        assertEquals("2026-10-31", objectMapper.readTree(json)["schedule"][0]["date"].stringValue())
    }

    private fun stored(json: String) {
        doReturn(SupportProgramAnalysisDbRow(
            sourceCode = "BIZINFO",
            sourceProgramId = "PBLN_1",
            programFingerprint = "a".repeat(64),
            currentProgramFingerprint = "a".repeat(64),
            status = "COMPLETED",
            analysisVersion = "govbiz-support-program-analysis-v1",
            model = "gpt-test",
            analysisJson = json,
            analyzedAt = LocalDateTime.of(2026, 10, 1, 10, 0),
        )).`when`(mapper).findWithCurrentFingerprint("BIZINFO", "PBLN_1")
    }

    private companion object {
        /** V46 초기 버전이 저장한 분석 JSON 형태입니다. */
        const val V1_JSON = """{"summaryLine":"요약","supportTypes":["GRANT"],
            "supportAmount":{"text":"최대 5천만원","maxAmountKrw":50000000,"evidence":{"field":"DETAIL_TEXT","quote":"최대 5천만원 지원"}},
            "selectionScale":null,
            "conditions":[{"kind":"REQUIRED","category":"REGION","text":"서울 소재",
              "values":{"regions":["서울"],"minYears":null,"maxYears":null,"minAge":null,"maxAge":null},
              "evidence":{"field":"TARGET_DESCRIPTION","quote":"서울 소재 기업"}}],
            "contact":null}"""
    }
}
