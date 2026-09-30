package ai.govbiz.core.supportprogram.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** 상세 화면에 보이는 공고 분석 상태입니다. 현재 공고 내용과 지문이 일치하는 결과만 COMPLETED·FAILED가 됩니다. */
enum class SupportProgramAnalysisStatus {
    COMPLETED,
    FAILED,
    NOT_ANALYZED,
}

enum class SupportProgramAnalysisSupportType {
    GRANT,
    LOAN,
    GUARANTEE,
    VOUCHER,
    CONSULTING,
    EDUCATION,
    SPACE,
    MARKETING,
    RND,
    EXPORT,
    HR,
    OTHER,
}

enum class SupportProgramAnalysisConditionKind {
    REQUIRED,
    EXCLUDED,
    PREFERRED,
}

enum class SupportProgramAnalysisConditionCategory {
    REGION,
    BUSINESS_AGE,
    FOUNDER_AGE,
    INDUSTRY,
    COMPANY_SIZE,
    LEGAL_FORM,
    CERTIFICATION,
    OTHER,
}

/** 분석 항목의 근거 문장을 가져온 입력 필드입니다. ATTACHMENT는 v2부터 보내는 공고 첨부 본문입니다. */
enum class SupportProgramAnalysisEvidenceField {
    SUMMARY,
    TARGET_DESCRIPTION,
    APPLICATION_METHOD,
    DETAIL_TEXT,
    ATTACHMENT,
}

/** 제출 서류의 제출 조건입니다. CONDITIONAL은 해당자만 제출합니다. */
enum class SupportProgramAnalysisDocumentRequirement {
    REQUIRED,
    OPTIONAL,
    CONDITIONAL,
}

/** 근거 인용입니다. attachmentName은 ATTACHMENT 근거일 때만 있으며, v1 저장 결과에는 없어 null로 읽힙니다. */
data class SupportProgramAnalysisEvidence(
    val field: SupportProgramAnalysisEvidenceField,
    val quote: String,
    val attachmentName: String? = null,
) {
    init {
        require(quote.isNotBlank()) { "analysis evidence quote must not be blank" }
        require((field == SupportProgramAnalysisEvidenceField.ATTACHMENT) == (attachmentName != null)) {
            "attachmentName must be present only for ATTACHMENT evidence"
        }
        require(attachmentName == null || attachmentName.isNotBlank()) { "attachmentName must not be blank" }
    }
}

/** 선정 규모·문의처처럼 원문 표현과 근거만 가진 분석 항목입니다. */
data class SupportProgramAnalysisText(
    val text: String,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(text.isNotBlank()) { "analysis text must not be blank" }
    }
}

data class SupportProgramAnalysisAmount(
    val text: String,
    val maxAmountKrw: Long?,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(text.isNotBlank()) { "support amount text must not be blank" }
        require(maxAmountKrw == null || maxAmountKrw >= 0) { "maxAmountKrw must not be negative" }
    }
}

/** 조건 분류별 구조화 값입니다. 해당하지 않는 값은 null입니다. */
data class SupportProgramAnalysisConditionValues(
    val regions: List<String>? = null,
    val minYears: Double? = null,
    val maxYears: Double? = null,
    val minAge: Int? = null,
    val maxAge: Int? = null,
) {
    init {
        require(regions == null || regions.all(String::isNotBlank)) { "condition regions must not be blank" }
        require(listOfNotNull(minYears, maxYears).all { it.isFinite() && it >= 0 }) { "condition years must be non-negative" }
        require(listOfNotNull(minAge, maxAge).all { it >= 0 }) { "condition ages must be non-negative" }
        require(minYears == null || maxYears == null || minYears <= maxYears) { "minYears must not exceed maxYears" }
        require(minAge == null || maxAge == null || minAge <= maxAge) { "minAge must not exceed maxAge" }
    }
}

data class SupportProgramAnalysisCondition(
    val kind: SupportProgramAnalysisConditionKind,
    val category: SupportProgramAnalysisConditionCategory,
    val text: String,
    val values: SupportProgramAnalysisConditionValues,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(text.isNotBlank()) { "condition text must not be blank" }
    }
}

data class SupportProgramAnalysisRequiredDocument(
    val name: String,
    val requirement: SupportProgramAnalysisDocumentRequirement,
    val note: String?,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(name.isNotBlank()) { "required document name must not be blank" }
        require(note == null || note.isNotBlank()) { "required document note must not be blank" }
    }
}

/** 선정 절차의 한 단계입니다. 목록 순서가 절차 순서입니다. */
data class SupportProgramAnalysisSelectionStep(
    val name: String,
    val note: String?,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(name.isNotBlank()) { "selection step name must not be blank" }
        require(note == null || note.isNotBlank()) { "selection step note must not be blank" }
    }
}

data class SupportProgramAnalysisEvaluationCriterion(
    val item: String,
    val points: Double?,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(item.isNotBlank()) { "evaluation criterion item must not be blank" }
        require(points == null || (points.isFinite() && points >= 0)) { "evaluation points must be non-negative" }
    }
}

/** 공고 일정입니다. date는 원문에 완전한 날짜가 있을 때만 있습니다. */
data class SupportProgramAnalysisScheduleItem(
    val label: String,
    val date: LocalDate?,
    val text: String,
    val evidence: SupportProgramAnalysisEvidence,
) {
    init {
        require(label.isNotBlank()) { "schedule label must not be blank" }
        require(text.isNotBlank()) { "schedule text must not be blank" }
    }
}

/**
 * AI가 공고 원문에서 근거와 함께 추출한 분석 내용입니다. DB에는 이 값만 JSON으로 저장합니다.
 * v2에서 추가된 목록과 sourceAttachmentNames(실제로 보낸 첨부 이름)는 v1 저장 결과에 없으므로 빈 목록으로 읽습니다.
 */
data class SupportProgramAnalysisContent(
    val summaryLine: String?,
    val supportTypes: List<SupportProgramAnalysisSupportType>,
    val supportAmount: SupportProgramAnalysisAmount?,
    val selectionScale: SupportProgramAnalysisText?,
    val conditions: List<SupportProgramAnalysisCondition>,
    val contact: SupportProgramAnalysisText?,
    val requiredDocuments: List<SupportProgramAnalysisRequiredDocument> = emptyList(),
    val selectionSteps: List<SupportProgramAnalysisSelectionStep> = emptyList(),
    val evaluationCriteria: List<SupportProgramAnalysisEvaluationCriterion> = emptyList(),
    val schedule: List<SupportProgramAnalysisScheduleItem> = emptyList(),
    val sourceAttachmentNames: List<String> = emptyList(),
) {
    init {
        require(summaryLine == null || summaryLine.isNotBlank()) { "summaryLine must not be blank" }
        require(supportTypes.toSet().size == supportTypes.size) { "supportTypes must not contain duplicates" }
    }
}

/** 목록·검색 카드의 한 줄 정보에 쓰는 현재 완료 분석의 일부입니다. 근거와 조건은 상세 조회에서만 제공합니다. */
data class SupportProgramAnalysisSummary(
    val summaryLine: String?,
    val supportAmountText: String?,
    val maxAmountKrw: Long?,
    val supportTypes: List<SupportProgramAnalysisSupportType>,
) {
    companion object {
        fun from(content: SupportProgramAnalysisContent) = SupportProgramAnalysisSummary(
            summaryLine = content.summaryLine,
            supportAmountText = content.supportAmount?.text,
            maxAmountKrw = content.supportAmount?.maxAmountKrw,
            supportTypes = content.supportTypes,
        )
    }
}

/**
 * 한 번의 AI 분석 실행 결과입니다. 모델은 기록용입니다. 재분석은 공고 지문이 바뀌었거나 저장된 분석 버전이
 * Core가 기대하는 버전과 다를 때 일어납니다.
 */
data class SupportProgramAnalysisOutput(
    val analysisVersion: String,
    val model: String,
    val content: SupportProgramAnalysisContent,
    val discardedItemCount: Int,
) {
    init {
        require(analysisVersion.isNotBlank()) { "analysisVersion must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(discardedItemCount >= 0) { "discardedItemCount must not be negative" }
    }
}

/** 상세 조회 시점의 공고 분석입니다. */
data class SupportProgramAnalysis(
    val status: SupportProgramAnalysisStatus,
    val analyzedAt: LocalDateTime?,
    val content: SupportProgramAnalysisContent?,
) {
    init {
        require((status == SupportProgramAnalysisStatus.COMPLETED) == (content != null && analyzedAt != null)) {
            "only a completed analysis has content and analyzedAt"
        }
    }

    companion object {
        val NOT_ANALYZED = SupportProgramAnalysis(SupportProgramAnalysisStatus.NOT_ANALYZED, null, null)
        val FAILED = SupportProgramAnalysis(SupportProgramAnalysisStatus.FAILED, null, null)
    }
}

/**
 * 분석 Worker가 한 공고를 처리할 실행권입니다. 결과 저장은 같은 leaseToken일 때만 반영됩니다.
 * keepsPreviousResult는 같은 공고 내용의 이전 버전 완료 결과를 새 버전으로 다시 분석하는 경우이며,
 * 이때 실패해도 이전 결과를 지우지 않습니다.
 */
data class SupportProgramAnalysisLease(
    val sourceCode: String,
    val sourceProgramId: String,
    val programFingerprint: String,
    val leaseToken: String,
    val attemptCount: Int,
    val keepsPreviousResult: Boolean = false,
)
