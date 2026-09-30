package ai.govbiz.core.supportprogram.service.analysis

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 기존 Core 프로세스의 scheduler로 공고를 하나씩 분석합니다. 유료 호출이므로 명시적으로 켠 환경에서만 실행합니다. */
@Component
@ConditionalOnProperty(name = ["app.support-program-analysis.enabled"], havingValue = "true")
class SupportProgramAnalysisWorker(private val service: SupportProgramAnalysisService) {
    @Scheduled(
        fixedDelayString = "\${app.support-program-analysis.delay-ms:30000}",
        scheduler = "supportProgramAnalysisTaskScheduler",
    )
    fun run() {
        try {
            service.runNext()
        } catch (exception: RuntimeException) {
            // 예상하지 못한 오류는 공고 원문이 섞일 수 있는 메시지 없이 종류만 남깁니다. 실행권 만료 후 다시 선점됩니다.
            logger.error("support_program_analysis outcome=UNEXPECTED_ERROR error={}", exception.javaClass.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SupportProgramAnalysisWorker::class.java)
    }
}
