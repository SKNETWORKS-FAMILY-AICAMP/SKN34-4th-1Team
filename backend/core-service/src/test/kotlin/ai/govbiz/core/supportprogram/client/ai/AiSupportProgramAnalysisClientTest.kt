package ai.govbiz.core.supportprogram.client.ai

import ai.govbiz.core._common.config.JsonDeserializationConfig
import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.exception.AiServiceFailure
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisAttachmentRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisRequest
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisRejectedException
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisVersionMismatchException
import ai.govbiz.core.supportprogram.client.ai.mapper.AiSupportProgramAnalysisMapper
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisDocumentRequirement
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class AiSupportProgramAnalysisClientTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).also {
        JsonDeserializationConfig().strictJsonRequestTypes().customize(it)
    }.build()
    private val builder = RestClient.builder().baseUrl("http://ai-service.test")
        .messageConverters { it.clear(); it.add(JacksonJsonHttpMessageConverter(mapper)) }
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = AiSupportProgramAnalysisClient(builder.build())
    private val request = AiSupportProgramAnalysisRequest(
        sourceCode = "BIZINFO",
        sourceProgramId = "PBLN_1",
        title = "서울 AI 지원사업",
        organization = "수행기관",
        summary = "AI 기업 지원",
        targetDescription = "창업 7년 이내 중소기업",
        applicationPeriod = "2026-10-01 ~ 2026-10-31",
        applicationMethod = null,
        detailText = "최대 5천만원 지원",
        attachments = listOf(AiSupportProgramAnalysisAttachmentRequest(ATTACHMENT, "사업계획서 제출, 서류평가 후 발표평가")),
    )

    @AfterEach
    fun verifyRequests() = server.verify()

    @Test
    fun sendsTheExactCamelCaseContractAndMapsTheValidatedAnalysis() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""{
              "sourceCode":"BIZINFO","sourceProgramId":"PBLN_1","title":"서울 AI 지원사업","organization":"수행기관",
              "summary":"AI 기업 지원","targetDescription":"창업 7년 이내 중소기업",
              "applicationPeriod":"2026-10-01 ~ 2026-10-31","applicationMethod":null,"detailText":"최대 5천만원 지원",
              "attachments":[{"name":"$ATTACHMENT","text":"사업계획서 제출, 서류평가 후 발표평가"}]
            }""", JsonCompareMode.STRICT))
            .andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON))

        val output = client.analyze(request)

        assertEquals(AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION, output.analysisVersion)
        assertEquals("gpt-test", output.model)
        assertEquals(2, output.discardedItemCount)
        val content = output.content
        assertEquals(listOf(SupportProgramAnalysisSupportType.GRANT, SupportProgramAnalysisSupportType.RND), content.supportTypes)
        assertEquals(50_000_000L, content.supportAmount?.maxAmountKrw)
        assertEquals(SupportProgramAnalysisEvidenceField.DETAIL_TEXT, content.supportAmount?.evidence?.field)
        assertNull(content.supportAmount?.evidence?.attachmentName)
        assertNull(content.selectionScale)
        val condition = content.conditions.single()
        assertEquals(SupportProgramAnalysisConditionKind.REQUIRED, condition.kind)
        assertEquals(SupportProgramAnalysisConditionCategory.BUSINESS_AGE, condition.category)
        assertEquals(7.0, condition.values.maxYears)
        assertNull(condition.values.regions)
        assertEquals("02-000-0000", content.contact?.text)
        val document = content.requiredDocuments.single()
        assertEquals(SupportProgramAnalysisDocumentRequirement.REQUIRED, document.requirement)
        assertEquals(SupportProgramAnalysisEvidenceField.ATTACHMENT, document.evidence.field)
        assertEquals(ATTACHMENT, document.evidence.attachmentName)
        assertEquals(listOf("서류평가", "발표평가"), content.selectionSteps.map { it.name })
        assertEquals(40.0, content.evaluationCriteria.single().points)
        assertEquals(LocalDate.of(2026, 10, 31), content.schedule[0].date)
        assertNull(content.schedule[1].date)
        assertEquals(listOf(ATTACHMENT), content.sourceAttachmentNames)
    }

    @ParameterizedTest
    @CsvSource("503,UNAVAILABLE", "504,TIMEOUT", "408,TIMEOUT", "500,UPSTREAM_ERROR", "400,UPSTREAM_ERROR", "204,INVALID_RESPONSE")
    fun classifiesNonOkStatuses(status: Int, failure: AiServiceFailure) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)))
        assertEquals(failure, assertThrows(AiServiceCallException::class.java) { client.analyze(request) }.failure)
    }

    @Test
    fun separatesRequestValidationRejectionFromRetryableFailures() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(422)))
        assertThrows(AiSupportProgramAnalysisRejectedException::class.java) { client.analyze(request) }
    }

    @Test
    fun anotherAnalysisVersionIsAVersionMismatchEvenWhenV2FieldsAreMissing() {
        respond(VALID_RESPONSE.replace(AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION, "govbiz-support-program-analysis-v3"))
        assertThrows(AiSupportProgramAnalysisVersionMismatchException::class.java) { client.analyze(request) }
        server.verify()
        server.reset()
        respond("""{"analysisVersion":"govbiz-support-program-analysis-v1","model":"gpt-test","summaryLine":null,
            "supportTypes":[],"supportAmount":null,"selectionScale":null,"conditions":[],"contact":null,"discardedItemCount":0}""")
        assertThrows(AiSupportProgramAnalysisVersionMismatchException::class.java) { client.analyze(request) }
    }

    @Test
    fun acceptsProvinceShortNamesAndRejectsOtherRegions() {
        respond(VALID_RESPONSE.replace(""""regions":null""", """"regions":["서울","경기"]"""))
        assertEquals(listOf("서울", "경기"), client.analyze(request).content.conditions.single().values.regions)
        server.verify()
        server.reset()
        respond(VALID_RESPONSE.replace(""""regions":null""", """"regions":["서울특별시"]"""))
        assertInvalid()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        // 지원하지 않는 지원 형태
        """"supportTypes":["GRANT","CASH"]""",
        // 중복 지원 형태
        """"supportTypes":["GRANT","GRANT"]""",
        // 필수 필드 누락
        """"supportTypesMissing":[]""",
    ])
    fun rejectsInvalidSupportTypesAsInvalidResponse(replacement: String) {
        respond(VALID_RESPONSE.replace(""""supportTypes":["GRANT","RND"]""", replacement))
        assertInvalid()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """"kind":"MAYBE"""",
        """"category":"MOOD"""",
        """"requirement":"MAYBE"""",
        // DETAIL_TEXT 근거를 이름 없는 첨부 근거로 바꿉니다.
        """"field":"ATTACHMENT"""",
        """"text":"   """",
        """"maxYears":-1""",
        """"quote":"""",
        """"discardedItemCount":-1""",
        """"maxAmountKrw":1.5""",
        """"points":1000.5""",
        """"points":-1""",
        """"date":"2026-02-30"""",
        """"date":"2026-9-1"""",
        """"date":"2026/10/31"""",
        """"note":" """",
    ])
    fun rejectsInvalidValuesAsInvalidResponse(replacement: String) {
        val key = replacement.substringBefore(':')
        val original = Regex("""$key:("[^"]*"|-?[0-9.]+|null)""").find(VALID_RESPONSE)!!.value
        respond(VALID_RESPONSE.replaceFirst(original, replacement))
        assertInvalid()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        // 첨부 근거인데 보내지 않은 첨부 이름
        """"attachmentName":"다른 파일.hwp"""",
        // 첨부 근거인데 이름 없음
        """"attachmentName":null""",
    ])
    fun attachmentEvidenceMustNameASentAttachment(replacement: String) {
        respond(VALID_RESPONSE.replace(""""attachmentName":"$ATTACHMENT"""", replacement))
        assertInvalid()
    }

    @Test
    fun nonAttachmentEvidenceMustNotCarryAnAttachmentName() {
        respond(VALID_RESPONSE.replace(
            """{"field":"DETAIL_TEXT","quote":"최대 5천만원 지원","attachmentName":null}""",
            """{"field":"DETAIL_TEXT","quote":"최대 5천만원 지원","attachmentName":"$ATTACHMENT"}""",
        ))
        assertInvalid()
    }

    @Test
    fun rejectsEvidenceFromAFieldThatWasNotSent() {
        server.expect(requestTo(URL)).andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON))
        val exception = assertThrows(AiServiceCallException::class.java) { client.analyze(request.copy(detailText = null)) }
        assertEquals(AiServiceFailure.INVALID_RESPONSE, exception.failure)
        server.verify()
        server.reset()
        server.expect(requestTo(URL)).andRespond(withSuccess(VALID_RESPONSE, MediaType.APPLICATION_JSON))
        val withoutAttachments = assertThrows(AiServiceCallException::class.java) {
            client.analyze(request.copy(attachments = emptyList()))
        }
        assertEquals(AiServiceFailure.INVALID_RESPONSE, withoutAttachments.failure)
    }

    @Test
    fun rejectsOverlongTextAndTooManyItemsWithoutEchoingModelOutput() {
        val longText = "가".repeat(1_001)
        respond(VALID_RESPONSE.replace(""""text":"02-000-0000"""", """"text":"$longText""""))
        val exception = assertInvalid()
        assertFalse(exception.message!!.contains(longText))
        server.verify()
        server.reset()
        val step = """{"name":"단계","note":null,"evidence":{"field":"SUMMARY","quote":"AI 기업 지원","attachmentName":null}}"""
        respond(VALID_RESPONSE.replace(Regex(""""selectionSteps":\[.*?]\s*,""", RegexOption.DOT_MATCHES_ALL),
            """"selectionSteps":[${List(11) { step }.joinToString(",")}],"""))
        assertInvalid()
    }

    @Test
    fun v2ListsAreRequiredForTheExpectedVersion() {
        respond(VALID_RESPONSE.replace(""""schedule":""", """"scheduleMissing":"""))
        assertInvalid()
    }

    @Test
    fun acceptsANullOptionalSectionAndEmptyLists() {
        respond("""{"analysisVersion":"${AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION}","model":"gpt-test",
            "summaryLine":null,"supportTypes":[],"supportAmount":null,"selectionScale":null,"conditions":[],"contact":null,
            "requiredDocuments":[],"selectionSteps":[],"evaluationCriteria":[],"schedule":[],"discardedItemCount":0}""")
        val output = client.analyze(request.copy(attachments = emptyList()))
        assertEquals(emptyList<Any>(), output.content.conditions)
        assertEquals(emptyList<Any>(), output.content.sourceAttachmentNames)
        assertNull(output.content.summaryLine)
    }

    private fun respond(body: String) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun assertInvalid(): AiServiceCallException {
        val exception = assertThrows(AiServiceCallException::class.java) { client.analyze(request) }
        assertEquals(AiServiceFailure.INVALID_RESPONSE, exception.failure)
        return exception
    }

    private companion object {
        const val URL = "http://ai-service.test/internal/v1/support-program-analyses/analyze"
        const val ATTACHMENT = "2026 공고문.hwp"
        val VALID_RESPONSE = """{
          "analysisVersion":"${AiSupportProgramAnalysisMapper.EXPECTED_ANALYSIS_VERSION}","model":"gpt-test",
          "summaryLine":"서울 AI 기업에 최대 5천만원을 지원합니다.",
          "supportTypes":["GRANT","RND"],
          "supportAmount":{"text":"최대 5천만원","maxAmountKrw":50000000,"evidence":{"field":"DETAIL_TEXT","quote":"최대 5천만원 지원","attachmentName":null}},
          "selectionScale":null,
          "conditions":[{"kind":"REQUIRED","category":"BUSINESS_AGE","text":"창업 7년 이내",
            "values":{"regions":null,"minYears":null,"maxYears":7.0,"minAge":null,"maxAge":null},
            "evidence":{"field":"TARGET_DESCRIPTION","quote":"창업 7년 이내 중소기업","attachmentName":null}}],
          "contact":{"text":"02-000-0000","evidence":{"field":"SUMMARY","quote":"AI 기업 지원","attachmentName":null}},
          "requiredDocuments":[{"name":"사업계획서","requirement":"REQUIRED","note":"지정 양식",
            "evidence":{"field":"ATTACHMENT","quote":"사업계획서 제출","attachmentName":"$ATTACHMENT"}}],
          "selectionSteps":[
            {"name":"서류평가","note":null,"evidence":{"field":"ATTACHMENT","quote":"서류평가","attachmentName":"$ATTACHMENT"}},
            {"name":"발표평가","note":"서류 통과 기업","evidence":{"field":"ATTACHMENT","quote":"발표평가","attachmentName":"$ATTACHMENT"}}],
          "evaluationCriteria":[{"item":"기술성","points":40.0,
            "evidence":{"field":"SUMMARY","quote":"AI 기업 지원","attachmentName":null}}],
          "schedule":[
            {"label":"접수 마감","date":"2026-10-31","text":"10월 31일까지","evidence":{"field":"SUMMARY","quote":"AI 기업 지원","attachmentName":null}},
            {"label":"발표평가","date":null,"text":"11월 중","evidence":{"field":"ATTACHMENT","quote":"발표평가","attachmentName":"$ATTACHMENT"}}],
          "discardedItemCount":2
        }""".trimIndent()
    }
}
