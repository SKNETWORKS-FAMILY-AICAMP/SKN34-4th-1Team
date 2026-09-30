package ai.govbiz.core.supportprogram.service.dto

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysis

/** 상세 화면에 필요한 현재 공고와 그 공고 내용 기준의 AI 분석입니다. */
data class SupportProgramDetailResult(
    val program: SupportProgram,
    val analysis: SupportProgramAnalysis,
)
