package ai.govbiz.core.supportprogram.client.ai

import ai.govbiz.core._common.exception.AiServiceCallException
import ai.govbiz.core._common.helper.executeAiServiceCall
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisPayload
import ai.govbiz.core.supportprogram.client.ai.dto.AiSupportProgramAnalysisRequest
import ai.govbiz.core.supportprogram.client.ai.exception.AiSupportProgramAnalysisRejectedException
import ai.govbiz.core.supportprogram.client.ai.mapper.AiSupportProgramAnalysisMapper
import ai.govbiz.core.supportprogram.domain.SupportProgramAnalysisOutput
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * AI Service 공고 분석 HTTP 계약을 호출하고 검증된 분석 결과로 변환합니다. 저장은 수행하지 않습니다.
 * AI Service 실행 한도(100초)보다 긴 읽기 시간 제한을 가진 전용 RestClient를 사용합니다.
 */
@Component
class AiSupportProgramAnalysisClient(
    @param:Qualifier("aiSupportProgramAnalysisRestClient") private val restClient: RestClient,
) {
    fun analyze(request: AiSupportProgramAnalysisRequest): SupportProgramAnalysisOutput {
        val payload = executeAiServiceCall {
            restClient.post()
                .uri("/internal/v1/support-program-analyses/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(
                    { it.value() != HttpStatus.OK.value() },
                    { _, response ->
                        when (val status = response.statusCode.value()) {
                            HttpStatus.NO_CONTENT.value() ->
                                throw AiServiceCallException.invalidResponse("AI analysis response was empty", null)
                            HttpStatus.SERVICE_UNAVAILABLE.value() -> throw AiServiceCallException.unavailable(null)
                            HttpStatus.REQUEST_TIMEOUT.value(), HttpStatus.GATEWAY_TIMEOUT.value() ->
                                throw AiServiceCallException.timeout(null)
                            HttpStatus.UNPROCESSABLE_CONTENT.value() -> throw AiSupportProgramAnalysisRejectedException()
                            else -> throw AiServiceCallException.upstreamError("AI analysis returned HTTP $status", null)
                        }
                    },
                )
                .toEntity(AiSupportProgramAnalysisPayload::class.java)
                .body
                ?: throw AiServiceCallException.invalidResponse("AI analysis response was empty", null)
        }
        return AiSupportProgramAnalysisMapper.toOutput(payload, request)
    }
}
