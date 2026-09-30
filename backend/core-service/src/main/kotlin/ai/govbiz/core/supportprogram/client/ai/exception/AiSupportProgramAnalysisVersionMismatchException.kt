package ai.govbiz.core.supportprogram.client.ai.exception

/**
 * AI Service가 Core가 기대하는 분석 계약 버전과 다른 버전으로 응답한 경우입니다. 두 서비스의 배포가 어긋난 상태이므로
 * 같은 입력을 반복 호출하지 않습니다.
 */
class AiSupportProgramAnalysisVersionMismatchException : RuntimeException("AI analysis version does not match")
