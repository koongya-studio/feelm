package com.feelm.backend.infrastructure.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** 아웃박스 발행 실패 횟수로 지수 백오프와 ±20% 지터를 적용한 다음 재시도 간격을 계산한다. */
public final class OutboxRetryPolicy {

    private static final double JITTER_RANGE = 0.2;

    private final Duration maximumDelay;
    private final DoubleSupplier random;

    public OutboxRetryPolicy(Duration maximumDelay, DoubleSupplier random) {
        this.maximumDelay = Objects.requireNonNull(maximumDelay);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * 1초부터 두 배씩 증가하는 간격을 반환한다.
     *
     * <p>동시 재시도 집중을 피하도록 지터를 적용하되 결과는 {@code maximumDelay}를 넘지 않는다.
     */
    public Duration delay(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        long exponent = Math.min(attemptCount - 1L, 30L);
        long baseMillis = Math.min(1000L << exponent, maximumDelay.toMillis());
        double jitter = 1.0 - JITTER_RANGE + (2.0 * JITTER_RANGE * random.getAsDouble());
        long jitteredMillis = Math.round(baseMillis * jitter);
        return Duration.ofMillis(Math.min(jitteredMillis, maximumDelay.toMillis()));
    }
}
