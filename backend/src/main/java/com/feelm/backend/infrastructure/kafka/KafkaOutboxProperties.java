package com.feelm.backend.infrastructure.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Kafka 연결과 아웃박스 relay의 조정 가능한 운영 설정이다. */
@ConfigurationProperties(prefix = "feelm.kafka.outbox")
public record KafkaOutboxProperties(
        @DefaultValue("localhost:9092") String bootstrapServers,
        @DefaultValue("100") int batchSize,
        @DefaultValue("30s") Duration maximumRetryDelay,
        @DefaultValue("7d") Duration publishedRetention) {

    public KafkaOutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }
}
