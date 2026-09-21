package com.feelm.backend.domain.recommendation.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 프리페치 전용 실행기다(S15P21E106-708).
 *
 * <p>이유 보강({@link RecommendationReasonExecutorConfig})과 풀을 나눈다. 이유 보강은 LLM 호출이라 지연이 길고, 같은 큐를 쓰면
 * 프리페치가 그 뒤에 밀려 "재생성을 사용자 요청의 임계 경로에서 뺀다"는 목적이 무의미해진다.
 */
@Configuration
@EnableConfigurationProperties(RecommendationPrefetchProperties.class)
class RecommendationPrefetchExecutorConfig {

    @Bean
    ThreadPoolTaskExecutor recommendationPrefetchExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("recommendation-prefetch-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
