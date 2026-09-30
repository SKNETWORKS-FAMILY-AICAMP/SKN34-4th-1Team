class SupportProgramAnalysisError(Exception):
    """모델 장애, 거절 또는 구조화 출력 계약 위반으로 분석 결과를 만들지 못한 오류."""


class SupportProgramAnalysisTimeoutError(SupportProgramAnalysisError):
    """분석 모델·HTTP 또는 전체 실행 제한 시간이 소진된 오류."""
