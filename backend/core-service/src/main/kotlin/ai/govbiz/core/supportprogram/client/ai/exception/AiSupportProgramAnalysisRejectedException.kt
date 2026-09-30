package ai.govbiz.core.supportprogram.client.ai.exception

/** AI Service가 공고 분석 요청 자체를 검증 오류(HTTP 422)로 거부한 경우입니다. 같은 입력으로 재시도하지 않습니다. */
class AiSupportProgramAnalysisRejectedException : RuntimeException("AI analysis rejected the request")
