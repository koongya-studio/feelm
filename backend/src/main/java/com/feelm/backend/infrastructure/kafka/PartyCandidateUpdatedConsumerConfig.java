package com.feelm.backend.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** 파티 후보 게시 완료는 DB 상태가 커밋된 뒤에만 offset을 확정한다. */
@Configuration
class PartyCandidateUpdatedConsumerConfig {
    @Bean
    ConsumerFactory<byte[], String> partyCandidateUpdatedConsumerFactory(
            KafkaOutboxProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<byte[], String> partyCandidateUpdatedListenerFactory(
            ConsumerFactory<byte[], String> partyCandidateUpdatedConsumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<byte[], String>();
        factory.setConsumerFactory(partyCandidateUpdatedConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // 기본 error handler는 제한 횟수 뒤 record를 recover하므로 DB 장애에도 offset이
        // 진행될 수 있다. 완료 상태를 잃지 않도록 성공할 때까지 같은 record를 재시도한다.
        var errorHandler =
                new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.setAckAfterHandle(false);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
