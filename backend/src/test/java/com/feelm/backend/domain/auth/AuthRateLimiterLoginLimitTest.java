package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 로그인 시도 제한이 상수가 아니라 설정값을 쓰는지 확인한다(S15P21E106-311).
 *
 * <p>계약 테스트({@code LoginContractTest})는 테스트 설정이 운영 기본값과 같은 5회/10분이라, 값이 설정에서 오는지 상수로 남았는지 구분하지 못한다.
 * 그래서 기본값과 다른 한도·창을 넣고 Redis 명령을 대역으로 확인한다. 카운터 자체의 원자성과 실제 429 응답은 계약 테스트가 검증한다.
 */
@DisplayName("로그인 시도 제한 — 설정값 사용")
class AuthRateLimiterLoginLimitTest {

    @Test
    @DisplayName("설정한 한도까지만 대조를 허용하고 설정한 창 길이로 만료를 건다")
    void configuredLimitAndWindowAreUsed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                new AuthRateLimiter(
                        redisTemplate,
                        new MailRateLimitProperties(Duration.ofMinutes(1), List.of(), List.of()),
                        new LoginRateLimitProperties(2, Duration.ofSeconds(30)),
                        new ClientIpProperties(32, 64));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L, 2L, 3L);

        boolean first = rateLimiter.tryAcquireLoginAttempt("user@example.com");
        boolean second = rateLimiter.tryAcquireLoginAttempt("user@example.com");
        boolean third = rateLimiter.tryAcquireLoginAttempt("user@example.com");

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        // 한도가 상수 5로 남아 있으면 세 번째 시도도 통과한다.
        assertThat(third).isFalse();
        // 창 길이가 상수 10분으로 남아 있으면 만료 인자가 600000이다.
        verify(redisTemplate, times(3))
                .execute(
                        any(RedisScript.class),
                        eq(List.of("auth:login-fail:" + Sha256Hasher.hex("user@example.com"))),
                        eq("30000"));
    }

    /** 스크립트가 값을 주지 못하면 통과시키지 않는다(fail-closed). 설정 이관으로 이 성질이 바뀌지 않았는지 함께 본다. */
    @Test
    @DisplayName("카운터를 확인할 수 없으면 시도를 허용하지 않는다")
    void missingCounterIsNotAllowed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                new AuthRateLimiter(
                        redisTemplate,
                        new MailRateLimitProperties(Duration.ofMinutes(1), List.of(), List.of()),
                        new LoginRateLimitProperties(5, Duration.ofMinutes(10)),
                        new ClientIpProperties(32, 64));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(null);

        assertThat(rateLimiter.tryAcquireLoginAttempt("user@example.com")).isFalse();
    }
}
