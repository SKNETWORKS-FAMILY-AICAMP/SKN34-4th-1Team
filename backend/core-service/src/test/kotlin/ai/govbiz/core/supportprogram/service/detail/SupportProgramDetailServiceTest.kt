package ai.govbiz.core.supportprogram.service.detail

import ai.govbiz.core.supportprogram.helper.SupportProgramTestHelper
import ai.govbiz.core.supportprogram.controller.dto.SupportProgramDetailResponse
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisStatus
import ai.govbiz.core.supportprogram.domain.SupportProgramApplicationRoute
import ai.govbiz.core.supportprogram.domain.SupportProgramApplicationRouteType
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.dto.SupportProgramDetailResult
import ai.govbiz.core.supportprogram.service.detail.exception.SupportProgramNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SupportProgramDetailServiceTest {

    @Mock
    private lateinit var supportProgramRepository: SupportProgramRepository

    @Mock
    private lateinit var analysisRepository: SupportProgramAnalysisRepository

    private lateinit var service: SupportProgramDetailService

    @BeforeEach
    fun setUp() {
        service = SupportProgramDetailService(supportProgramRepository, analysisRepository)
    }

    @Test
    fun findsTheCurrentProgramUsingTheExactSourceIdentityValues() {
        val catalogProgram = SupportProgramTestHelper.catalogProgram("PBLN_TEST")
        doReturn(catalogProgram).`when`(supportProgramRepository)
            .findPresentBySourceAndProgramId("BIZINFO", "PBLN_TEST")

        val result = service.get("BIZINFO", "PBLN_TEST")

        assertEquals(catalogProgram.program, result)
    }

    @Test
    fun detailResponseKeepsTheAnnouncementAndApplicationUrlsSeparate() {
        val program = SupportProgramTestHelper.catalogProgram("PBLN_TEST").program.copy(
            applicationRoute = SupportProgramApplicationRoute(
                "온라인 접수", "https://forms.gle/abc123", SupportProgramApplicationRouteType.GOOGLE_FORMS),
        )
        val response = SupportProgramDetailResponse.from(SupportProgramDetailResult(program, SupportProgramAnalysis.NOT_ANALYZED))
        assertEquals(program.sourceUrl, response.sourceUrl)
        assertEquals(program.applicationRoute.method, response.applicationRoute.method)
        assertEquals(program.applicationRoute.url, response.applicationRoute.url)
        assertEquals(program.applicationRoute.type.name, response.applicationRoute.type)
        assertEquals(SupportProgramAnalysisStatus.NOT_ANALYZED, response.analysis.status)
        assertEquals(emptyList<Any>(), response.analysis.conditions)
    }

    @Test
    fun detailIncludesTheAnalysisReadByTheCompositeProgramIdentity() {
        val catalogProgram = SupportProgramTestHelper.catalogProgram("PBLN_TEST")
        doReturn(catalogProgram).`when`(supportProgramRepository)
            .findPresentBySourceAndProgramId("BIZINFO", "PBLN_TEST")
        doReturn(SupportProgramAnalysis.FAILED).`when`(analysisRepository).findCurrent("BIZINFO", "PBLN_TEST")

        val result = service.getDetail("BIZINFO", "PBLN_TEST")

        assertEquals(SupportProgramDetailResult(catalogProgram.program, SupportProgramAnalysis.FAILED), result)
    }

    @Test
    fun missingProgramDoesNotReadAnalysis() {
        assertThrows(SupportProgramNotFoundException::class.java) { service.getDetail("BIZINFO", "PBLN_MISSING") }
        verifyNoInteractions(analysisRepository)
    }

    @Test
    fun doesNotSilentlyChangeSourceIdentityValuesBeforeLookingThemUp() {
        assertThrows(SupportProgramNotFoundException::class.java) {
            service.get(" BIZINFO ", " PBLN_TEST ")
        }

        verify(supportProgramRepository)
            .findPresentBySourceAndProgramId(" BIZINFO ", " PBLN_TEST ")
    }

    @Test
    fun throwsNotFoundWhenTheCurrentProgramDoesNotExist() {
        assertThrows(SupportProgramNotFoundException::class.java) {
            service.get("BIZINFO", "PBLN_MISSING")
        }
    }
}
