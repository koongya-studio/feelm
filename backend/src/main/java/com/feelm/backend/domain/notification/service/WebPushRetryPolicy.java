package com.feelm.backend.domain.notification.service;

import java.time.Duration;

final class WebPushRetryPolicy {

    private WebPushRetryPolicy() {}

    static Duration delay(Duration initial, Duration maximum, int attemptCount) {
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 30);
        try {
            Duration delay = initial.multipliedBy(multiplier);
            return delay.compareTo(maximum) > 0 ? maximum : delay;
        } catch (ArithmeticException exception) {
            return maximum;
        }
    }
}
