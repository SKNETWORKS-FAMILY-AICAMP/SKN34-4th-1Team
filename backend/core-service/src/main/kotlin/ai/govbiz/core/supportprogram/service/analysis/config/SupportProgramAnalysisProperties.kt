package ai.govbiz.core.supportprogram.service.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 공고 분석 Worker의 실행 여부와 비용 한도입니다. 유료 호출이므로 기본값은 비활성화입니다. */
@ConfigurationProperties(prefix = "app.support-program-analysis")
data class SupportProgramAnalysisProperties(
    val enabled: Boolean = false,
    val delayMs: Long = 30_000,
    val dailyLimit: Int = 200,
    val leaseSeconds: Long = 300,
    val maxAttempts: Int = 3,
) {
    init {
        require(delayMs in 1_000..86_400_000) { "app.support-program-analysis.delay-ms must be between 1000 and 86400000" }
        require(dailyLimit in 0..100_000) { "app.support-program-analysis.daily-limit must be between 0 and 100000" }
        require(leaseSeconds in 60..86_400) { "app.support-program-analysis.lease-seconds must be between 60 and 86400" }
        require(maxAttempts in 1..10) { "app.support-program-analysis.max-attempts must be between 1 and 10" }
    }
}
