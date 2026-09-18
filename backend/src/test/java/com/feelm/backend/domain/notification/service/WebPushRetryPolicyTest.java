package com.feelm.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebPushRetryPolicyTest {

    @Test
    void doublesFromTheFirstAttemptAndCapsAtTheConfiguredMaximum() {
        Duration initial = Duration.ofMinutes(1);
        Duration maximum = Duration.ofMinutes(5);

        assertThat(WebPushRetryPolicy.delay(initial, maximum, 1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(WebPushRetryPolicy.delay(initial, maximum, 2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(WebPushRetryPolicy.delay(initial, maximum, 3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(WebPushRetryPolicy.delay(initial, maximum, 4)).isEqualTo(maximum);
        assertThat(WebPushRetryPolicy.delay(initial, maximum, 20)).isEqualTo(maximum);
    }
}
