package com.feelm.backend.infrastructure.kafka;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ADR-0018에서 확정한 Kafka Producer의 전달 보장과 제한 시간을 한 곳에서 구성한다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(KafkaOutboxProperties.class)
class KafkaEventSupportConfig {

    @Bean
    ProducerFactory<Long, String> kafkaProducerFactory(KafkaOutboxProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<Long, String> kafkaTemplate(ProducerFactory<Long, String> producerFactory) {
        KafkaTemplate<Long, String> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        // Spring Kafka 기본 실패 리스너는 payload 일부를 로그에 포함하므로 Relay의 식별 정보 로그만 사용한다.
        kafkaTemplate.setProducerListener(null);
        return kafkaTemplate;
    }

    @Bean
    Clock kafkaEventClock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> kafkaEventIdSupplier() {
        return UUID::randomUUID;
    }

    @Bean
    OutboxRetryPolicy outboxRetryPolicy(KafkaOutboxProperties properties) {
        return new OutboxRetryPolicy(properties.maximumRetryDelay(), Math::random);
    }
}
