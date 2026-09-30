package ai.govbiz.core.supportprogram.client.ai.mapper

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisAmountPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisAttachmentRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisConditionPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisEvaluationCriterionPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisEvidencePayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisRequest
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisRequiredDocumentPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisScheduleItemPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisSelectionStepPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisTextPayload
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisVersionMismatchException
import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisAmount
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisCondition
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionValues
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisContent
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisDocumentRequirement
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvaluationCriterion
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidence
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisEvidenceField
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisOutput
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisRequiredDocument
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisScheduleItem
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSelectionStep
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSupportType
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisText
import ai.govbiz.core.supportprogram.domain.SupportProgramAttachmentText
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 공고를 AI Service 분석 요청으로 만들고, 원본 응답을 버전·열거값·길이 검증 후 도메인 분석 결과로 변환합니다. */
object AiSupportProgramAnalysisMapper {
    /** Core가 저장·표시하는 분석 계약 버전입니다. 저장된 완료 분석의 버전이 다르면 다시 분석합니다. */
    const val EXPECTED_ANALYSIS_VERSION = "govbiz-support-program-analysis-v2"

    const val MAX_TITLE = 500
    const val MAX_ORGANIZATION = 255
    const val MAX_SUMMARY = 20_000
    const val MAX_TARGET_DESCRIPTION = 8_000
    const val MAX_APPLICATION_PERIOD = 1_000
    const val MAX_APPLICATION_METHOD = 8_000
    const val MAX_DETAIL_TEXT = 30_000
    const val MAX_ATTACHMENTS = 8
    const val MAX_ATTACHMENT_NAME = 255
    const val MAX_ATTACHMENT_TEXT_TOTAL = 40_000

    /** 이름에 이 단어가 있는 첨부(공고문)를 먼저 보냅니다. */
    private const val NOTICE_KEYWORD = "공고"

    // 응답 검증 한도입니다. DB 컬럼 길이와 화면 표시를 넘는 비정상 응답만 거부하도록 넉넉하게 둡니다.
    // 목록 개수는 AI Service 계약의 최대값과 같습니다.
    private const val MAX_MODEL = 200
    private const val MAX_SUMMARY_LINE = 500
    private const val MAX_TEXT = 1_000
    private const val MAX_QUOTE = 1_000
    private const val MAX_CONDITIONS = 30
    private const val MAX_REGIONS = 18
    private const val MAX_YEARS = 100.0
    private const val MAX_AGE = 120
    private const val MAX_REQUIRED_DOCUMENTS = 30
    private const val MAX_SELECTION_STEPS = 10
    private const val MAX_EVALUATION_CRITERIA = 20
    private const val MAX_SCHEDULE = 15
    private const val MAX_POINTS = 1_000.0
    private val DATE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** AI Service 계약의 17개 시·도 약칭과 전국입니다. 시·군·구는 조건 text에만 남습니다. */
    private val REGIONS = setOf(
        "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기",
        "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주", "전국",
    )

    /** 요청 한도를 넘는 값은 코드 포인트 단위로 잘라 서로게이트 쌍이 깨지지 않게 합니다. */
    fun toRequest(
        program: SupportProgram,
        detailText: String?,
        attachments: List<SupportProgramAttachmentText> = emptyList(),
    ): AiSupportProgramAnalysisRequest =
        AiSupportProgramAnalysisRequest(
            sourceCode = program.sourceCode,
            sourceProgramId = program.id,
            title = program.title.clip(MAX_TITLE),
            organization = program.organization.clip(MAX_ORGANIZATION),
            summary = program.summary.clip(MAX_SUMMARY),
            targetDescription = program.targetDescription.clip(MAX_TARGET_DESCRIPTION),
            applicationPeriod = program.applicationPeriod.clip(MAX_APPLICATION_PERIOD),
            applicationMethod = program.applicationRoute.method?.takeIf(String::isNotBlank)?.clip(MAX_APPLICATION_METHOD),
            detailText = detailText?.takeIf(String::isNotBlank)?.clip(MAX_DETAIL_TEXT),
            attachments = selectAttachments(attachments),
        )

    /**
     * 이름에 `공고`가 있는 첨부를 먼저, 나머지는 원래 순서로 최대 8개까지 보냅니다. 본문 합계가 40,000 코드 포인트를
     * 넘지 않도록 앞 첨부부터 남은 한도만큼 자르고, 한도를 다 쓰면 이후 첨부는 보내지 않습니다.
     */
    private fun selectAttachments(files: List<SupportProgramAttachmentText>): List<AiSupportProgramAnalysisAttachmentRequest> {
        val ordered = files.withIndex().sortedBy { if (NOTICE_KEYWORD in it.value.name) 0 else 1 }
        var remaining = MAX_ATTACHMENT_TEXT_TOTAL
        return buildList {
            for ((index, file) in ordered) {
                if (size == MAX_ATTACHMENTS || remaining == 0) break
                val text = file.text.trim().clip(remaining)
                if (text.isBlank()) continue
                remaining -= text.codePointCount(0, text.length)
                val name = file.name.trim().ifBlank { "첨부 ${index + 1}" }.clip(MAX_ATTACHMENT_NAME)
                add(AiSupportProgramAnalysisAttachmentRequest(name, text))
            }
        }
    }

    /**
     * 버전이 [EXPECTED_ANALYSIS_VERSION]과 다르면 [AiSupportProgramAnalysisVersionMismatchException]을, 그 밖의 규칙 위반은
     * INVALID_RESPONSE를 던집니다. 저장 내용의 sourceAttachmentNames는 실제로 보낸 첨부 이름입니다.
     */
    fun toOutput(payload: AiSupportProgramAnalysisPayload, request: AiSupportProgramAnalysisRequest): SupportProgramAnalysisOutput {
        if (payload.analysisVersion != EXPECTED_ANALYSIS_VERSION) throw AiSupportProgramAnalysisVersionMismatchException()
        val sources = EvidenceSources(
            fields = buildSet {
                add(SupportProgramAnalysisEvidenceField.SUMMARY)
                add(SupportProgramAnalysisEvidenceField.TARGET_DESCRIPTION)
                if (request.applicationMethod != null) add(SupportProgramAnalysisEvidenceField.APPLICATION_METHOD)
                if (request.detailText != null) add(SupportProgramAnalysisEvidenceField.DETAIL_TEXT)
                if (request.attachments.isNotEmpty()) add(SupportProgramAnalysisEvidenceField.ATTACHMENT)
            },
            attachmentNames = request.attachments.map { it.name }.toSet(),
        )
        return try {
            SupportProgramAnalysisOutput(
                analysisVersion = EXPECTED_ANALYSIS_VERSION,
                model = required(payload.model, "model", MAX_MODEL),
                content = SupportProgramAnalysisContent(
                    summaryLine = payload.summaryLine?.also { requireLength(it, "summaryLine", MAX_SUMMARY_LINE) },
                    supportTypes = requireNotNull(payload.supportTypes) { "supportTypes is required" }
                        .map { enumValue<SupportProgramAnalysisSupportType>(it, "supportTypes") },
                    supportAmount = payload.supportAmount?.let { amount(it, sources) },
                    selectionScale = payload.selectionScale?.let { text(it, sources) },
                    conditions = items(payload.conditions, "conditions", MAX_CONDITIONS) { condition(it, sources) },
                    contact = payload.contact?.let { text(it, sources) },
                    requiredDocuments = items(payload.requiredDocuments, "requiredDocuments", MAX_REQUIRED_DOCUMENTS) {
                        requiredDocument(it, sources)
                    },
                    selectionSteps = items(payload.selectionSteps, "selectionSteps", MAX_SELECTION_STEPS) {
                        selectionStep(it, sources)
                    },
                    evaluationCriteria = items(payload.evaluationCriteria, "evaluationCriteria", MAX_EVALUATION_CRITERIA) {
                        evaluationCriterion(it, sources)
                    },
                    schedule = items(payload.schedule, "schedule", MAX_SCHEDULE) { scheduleItem(it, sources) },
                    sourceAttachmentNames = request.attachments.map { it.name },
                ),
                discardedItemCount = requireNotNull(payload.discardedItemCount) { "discardedItemCount is required" },
            )
        } catch (exception: IllegalArgumentException) {
            // 모델 출력 원문은 로그·예외에 남기지 않고 어떤 규칙이 깨졌는지만 전달합니다.
            throw AiServiceCallException.invalidResponse("AI analysis response was invalid: ${exception.message}", null)
        }
    }

    /** 요청에 실제로 보낸 근거 필드와 첨부 이름입니다. */
    private class EvidenceSources(
        val fields: Set<SupportProgramAnalysisEvidenceField>,
        val attachmentNames: Set<String>,
    )

    private fun <P : Any, T> items(values: List<P?>?, name: String, max: Int, map: (P) -> T): List<T> {
        requireNotNull(values) { "$name is required" }
        require(values.size <= max) { "too many $name" }
        return values.map { map(requireNotNull(it) { "$name item must not be null" }) }
    }

    private fun amount(payload: AiSupportProgramAnalysisAmountPayload, sources: EvidenceSources) = SupportProgramAnalysisAmount(
        text = required(payload.text, "supportAmount.text", MAX_TEXT),
        maxAmountKrw = payload.maxAmountKrw,
        evidence = evidence(payload.evidence, sources),
    )

    private fun text(payload: AiSupportProgramAnalysisTextPayload, sources: EvidenceSources) = SupportProgramAnalysisText(
        text = required(payload.text, "text", MAX_TEXT),
        evidence = evidence(payload.evidence, sources),
    )

    private fun requiredDocument(payload: AiSupportProgramAnalysisRequiredDocumentPayload, sources: EvidenceSources) =
        SupportProgramAnalysisRequiredDocument(
            name = required(payload.name, "requiredDocument name", MAX_TEXT),
            requirement = enumValue<SupportProgramAnalysisDocumentRequirement>(payload.requirement, "requiredDocument requirement"),
            note = payload.note?.let { required(it, "requiredDocument note", MAX_TEXT) },
            evidence = evidence(payload.evidence, sources),
        )

    private fun selectionStep(payload: AiSupportProgramAnalysisSelectionStepPayload, sources: EvidenceSources) =
        SupportProgramAnalysisSelectionStep(
            name = required(payload.name, "selectionStep name", MAX_TEXT),
            note = payload.note?.let { required(it, "selectionStep note", MAX_TEXT) },
            evidence = evidence(payload.evidence, sources),
        )

    private fun evaluationCriterion(payload: AiSupportProgramAnalysisEvaluationCriterionPayload, sources: EvidenceSources) =
        SupportProgramAnalysisEvaluationCriterion(
            item = required(payload.item, "evaluationCriterion item", MAX_TEXT),
            points = payload.points?.also {
                require(it.isFinite() && it in 0.0..MAX_POINTS) { "evaluation points out of range" }
            },
            evidence = evidence(payload.evidence, sources),
        )

    private fun scheduleItem(payload: AiSupportProgramAnalysisScheduleItemPayload, sources: EvidenceSources) =
        SupportProgramAnalysisScheduleItem(
            label = required(payload.label, "schedule label", MAX_TEXT),
            date = payload.date?.let(::date),
            text = required(payload.text, "schedule text", MAX_TEXT),
            evidence = evidence(payload.evidence, sources),
        )

    private fun date(value: String): LocalDate {
        require(DATE_PATTERN.matches(value)) { "schedule date must be YYYY-MM-DD" }
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("schedule date is not a real date")
        }
    }

    private fun condition(payload: AiSupportProgramAnalysisConditionPayload, sources: EvidenceSources): SupportProgramAnalysisCondition {
        val values = requireNotNull(payload.values) { "condition values is required" }
        val regions = values.regions?.map { region ->
            requireNotNull(region) { "condition region must not be null" }
                .also { require(it in REGIONS) { "condition region is not supported" } }
        }
        require(regions == null || (regions.size <= MAX_REGIONS && regions.toSet().size == regions.size)) {
            "condition regions must be distinct and within the limit"
        }
        require(listOfNotNull(values.minYears, values.maxYears).all { it <= MAX_YEARS }) { "condition years out of range" }
        require(listOfNotNull(values.minAge, values.maxAge).all { it <= MAX_AGE }) { "condition ages out of range" }
        return SupportProgramAnalysisCondition(
            kind = enumValue<SupportProgramAnalysisConditionKind>(payload.kind, "condition kind"),
            category = enumValue<SupportProgramAnalysisConditionCategory>(payload.category, "condition category"),
            text = required(payload.text, "condition text", MAX_TEXT),
            values = SupportProgramAnalysisConditionValues(
                regions = regions,
                minYears = values.minYears,
                maxYears = values.maxYears,
                minAge = values.minAge,
                maxAge = values.maxAge,
            ),
            evidence = evidence(payload.evidence, sources),
        )
    }

    /** 근거 필드는 요청에 보낸 필드여야 하고, attachmentName은 ATTACHMENT일 때만 보낸 첨부 이름 중 하나여야 합니다. */
    private fun evidence(payload: AiSupportProgramAnalysisEvidencePayload?, sources: EvidenceSources): SupportProgramAnalysisEvidence {
        requireNotNull(payload) { "evidence is required" }
        val field = enumValue<SupportProgramAnalysisEvidenceField>(payload.field, "evidence field")
        require(field in sources.fields) { "evidence field was not sent in the request" }
        val attachmentName = payload.attachmentName
        if (field == SupportProgramAnalysisEvidenceField.ATTACHMENT) {
            require(attachmentName != null && attachmentName in sources.attachmentNames) {
                "attachment evidence must name a sent attachment"
            }
        } else {
            require(attachmentName == null) { "attachmentName is only allowed for ATTACHMENT evidence" }
        }
        return SupportProgramAnalysisEvidence(field, required(payload.quote, "evidence quote", MAX_QUOTE), attachmentName)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, name: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("$name is not supported")

    private fun required(value: String?, name: String, max: Int): String {
        requireNotNull(value) { "$name is required" }
        require(value.isNotBlank()) { "$name must not be blank" }
        requireLength(value, name, max)
        return value
    }

    private fun requireLength(value: String, name: String, max: Int) {
        require(value.codePointCount(0, value.length) <= max) { "$name exceeds $max code points" }
    }

    private fun String.clip(max: Int): String =
        if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))
}
