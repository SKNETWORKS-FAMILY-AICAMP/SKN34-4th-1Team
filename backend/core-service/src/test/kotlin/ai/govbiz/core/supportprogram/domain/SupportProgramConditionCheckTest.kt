package ai.govbiz.core.supportprogram.domain

import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory.BUSINESS_AGE
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory.FOUNDER_AGE
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory.INDUSTRY
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionCategory.REGION
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind.EXCLUDED
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind.PREFERRED
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisConditionKind.REQUIRED
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.BOUNDARY_YEAR
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.BUSINESS_AGE_OUTSIDE
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.BUSINESS_AGE_WITHIN
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.NOT_COMPARABLE
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.PRE_STARTUP_ONLY
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.PROFILE_MISSING
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.REGION_MATCH
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionReason.REGION_MISMATCH
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionResult.MET
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionResult.NOT_MET
import ai.govbiz.core.supportprogram.domain.SupportProgramConditionResult.UNKNOWN
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class SupportProgramConditionCheckTest {
    private val referenceDate = LocalDate.of(2026, 10, 1)

    @ParameterizedTest
    @CsvSource(
        "서울특별시, 서울", "서울, 서울", " 경기도 , 경기", "세종특별자치시, 세종", "강원특별자치도, 강원", "강원도, 강원",
        "전북특별자치도, 전북", "전라북도, 전북", "제주특별자치도, 제주", "제주도, 제주", "충청남도, 충남", "경상북도, 경북",
    )
    fun normalizesFullAndLegacyProvinceNamesToShortNames(raw: String, expected: String) {
        assertEquals(expected, SupportProgramConditionCheck.regionName(raw))
    }

    @Test
    fun unknownOrNationwideProfileRegionsHaveNoShortName() {
        for (raw in listOf(null, "", "서울시 강남구", "Seoul", "전국")) {
            assertNull(SupportProgramConditionCheck.regionName(raw), "$raw")
        }
    }

    @Test
    fun requiredRegionComparesTheProfileFullNameWithShortConditionRegions() {
        assertVerdict(MET, REGION_MATCH, condition(REQUIRED, REGION, regions = listOf("서울", "경기")), region = "서울특별시")
        assertVerdict(MET, REGION_MATCH, condition(REQUIRED, REGION, regions = listOf("경기")), region = "경기")
        assertVerdict(NOT_MET, REGION_MISMATCH, condition(REQUIRED, REGION, regions = listOf("서울", "경기")), region = "부산광역시")
        assertVerdict(MET, REGION_MATCH, condition(REQUIRED, REGION, regions = listOf("전북")), region = "전라북도")
    }

    @Test
    fun nationwideRegionIncludesEveryProvince() {
        for (region in listOf("서울특별시", "제주특별자치도", "경상남도")) {
            assertVerdict(MET, REGION_MATCH, condition(REQUIRED, REGION, regions = listOf("전국")), region = region)
        }
    }

    @Test
    fun regionWithoutStructuredValuesOrWithoutAUsableProfileRegionIsUnknown() {
        assertVerdict(UNKNOWN, NOT_COMPARABLE, condition(REQUIRED, REGION, regions = null), region = "서울특별시")
        assertVerdict(UNKNOWN, NOT_COMPARABLE, condition(REQUIRED, REGION, regions = emptyList()), region = "서울특별시")
        assertVerdict(UNKNOWN, PROFILE_MISSING, condition(REQUIRED, REGION, regions = listOf("서울")), region = null)
        assertVerdict(UNKNOWN, PROFILE_MISSING, condition(REQUIRED, REGION, regions = listOf("서울")), region = "서울시 강남구")
        // 조건 지역을 알 수 없으면 일부만 보고 불일치로 단정하지 않습니다.
        assertVerdict(UNKNOWN, NOT_COMPARABLE, condition(REQUIRED, REGION, regions = listOf("서울", "강남구")), region = "부산광역시")
    }

    @Test
    fun excludedRegionIsNotMetOnlyWhenTheCompanyIsInsideTheExcludedGroup() {
        assertVerdict(NOT_MET, REGION_MATCH, condition(EXCLUDED, REGION, regions = listOf("서울")), region = "서울특별시")
        assertVerdict(MET, REGION_MISMATCH, condition(EXCLUDED, REGION, regions = listOf("서울")), region = "부산광역시")
        assertVerdict(UNKNOWN, PROFILE_MISSING, condition(EXCLUDED, REGION, regions = listOf("서울")), region = null)
    }

    @Test
    fun preferredRegionIsMetWhenInsideAndNotMetWhenOutside() {
        assertVerdict(MET, REGION_MATCH, condition(PREFERRED, REGION, regions = listOf("충남")), region = "충청남도")
        assertVerdict(NOT_MET, REGION_MISMATCH, condition(PREFERRED, REGION, regions = listOf("충남")), region = "충청북도")
    }

    @Test
    fun maxYearsUsesEveryPossibleFoundingDateOfTheFoundedYear() {
        // 기준일 2026-10-01에서 7년 → 2019-10-01 이후 설립이어야 합니다.
        val withinSeven = condition(REQUIRED, BUSINESS_AGE, maxYears = 7.0)
        assertVerdict(MET, BUSINESS_AGE_WITHIN, withinSeven, foundedYear = 2020)
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, withinSeven, foundedYear = 2019)
        assertVerdict(NOT_MET, BUSINESS_AGE_OUTSIDE, withinSeven, foundedYear = 2018)
        // 설립연도 안의 날짜가 모두 경계일 이후면(경계일 포함) 판정합니다.
        assertVerdict(MET, BUSINESS_AGE_WITHIN, withinSeven, foundedYear = 2019, referenceDate = LocalDate.of(2026, 1, 1))
        assertVerdict(MET, BUSINESS_AGE_WITHIN, withinSeven, foundedYear = 2026)
    }

    @Test
    fun fractionalYearsAreRoundedToMonthsBeforeComparing() {
        val julyFirst = LocalDate.of(2026, 7, 1)
        // 3.5년 = 42개월 → 2023-01-01 이후 설립
        assertVerdict(MET, BUSINESS_AGE_WITHIN, condition(REQUIRED, BUSINESS_AGE, maxYears = 3.5), foundedYear = 2023, referenceDate = julyFirst)
        // 3.4년 = 40.8개월 → 41개월 → 2023-02-01 이후 설립이라 2023년 설립은 경계입니다.
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, condition(REQUIRED, BUSINESS_AGE, maxYears = 3.4), foundedYear = 2023, referenceDate = julyFirst)
        // 7.5년 = 90개월 → 2019-04-01
        val sevenAndAHalf = condition(REQUIRED, BUSINESS_AGE, maxYears = 7.5)
        assertVerdict(MET, BUSINESS_AGE_WITHIN, sevenAndAHalf, foundedYear = 2020)
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, sevenAndAHalf, foundedYear = 2019)
        assertVerdict(NOT_MET, BUSINESS_AGE_OUTSIDE, sevenAndAHalf, foundedYear = 2018)
    }

    @Test
    fun minYearsRequiresFoundingOnOrBeforeTheBound() {
        // 2년 이상 → 2024-10-01 이전 설립
        val atLeastTwo = condition(REQUIRED, BUSINESS_AGE, minYears = 2.0)
        assertVerdict(MET, BUSINESS_AGE_WITHIN, atLeastTwo, foundedYear = 2023)
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, atLeastTwo, foundedYear = 2024)
        assertVerdict(NOT_MET, BUSINESS_AGE_OUTSIDE, atLeastTwo, foundedYear = 2025)
        assertVerdict(MET, BUSINESS_AGE_WITHIN, atLeastTwo, foundedYear = 2024, referenceDate = LocalDate.of(2026, 12, 31))
    }

    @Test
    fun minAndMaxYearsCombineWithFalseBeforeUnknown() {
        // 1년 이상 3년 이내 → 2023-10-01 ~ 2025-10-01 설립
        val oneToThree = condition(REQUIRED, BUSINESS_AGE, minYears = 1.0, maxYears = 3.0)
        assertVerdict(MET, BUSINESS_AGE_WITHIN, oneToThree, foundedYear = 2024)
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, oneToThree, foundedYear = 2025)
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, oneToThree, foundedYear = 2023)
        assertVerdict(NOT_MET, BUSINESS_AGE_OUTSIDE, oneToThree, foundedYear = 2022)
        assertVerdict(NOT_MET, BUSINESS_AGE_OUTSIDE, oneToThree, foundedYear = 2026)
    }

    @Test
    fun preStartupOnlyConditionIsNeverMatchedByARegisteredCompany() {
        assertVerdict(NOT_MET, PRE_STARTUP_ONLY, condition(REQUIRED, BUSINESS_AGE, maxYears = 0.0), foundedYear = 2026)
        assertVerdict(NOT_MET, PRE_STARTUP_ONLY, condition(REQUIRED, BUSINESS_AGE, maxYears = 0.0), foundedYear = null)
        assertVerdict(MET, PRE_STARTUP_ONLY, condition(EXCLUDED, BUSINESS_AGE, maxYears = 0.0), foundedYear = 2026)
        assertVerdict(NOT_MET, PRE_STARTUP_ONLY, condition(PREFERRED, BUSINESS_AGE, minYears = 0.0, maxYears = 0.0), foundedYear = 2026)
    }

    @Test
    fun businessAgeWithoutValuesOrFoundedYearIsUnknown() {
        assertVerdict(UNKNOWN, NOT_COMPARABLE, condition(REQUIRED, BUSINESS_AGE), foundedYear = 2020)
        assertVerdict(UNKNOWN, PROFILE_MISSING, condition(REQUIRED, BUSINESS_AGE, maxYears = 7.0), foundedYear = null)
    }

    @Test
    fun excludedAndPreferredBusinessAgeFollowTheKindMapping() {
        // "업력 7년 초과 기업 제외"를 values로 7년 이내 집단이 아니라 7년 초과 집단(minYears=7)으로 표현한 경우
        val excludedOlder = condition(EXCLUDED, BUSINESS_AGE, minYears = 7.0)
        assertVerdict(NOT_MET, BUSINESS_AGE_WITHIN, excludedOlder, foundedYear = 2010)
        assertVerdict(MET, BUSINESS_AGE_OUTSIDE, excludedOlder, foundedYear = 2022)
        assertVerdict(UNKNOWN, BOUNDARY_YEAR, excludedOlder, foundedYear = 2019)
        val preferredYoung = condition(PREFERRED, BUSINESS_AGE, maxYears = 3.0)
        assertVerdict(MET, BUSINESS_AGE_WITHIN, preferredYoung, foundedYear = 2024)
        assertVerdict(NOT_MET, BUSINESS_AGE_OUTSIDE, preferredYoung, foundedYear = 2015)
    }

    @Test
    fun founderAgeNeedsAProfileValueThatDoesNotExist() {
        assertVerdict(UNKNOWN, PROFILE_MISSING, condition(REQUIRED, FOUNDER_AGE, minAge = 19, maxAge = 39))
        assertVerdict(UNKNOWN, NOT_COMPARABLE, condition(REQUIRED, FOUNDER_AGE))
    }

    @ParameterizedTest
    @EnumSource(names = ["INDUSTRY", "COMPANY_SIZE", "LEGAL_FORM", "CERTIFICATION", "OTHER"])
    fun freeTextCategoriesAreNeverGuessed(category: SupportProgramAnalysisConditionCategory) {
        // 다른 분류 값이 섞여 와도 해당 분류가 아니면 비교하지 않습니다.
        val values = SupportProgramAnalysisConditionValues(regions = listOf("서울"), maxYears = 7.0)
        for (kind in SupportProgramAnalysisConditionKind.entries) {
            val condition = SupportProgramAnalysisCondition(kind, category, "제조업 중소기업", values, EVIDENCE)
            assertVerdict(UNKNOWN, NOT_COMPARABLE, condition, region = "서울특별시", foundedYear = 2024)
        }
    }

    @Test
    fun overallCountsOnlyRequiredAndExcludedConditions() {
        val seoul = condition(REQUIRED, REGION, regions = listOf("서울"))
        val busan = condition(REQUIRED, REGION, regions = listOf("부산"))
        val excludedBusan = condition(EXCLUDED, REGION, regions = listOf("부산"))
        val preferredBusan = condition(PREFERRED, REGION, regions = listOf("부산"))
        val industry = condition(REQUIRED, INDUSTRY)
        val preferredIndustry = condition(PREFERRED, INDUSTRY)

        assertEquals(MET, overall(seoul, excludedBusan, preferredBusan))
        assertEquals(NOT_MET, overall(seoul, busan, industry))
        assertEquals(NOT_MET, overall(industry, condition(EXCLUDED, REGION, regions = listOf("서울"))))
        assertEquals(UNKNOWN, overall(seoul, industry, preferredBusan))
        // 우대 조건만 있거나 조건이 없으면 신청 가능 여부를 말할 수 없습니다.
        assertEquals(UNKNOWN, overall(preferredBusan, preferredIndustry))
        assertEquals(UNKNOWN, overall())
    }

    @Test
    fun verdictsKeepTheInputConditionOrder() {
        val conditions = listOf(
            condition(PREFERRED, INDUSTRY),
            condition(REQUIRED, REGION, regions = listOf("부산")),
            condition(EXCLUDED, BUSINESS_AGE, maxYears = 0.0),
            condition(REQUIRED, BUSINESS_AGE, maxYears = 7.0),
            condition(REQUIRED, FOUNDER_AGE, maxAge = 39),
        )

        val evaluation = SupportProgramConditionCheck.check(conditions, profile("서울특별시", 2021), referenceDate)

        assertEquals(
            listOf(
                SupportProgramConditionVerdict(UNKNOWN, NOT_COMPARABLE),
                SupportProgramConditionVerdict(NOT_MET, REGION_MISMATCH),
                SupportProgramConditionVerdict(MET, PRE_STARTUP_ONLY),
                SupportProgramConditionVerdict(MET, BUSINESS_AGE_WITHIN),
                SupportProgramConditionVerdict(UNKNOWN, PROFILE_MISSING),
            ),
            evaluation.conditions,
        )
        assertEquals(NOT_MET, evaluation.overall)
    }

    private fun overall(vararg conditions: SupportProgramAnalysisCondition) =
        SupportProgramConditionCheck.check(conditions.toList(), profile("서울특별시", 2021), referenceDate).overall

    private fun assertVerdict(
        result: SupportProgramConditionResult,
        reason: SupportProgramConditionReason,
        condition: SupportProgramAnalysisCondition,
        region: String? = "서울특별시",
        foundedYear: Int? = 2021,
        referenceDate: LocalDate = this.referenceDate,
    ) {
        val evaluation = SupportProgramConditionCheck.check(listOf(condition), profile(region, foundedYear), referenceDate)
        assertEquals(listOf(SupportProgramConditionVerdict(result, reason)), evaluation.conditions, "$condition / $region / $foundedYear")
    }

    private fun profile(region: String?, foundedYear: Int?) = SupportProgramApplicantProfile(region, foundedYear)

    private fun condition(
        kind: SupportProgramAnalysisConditionKind,
        category: SupportProgramAnalysisConditionCategory,
        regions: List<String>? = null,
        minYears: Double? = null,
        maxYears: Double? = null,
        minAge: Int? = null,
        maxAge: Int? = null,
    ) = SupportProgramAnalysisCondition(
        kind = kind,
        category = category,
        text = "조건",
        values = SupportProgramAnalysisConditionValues(regions, minYears, maxYears, minAge, maxAge),
        evidence = EVIDENCE,
    )

    private companion object {
        val EVIDENCE = SupportProgramAnalysisEvidence(SupportProgramAnalysisEvidenceField.TARGET_DESCRIPTION, "공고 원문")
    }
}
