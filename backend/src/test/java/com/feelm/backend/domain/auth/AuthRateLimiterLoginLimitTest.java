package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.auth.LoginRateLimitProperties.Quota;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 로그인 시도 제한이 상수가 아니라 설정값을 쓰는지, IP 축을 이메일 축보다 먼저 보는지 확인한다(S15P21E106-311·-312).
 *
 * <p>계약 테스트({@code LoginContractTest})는 테스트 설정이 운영 기본값과 같은 5회/10분이라, 값이 설정에서 오는지 상수로 남았는지 구분하지 못한다.
 * 그래서 기본값과 다른 한도·창을 넣고 Redis 명령을 대역으로 확인한다. 카운터 자체의 원자성과 실제 429 응답은 계약 테스트가 검증한다.
 *
 * <p>평가 순서와 "앞 축에서 막히면 뒤 축의 카운터를 올리지 않는다"는 성질도 여기서 본다. 계약 테스트는 응답 코드만 보므로 두 축의 순서를 뒤집어도 통과한다.
 */
@DisplayName("로그인 시도 제한 — 설정값 사용")
class AuthRateLimiterLoginLimitTest {

    private static final String CLIENT_IP = "203.0.113.7";

    /** 운영·테스트 기본값과 같은 집계 단위(S15P21E106-288). */
    private static final ClientIpProperties PREFIXES = new ClientIpProperties(32, 64);

    @Test
    @DisplayName("설정한 한도까지만 대조를 허용하고 설정한 창 길이로 만료를 건다")
    void configuredLimitAndWindowAreUsed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                new AuthRateLimiter(
                        redisTemplate,
                        new MailRateLimitProperties(Duration.ofMinutes(1), List.of()),
                        new LoginRateLimitProperties(2, Duration.ofSeconds(30), List.of()),
                        new SignupRateLimitProperties(List.of()),
                        new ClientIpProperties(32, 64));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L, 2L, 3L);

        boolean first = rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP);
        boolean second = rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP);
        boolean third = rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP);

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
                        new MailRateLimitProperties(Duration.ofMinutes(1), List.of()),
                        new LoginRateLimitProperties(5, Duration.ofMinutes(10), List.of()),
                        new SignupRateLimitProperties(List.of()),
                        new ClientIpProperties(32, 64));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(null);

        assertThat(rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP)).isFalse();
    }

    /**
     * IP 축이 이메일 축보다 먼저 평가되는지, 그리고 IP에서 거절된 요청이 이메일 카운터를 올리지 않는지 본다(S15P21E106-312).
     *
     * <p>이메일 카운터를 올려 버리면 공유 NAT에서 남의 IP 예산 때문에 429를 받은 사용자가 자기 계정까지 잠기게 된다.
     */
    @Test
    @DisplayName("IP 한도를 넘긴 시도는 이메일 카운터를 올리지 않는다")
    void ipBudgetIsEvaluatedBeforeEmailAxis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                loginLimiter(redisTemplate, 5, List.of(new Quota(1, Duration.ofMinutes(10))));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any())).thenReturn(2L);

        assertThat(rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP)).isFalse();

        verify(redisTemplate)
                .execute(any(RedisScript.class), eq(List.of(loginIpKey(600))), eq("600000"));
        verify(redisTemplate, never())
                .execute(any(RedisScript.class), eq(List.of(loginFailureKey())), any());
    }

    /** 목록의 창을 하나만 평가하는 구현을 막는다. 창 길이가 키와 만료 인자에 모두 들어가야 설정을 바꿔도 옛 창의 카운터가 섞이지 않는다. */
    @Test
    @DisplayName("IP 목록의 창을 모두 적용하고 창 길이를 키에 담는다")
    void everyConfiguredIpWindowIsApplied() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                loginLimiter(
                        redisTemplate,
                        5,
                        List.of(
                                new Quota(3, Duration.ofMinutes(10)),
                                new Quota(4, Duration.ofHours(1))));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L, 5L);

        assertThat(rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP)).isFalse();

        verify(redisTemplate)
                .execute(any(RedisScript.class), eq(List.of(loginIpKey(600))), eq("600000"));
        verify(redisTemplate)
                .execute(any(RedisScript.class), eq(List.of(loginIpKey(3600))), eq("3600000"));
        verify(redisTemplate, never())
                .execute(any(RedisScript.class), eq(List.of(loginFailureKey())), any());
    }

    /**
     * 카운터를 확인할 수 없을 때의 처리가 설정된 한도와 독립인지 본다. 한도를 {@code Long.MAX_VALUE}로 둔 설정에서 "값을 받지 못했다"를 한도보다 큰
     * 값으로 표현하면 그 신호가 한도와 같아져 fail-closed가 fail-open으로 뒤집힌다.
     */
    @Test
    @DisplayName("IP 카운터를 확인할 수 없으면 한도가 아무리 커도 시도를 허용하지 않는다")
    void missingIpCounterIsNotAllowed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                loginLimiter(
                        redisTemplate,
                        5,
                        List.of(new Quota(Long.MAX_VALUE, Duration.ofMinutes(10))));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(null);

        assertThat(rateLimiter.tryAcquireLoginAttempt("user@example.com", CLIENT_IP)).isFalse();
    }

    /**
     * IP 축의 예산이 주소 하나가 아니라 집계 버킷의 것인지 본다(S15P21E106-288). 키가 IP 원문을 해시하면 같은 {@code /64}를 배정받은 IPv6
     * 클라이언트가 주소만 바꿔 로그인 예산을 새로 얻어, IP 축이 사실상 없는 것과 같아진다.
     */
    @Test
    @DisplayName("같은 프리픽스의 IPv6 주소는 로그인 IP 예산을 공유한다")
    void ipv6AddressesInSamePrefixShareBudget() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthRateLimiter rateLimiter =
                loginLimiter(redisTemplate, 5, List.of(new Quota(1, Duration.ofMinutes(10))));
        when(redisTemplate.<Long>execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L, 2L);

        boolean first = rateLimiter.tryAcquireLoginAttempt("user@example.com", "2001:db8::1");
        boolean second = rateLimiter.tryAcquireLoginAttempt("user@example.com", "2001:db8::2");

        assertThat(first).isTrue();
        // 주소를 바꾼 두 번째 시도가 같은 카운터를 올려 한도에 걸린다.
        assertThat(second).isFalse();
        verify(redisTemplate, times(2))
                .execute(
                        any(RedisScript.class),
                        eq(List.of(loginIpKey("2001:db8::", 600))),
                        eq("600000"));
    }

    private AuthRateLimiter loginLimiter(
            StringRedisTemplate redisTemplate, long attemptLimit, List<Quota> ipQuotas) {
        return new AuthRateLimiter(
                redisTemplate,
                new MailRateLimitProperties(Duration.ofMinutes(1), List.of()),
                new LoginRateLimitProperties(attemptLimit, Duration.ofMinutes(10), ipQuotas),
                new SignupRateLimitProperties(List.of()),
                PREFIXES);
    }

    private String loginIpKey(long windowSeconds) {
        return loginIpKey(CLIENT_IP, windowSeconds);
    }

    private String loginIpKey(String clientIp, long windowSeconds) {
        return "auth:login:ip:"
                + Sha256Hasher.hex(ClientIpBucket.of(clientIp, PREFIXES))
                + ":"
                + windowSeconds;
    }

    private String loginFailureKey() {
        return "auth:login-fail:" + Sha256Hasher.hex("user@example.com");
    }
}
