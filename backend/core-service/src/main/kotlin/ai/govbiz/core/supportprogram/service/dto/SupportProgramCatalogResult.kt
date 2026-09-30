package ai.govbiz.core.supportprogram.service.dto

import ai.govbiz.core.supportprogram.domain.SupportProgram
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisSummary

/** 공개 목록 한 페이지입니다. analysisSummaries는 이 페이지 공고 중 현재 완료 분석이 있는 공고의 요약이며 키는 sourceQualifiedId입니다. */
data class SupportProgramCatalogResult(
    val programs: List<SupportProgram>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val regions: List<String>,
    val categories: List<String>,
    val startupStages: List<String> = emptyList(),
    val applicantTypes: List<String> = emptyList(),
    val founderAges: List<String> = emptyList(),
    val analysisSummaries: Map<String, SupportProgramAnalysisSummary> = emptyMap(),
)
