package com.feelm.backend.domain.auth;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 인증 메일 재발송 간격과 로그인 연속 실패를 Redis로 제한한다(플랜 결정 4).
 *
 * <p>키에는 이메일 원문 대신 해시를 쓴다. Redis 키 목록만으로 어떤 이메일이 서비스에 있는지 알 수 있으면 안 되기 때문이다. 네임스페이스는 Refresh Token이
 * 쓰는 {@code auth:}를 이어 사용한다.
 *
 * <p>로그인 제한은 실패를 나중에 기록하는 대신 시도를 먼저 센다. 확인과 기록이 분리되어 있으면 동시 요청이 모두 확인을 통과해 제한 횟수보다 많은 비밀번호 대조가 일어나기
 * 때문이다. 카운터는 DB 트랜잭션과 무관하게 즉시 반영된다. Redis가 응답하지 않으면 예외가 그대로 올라가 인증 요청은 500으로 실패한다(fail-closed). 로그인
 * 성공 자체가 Refresh Token을 Redis에 저장해야 하므로 리미터만 열어 두어도 얻는 것이 없고, 재발송 제한을 열면 장애 중 메일 폭탄이 가능해지기 때문이다. 장애
 * 시 정책을 바꾸려면 후속 업무에서 정한다.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimiter {

    private static final String RESEND_KEY_PREFIX = "auth:email-resend:";
    private static final String LOGIN_FAILURE_KEY_PREFIX = "auth:login-fail:";
    private static final String PASSWORD_RESET_KEY_PREFIX = "auth:password-reset:";

    /**
     * 시도 횟수를 올리고 만료를 보장한 뒤 올린 횟수를 돌려주는 스크립트. {@code INCR}와 {@code PEXPIRE}를 따로 보내면 그 사이에 연결이 끊겼을 때
     * TTL 없는 카운터가 남아 해당 이메일이 무기한 429가 된다. 첫 시도이거나(카운터가 1) 어떤 이유로든 TTL이 없으면 만료를 다시 건다.
     */
    private static final DefaultRedisScript<Long> RECORD_ATTEMPT_SCRIPT =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then "
                            + "redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count",
                    Long.class);

    /** 인증 메일 재발송 최소 간격. */
    private static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

    /** 비밀번호 재설정 요청 최소 간격. 재발송과 같은 값을 쓴다. 둘 다 이메일 발송을 유발하는 공개 엔드포인트다. */
    private static final Duration PASSWORD_RESET_INTERVAL = Duration.ofMinutes(1);

    /** 로그인 연속 실패를 세는 기간. */
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(10);

    /** 이 횟수까지는 비밀번호를 대조하고, 넘는 시도는 대조 없이 429로 막는다(플랜 결정 14). */
    private static final long LOGIN_ATTEMPT_LIMIT = 5;

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

    /**
     * 비밀번호 재설정 요청 간격을 확보한다.
     *
     * <p>계정 존재 여부와 무관하게 요청 이메일 기준으로 센다. 존재하는 계정만 셌다면 429 응답 자체가 가입 여부를 알려 주는 신호가 되기 때문이다(API-010 처리
     * 규칙).
     *
     * @return 처리해도 되면 {@code true}, 아직 간격이 지나지 않았으면 {@code false}
     */
    public boolean tryAcquirePasswordResetSlot(String email) {
        Boolean acquired =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(passwordResetKey(email), "1", PASSWORD_RESET_INTERVAL);
        return !Boolean.FALSE.equals(acquired);
    }

    /**
     * 로그인 시도 자리를 확보한다. 비밀번호를 대조하기 전에 호출한다.
     *
     * <p>확인과 기록을 한 번의 원자적 증가로 합쳐, 동시 요청이 몰려도 한 창 안에서 대조가 정확히 제한 횟수만 일어난다. 첫 시도에 만료를 걸어 최초 시도 시점부터
     * 기간이 흐른다. 시도가 실패로 남을지는 호출자가 정한다. 비밀번호가 맞으면 {@link #clearLoginAttempts}로 지우고, 틀리면 그대로 둔다.
     *
     * @return 대조해도 되면 {@code true}, 제한을 넘었으면 {@code false}
     */
    public boolean tryAcquireLoginAttempt(String email) {
        Long count =
                redisTemplate.execute(
                        RECORD_ATTEMPT_SCRIPT,
                        List.of(loginFailureKey(email)),
                        Long.toString(LOGIN_FAILURE_WINDOW.toMillis()));
        return count != null && count <= LOGIN_ATTEMPT_LIMIT;
    }

    /** 자격이 확인되면 시도 기록을 지운다. */
    public void clearLoginAttempts(String email) {
        redisTemplate.delete(loginFailureKey(email));
    }

    private String resendKey(String email) {
        return RESEND_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String loginFailureKey(String email) {
        return LOGIN_FAILURE_KEY_PREFIX + Sha256Hasher.hex(email);
    }

    private String passwordResetKey(String email) {
        return PASSWORD_RESET_KEY_PREFIX + Sha256Hasher.hex(email);
    }
}
