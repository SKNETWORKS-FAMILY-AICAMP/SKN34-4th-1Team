package ai.govbiz.core.supportprogram.service.detail

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.repository.SupportProgramAnalysisRepository
import ai.govbiz.core.supportprogram.repository.SupportProgramRepository
import ai.govbiz.core.supportprogram.service.detail.exception.SupportProgramNotFoundException
import ai.govbiz.core.supportprogram.service.dto.SupportProgramDetailResult
import org.springframework.stereotype.Service

/** 제공처 원본 식별자로 현재 노출 중인 지원사업 상세를 조회합니다. */
@Service
class SupportProgramDetailService(
    private val supportProgramRepository: SupportProgramRepository,
    private val analysisRepository: SupportProgramAnalysisRepository,
) {
    fun get(sourceCode: String, sourceProgramId: String): SupportProgram =
        supportProgramRepository
            .findPresentBySourceAndProgramId(sourceCode, sourceProgramId)
            ?.program
            ?: throw SupportProgramNotFoundException()

    /** 상세 화면용으로 공고와 현재 공고 내용 기준의 AI 분석을 함께 조회합니다. */
    fun getDetail(sourceCode: String, sourceProgramId: String): SupportProgramDetailResult {
        val program = get(sourceCode, sourceProgramId)
        return SupportProgramDetailResult(program, analysisRepository.findCurrent(program.sourceCode, program.id))
    }
}
