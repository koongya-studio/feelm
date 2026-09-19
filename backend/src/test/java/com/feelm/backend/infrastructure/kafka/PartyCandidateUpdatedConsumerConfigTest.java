package com.feelm.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

class PartyCandidateUpdatedConsumerConfigTest {
    @Test
    void keepsDatabaseFailuresUnacknowledgedInsteadOfRecoveringThem() {
        var config = new PartyCandidateUpdatedConsumerConfig();
        var properties =
                new KafkaOutboxProperties(
                        "localhost:9092", 1, Duration.ofSeconds(30), Duration.ofDays(7));
        var consumerFactory = config.partyCandidateUpdatedConsumerFactory(properties);
        var listenerFactory = config.partyCandidateUpdatedListenerFactory(consumerFactory);

        var container = listenerFactory.createContainer("feelm.party-candidate-updated.v1");

        assertThat(container.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);
        assertThat(((DefaultErrorHandler) container.getCommonErrorHandler()).isAckAfterHandle())
                .isFalse();
    }
}
