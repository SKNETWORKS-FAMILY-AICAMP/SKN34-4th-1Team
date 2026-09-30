package ai.govbiz.core.supportprogram.service.analysis.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportProgramAnalysisProperties::class)
class SupportProgramAnalysisConfig {
    /** 공고 분석 AI 호출이 공고 동기화·색인 scheduler 스레드를 점유하지 않게 전용 스레드를 씁니다. */
    @Bean
    @ConditionalOnProperty(name = ["app.support-program-analysis.enabled"], havingValue = "true")
    fun supportProgramAnalysisTaskScheduler() = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("support-program-analysis-")
    }
}
