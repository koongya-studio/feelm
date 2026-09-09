package com.feelm.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("아웃박스 재시도 정책")
class OutboxRetryPolicyTest {

    @Test
    @DisplayName("재시도 간격을 지수 증가시키고 30초로 제한한다")
    void capsExponentialDelay() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(Duration.ofSeconds(30), () -> 0.5);

        assertThat(policy.delay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delay(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.delay(20)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("재시도 간격에 최대 20퍼센트 편차를 적용한다")
    void appliesJitter() {
        OutboxRetryPolicy minimum = new OutboxRetryPolicy(Duration.ofSeconds(30), () -> 0.0);
        OutboxRetryPolicy maximum = new OutboxRetryPolicy(Duration.ofSeconds(30), () -> 1.0);

        assertThat(minimum.delay(4)).isEqualTo(Duration.ofMillis(6400));
        assertThat(maximum.delay(4)).isEqualTo(Duration.ofMillis(9600));
    }

    @Test
    @DisplayName("지터를 적용해도 최대 재시도 간격을 넘지 않는다")
    void capsJitteredDelay() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(Duration.ofSeconds(30), () -> 1.0);

        assertThat(policy.delay(20)).isEqualTo(Duration.ofSeconds(30));
    }
}
