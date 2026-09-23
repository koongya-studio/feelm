package com.feelm.backend.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * 테스트 context가 재개될 때도 Kafka listener의 {@code autoStartup} 설정을 따르게 한다.
 *
 * <p>Spring TestContext는 기본값({@code spring.test.context.cache.pause=ON_CONTEXT_SWITCH})에서 다른
 * context로 넘어갈 때 캐시한 context를 {@code pause()}하고, 다시 쓸 때 {@code restart()}한다. Spring Kafka registry는
 * 기본값({@code alwaysStartAfterRefresh=true})이면 refresh 이후의 시작에서 {@code autoStartup=false}
 * container까지 시작하므로, 테스트 설정으로 끈 consumer가 localhost Kafka에 접속한다. 운영 context는 재개되지 않아 이 설정은 테스트
 * classpath의 {@code AutoConfiguration.imports}로만 등록한다.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaListenerEndpointRegistry.class)
public class KafkaListenerTestStartupAutoConfiguration {

    @Bean
    static BeanPostProcessor kafkaListenerRegistryRespectsAutoStartup() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaListenerEndpointRegistry registry) {
                    registry.setAlwaysStartAfterRefresh(false);
                }
                return bean;
            }
        };
    }
}
