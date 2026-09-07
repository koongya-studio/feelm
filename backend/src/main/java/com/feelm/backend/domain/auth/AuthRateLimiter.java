package com.feelm.backend.domain.auth;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 인증 메일 재발송 간격과 로그인 연속 실패를 Redis로 제한한다(플랜 결정 4).
 *
 * <p>키에는 이메일 원문 대신 해시를 쓴다. Redis 키 목록만으로 어떤 이메일이 서비스에 있는지 알 수 있으면 안 되기 때문이다. 네임스페이스는 Refresh Token이
 * 쓰는 {@code auth:}를 이어 사용한다.
 *
 * <p>카운터는 로그인 실패 응답과 함께 남아야 하므로 DB 트랜잭션과 무관하게 즉시 반영된다. Redis가 응답하지 않으면 예외가 그대로 올라가 인증 요청은 500으로
 * 실패한다(fail-closed). 로그인 성공 자체가 Refresh Token을 Redis에 저장해야 하므로 리미터만 열어 두어도 얻는 것이 없고, 재발송 제한을 열면 장애
 * 중 메일 폭탄이 가능해지기 때문이다. 장애 시 정책을 바꾸려면 후속 업무에서 정한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimiter {

    private static final String RESEND_KEY_PREFIX = "auth:email-resend:";
    private static final String LOGIN_FAILURE_KEY_PREFIX = "auth:login-fail:";

    /**
     * 실패 횟수를 올리고 만료를 보장하는 스크립트. {@code INCR}와 {@code PEXPIRE}를 따로 보내면 그 사이에 연결이 끊겼을 때 TTL 없는 카운터가
     * 남아 해당 이메일이 무기한 429가 된다. 첫 실패이거나(카운터가 1) 어떤 이유로든 TTL이 없으면 만료를 다시 건다.
     */
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count",
                    Long.class);

    /** 인증 메일 재발송 최소 간격. */
    private static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

    /** 로그인 연속 실패를 세는 기간. */
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(10);

    /** 이 횟수까지는 401로 응답하고, 도달한 뒤의 요청은 429로 막는다(플랜 결정 14). */
    private static final long LOGIN_FAILURE_LIMIT = 5;

    private final StringRedisTemplate redisTemplate;

    /**
     * 재발송 간격을 확보한다.
     *
     * @return 발송해도 되면 {@code true}, 아직 간격이 지나지 않았으면 {@code false}
     */
    public boolean tryAcquireResendSlot(String email) {
        Boolean acquired =
                redisTemplate.opsForValue().setIfAbsent(resendKey(email), "1", RESEND_INTERVAL);
        return !Boolean.FALSE.equals(acquired);
    }

    /** 연속 실패 제한에 걸리지 않았는지 확인한다. 비밀번호를 대조하기 전에 호출한다. */
    public boolean isLoginAllowed(String email) {
        String recorded = redisTemplate.opsForValue().get(loginFailureKey(email));
        return failureCount(recorded) < LOGIN_FAILURE_LIMIT;
    }

    /** 로그인 실패를 기록한다. 첫 실패에 만료를 걸어 최초 실패 시점부터 기간이 흐르게 하며, 증가와 만료 설정은 한 스크립트로 원자적으로 수행한다. */
    public void recordLoginFailure(String email) {
        redisTemplate.execute(
                RECORD_FAILURE_SCRIPT,
                List.of(loginFailureKey(email)),
                Long.toString(LOGIN_FAILURE_WINDOW.toMillis()));
    }

    /** 로그인에 성공하면 실패 기록을 지운다. */
    public void clearLoginFailures(String email) {
        redisTemplate.delete(loginFailureKey(email));
    }

    private long failureCount(String recorded) {
        if (recorded == null) {
            return 0;
        }
        try {
            return Long.parseLong(recorded);
        } catch (NumberFormatException exception) {
            log.warn("로그인 실패 카운터 값을 해석할 수 없어 0으로 봅니다.");
            return 0;
        }
    }

    private String resendKey(String email) {
        return RESEND_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String loginFailureKey(String email) {
        return LOGIN_FAILURE_KEY_PREFIX + Sha256Hasher.hex(email);
    }
}
