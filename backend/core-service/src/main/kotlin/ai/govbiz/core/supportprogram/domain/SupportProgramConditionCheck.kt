package ai.govbiz.core.supportprogram.domain

import java.time.LocalDate
import kotlin.math.roundToLong

/** 분석 조건 하나를 기업 프로필과 비교한 결과입니다. */
enum class SupportProgramConditionResult {
    MET,
    NOT_MET,
    UNKNOWN,
}

/**
 * 비교 결과의 근거 코드입니다. `REGION_*`·`BUSINESS_AGE_*`는 기업이 조건 values가 말하는 집단에 속하는지를 나타내며,
 * 필수·제외·우대에 따른 충족 여부는 [SupportProgramConditionResult]가 나타냅니다.
 */
enum class SupportProgramConditionReason {
    REGION_MATCH,
    REGION_MISMATCH,
    BUSINESS_AGE_WITHIN,
    BUSINESS_AGE_OUTSIDE,
    BOUNDARY_YEAR,
    PRE_STARTUP_ONLY,
    PROFILE_MISSING,
    NOT_COMPARABLE,
}

data class SupportProgramConditionVerdict(
    val result: SupportProgramConditionResult,
    val reason: SupportProgramConditionReason,
)

/** 조건별 판정(입력 조건과 같은 순서)과 필수·제외 조건만으로 정한 전체 판정입니다. */
data class SupportProgramConditionEvaluation(
    val overall: SupportProgramConditionResult,
    val conditions: List<SupportProgramConditionVerdict>,
)

/** 조건 확인에 쓰는 기업 프로필입니다. 설립일은 연도만 알고, 대표자 나이는 프로필에 없습니다. */
data class SupportProgramApplicantProfile(
    val region: String?,
    val foundedYear: Int?,
)

/**
 * 공고 분석의 구조화 조건을 기업 프로필과 비교하는 순수 규칙입니다. 자유 문장은 추측하지 않고, 확인할 수 없으면
 * UNKNOWN입니다. 지역은 17개 시·도 약칭으로 맞춰 비교하고, 업력은 설립연도의 1월 1일~12월 31일이 모두 같은
 * 결과일 때만 판정합니다. 선정 가능성이나 법적 신청 자격을 확정하지 않습니다.
 */
object SupportProgramConditionCheck {
    const val NATIONWIDE = "전국"

    fun check(
        conditions: List<SupportProgramAnalysisCondition>,
        applicant: SupportProgramApplicantProfile,
        referenceDate: LocalDate,
    ): SupportProgramConditionEvaluation {
        val verdicts = conditions.map { verdict(it, applicant, referenceDate) }
        // 우대 조건은 신청 가능 여부가 아니므로 전체 판정에 넣지 않습니다.
        val counted = conditions.indices
            .filter { conditions[it].kind != SupportProgramAnalysisConditionKind.PREFERRED }
            .map { verdicts[it].result }
        val overall = when {
            SupportProgramConditionResult.NOT_MET in counted -> SupportProgramConditionResult.NOT_MET
            SupportProgramConditionResult.UNKNOWN in counted -> SupportProgramConditionResult.UNKNOWN
            counted.isNotEmpty() -> SupportProgramConditionResult.MET
            else -> SupportProgramConditionResult.UNKNOWN
        }
        return SupportProgramConditionEvaluation(overall, verdicts)
    }

    /** 프로필 소재지(서울특별시·서울 등)를 시·도 약칭으로 바꿉니다. 알 수 없는 값과 `전국`은 null입니다. */
    fun regionName(region: String?): String? = region?.trim()?.let(REGION_NAMES::get)

    private fun verdict(
        condition: SupportProgramAnalysisCondition,
        applicant: SupportProgramApplicantProfile,
        referenceDate: LocalDate,
    ): SupportProgramConditionVerdict {
        val values = condition.values
        val membership = when (condition.category) {
            SupportProgramAnalysisConditionCategory.REGION -> region(values.regions, applicant.region)
            SupportProgramAnalysisConditionCategory.BUSINESS_AGE -> businessAge(values, applicant.foundedYear, referenceDate)
            SupportProgramAnalysisConditionCategory.FOUNDER_AGE ->
                if (values.minAge == null && values.maxAge == null) Membership.unknown(SupportProgramConditionReason.NOT_COMPARABLE)
                else Membership.unknown(SupportProgramConditionReason.PROFILE_MISSING)
            else -> Membership.unknown(SupportProgramConditionReason.NOT_COMPARABLE)
        }
        val result = when (membership.fallsIn) {
            null -> SupportProgramConditionResult.UNKNOWN
            // 제외 조건은 그 집단에 속하면 충족하지 못한 것이고, 필수·우대는 속해야 충족입니다.
            true -> if (condition.kind == SupportProgramAnalysisConditionKind.EXCLUDED) {
                SupportProgramConditionResult.NOT_MET
            } else {
                SupportProgramConditionResult.MET
            }
            false -> if (condition.kind == SupportProgramAnalysisConditionKind.EXCLUDED) {
                SupportProgramConditionResult.MET
            } else {
                SupportProgramConditionResult.NOT_MET
            }
        }
        return SupportProgramConditionVerdict(result, membership.reason)
    }

    private fun region(regions: List<String>?, applicantRegion: String?): Membership {
        if (regions.isNullOrEmpty()) return Membership.unknown(SupportProgramConditionReason.NOT_COMPARABLE)
        val company = regionName(applicantRegion)
            ?: return Membership.unknown(SupportProgramConditionReason.PROFILE_MISSING)
        val names = regions.map { if (it.trim() == NATIONWIDE) NATIONWIDE else regionName(it) }
        if (null in names) return Membership.unknown(SupportProgramConditionReason.NOT_COMPARABLE)
        return if (NATIONWIDE in names || company in names) {
            Membership(true, SupportProgramConditionReason.REGION_MATCH)
        } else {
            Membership(false, SupportProgramConditionReason.REGION_MISMATCH)
        }
    }

    /**
     * maxYears는 기준일에서 그 기간을 뺀 날 이후 설립, minYears는 그날 이전 설립을 뜻합니다(경계일 포함).
     * 소수 연수는 개월로 반올림합니다. 설립연도 안의 모든 날짜가 같은 결과가 아니면 BOUNDARY_YEAR입니다.
     */
    private fun businessAge(
        values: SupportProgramAnalysisConditionValues,
        foundedYear: Int?,
        referenceDate: LocalDate,
    ): Membership {
        val minYears = values.minYears
        val maxYears = values.maxYears
        if (minYears == null && maxYears == null) return Membership.unknown(SupportProgramConditionReason.NOT_COMPARABLE)
        // 예비창업자만 받는 조건입니다. 조건 확인은 기업을 등록한 계정만 하므로 그 집단에 속하지 않습니다.
        if (maxYears != null && maxYears <= 0.0) return Membership(false, SupportProgramConditionReason.PRE_STARTUP_ONLY)
        val year = foundedYear ?: return Membership.unknown(SupportProgramConditionReason.PROFILE_MISSING)
        val earliest = LocalDate.of(year, 1, 1)
        val latest = LocalDate.of(year, 12, 31)

        val withinMax: Boolean? = if (maxYears == null) {
            true
        } else {
            val cutoff = referenceDate.minusMonths(months(maxYears))
            when {
                !earliest.isBefore(cutoff) -> true
                latest.isBefore(cutoff) -> false
                else -> null
            }
        }
        val withinMin: Boolean? = if (minYears == null) {
            true
        } else {
            val bound = referenceDate.minusMonths(months(minYears))
            when {
                !latest.isAfter(bound) -> true
                earliest.isAfter(bound) -> false
                else -> null
            }
        }
        return when {
            withinMax == false || withinMin == false -> Membership(false, SupportProgramConditionReason.BUSINESS_AGE_OUTSIDE)
            withinMax == null || withinMin == null -> Membership.unknown(SupportProgramConditionReason.BOUNDARY_YEAR)
            else -> Membership(true, SupportProgramConditionReason.BUSINESS_AGE_WITHIN)
        }
    }

    private fun months(years: Double): Long = (years * MONTHS_PER_YEAR).roundToLong()

    /** 기업이 조건 values가 말하는 집단에 속하는지입니다. null이면 확인할 수 없습니다. */
    private data class Membership(val fallsIn: Boolean?, val reason: SupportProgramConditionReason) {
        companion object {
            fun unknown(reason: SupportProgramConditionReason) = Membership(null, reason)
        }
    }

    private const val MONTHS_PER_YEAR = 12.0

    private val SHORT_REGION_NAMES = listOf(
        "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기",
        "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
    )

    /** 약칭은 그대로, 현재·이전 정식 명칭은 약칭으로 바꿉니다. 기업 프로필은 정식 명칭(서울특별시)으로 저장됩니다. */
    private val REGION_NAMES: Map<String, String> = SHORT_REGION_NAMES.associateWith { it } + mapOf(
        "서울특별시" to "서울",
        "부산광역시" to "부산",
        "대구광역시" to "대구",
        "인천광역시" to "인천",
        "광주광역시" to "광주",
        "대전광역시" to "대전",
        "울산광역시" to "울산",
        "세종특별자치시" to "세종",
        "경기도" to "경기",
        "강원특별자치도" to "강원",
        "강원도" to "강원",
        "충청북도" to "충북",
        "충청남도" to "충남",
        "전북특별자치도" to "전북",
        "전라북도" to "전북",
        "전라남도" to "전남",
        "경상북도" to "경북",
        "경상남도" to "경남",
        "제주특별자치도" to "제주",
        "제주도" to "제주",
    )
}
