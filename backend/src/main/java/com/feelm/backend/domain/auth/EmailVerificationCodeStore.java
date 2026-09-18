package com.feelm.backend.domain.auth;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 가입 인증번호(API-078·076)를 Redis에 보관하고 대조한다.
 *
 * <p>번호 원문은 어디에도 남기지 않고 SHA-256 해시만 저장한다(API-078 처리 규칙). 원문이 있는 곳은 발송한 메일 본문뿐이라 저장소를 읽어도 번호를 알 수
 * 없다. DB가 아니라 Redis에 두는 이유는 유효기간이 10분이고 계정이 아직 없어 소유자를 가리킬 행이 없기 때문이다. TTL이 정리까지 맡는다.
 *
 * <p>키는 이메일당 하나({@code auth:email-code:<sha256(email)>})다. 유효한 번호가 이메일당 하나라는 규칙이 키 구조에 그대로 들어 있어
 * 재발송이 이전 번호를 덮어쓰면 자동으로 무효화된다. 키에 이메일 원문을 쓰지 않는 것은 레이트 리밋 키와 같은 이유다({@link AuthRateLimiter} 참고).
 *
 * <p><b>시도 상한을 넘긴 번호는 키를 지우지 않고 폐기 표시를 남긴다.</b> 지우면 그 뒤의 확인 요청이 "발급된 번호가 없음"과 구분되지 않아 계약이 요구하는
 * {@code 400 VERIFICATION_CODE_EXHAUSTED}(새 번호를 받아야 한다)를 {@code 410}(만료)으로 잘못 안내하게 된다. 표시는 원래 만료
 * 시각까지만 남는다.
 *
 * <p>대조는 Lua 스크립트 한 번으로 한다. 시도 증가와 대조가 나뉘면 동시 요청이 모두 증가 전 값을 읽어 상한을 넘겨 대조할 수 있다. 6자리는 10^6짜리 무차별 대입
 * 표면이라 이 상한이 경로의 핵심 방어다(API-079 처리 규칙).
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationCodeStore {

    /** 인증번호 유효기간(API-078 처리 규칙의 10분). {@code MailKind.VERIFICATION_CODE}의 본문 문구와 짝이다. */
    public static final Duration TTL = Duration.ofMinutes(10);

    /** 인증번호 1건당 확인 시도 상한(API-079 처리 규칙). 상한을 넘긴 요청은 대조하지 않는다. */
    public static final int ATTEMPT_LIMIT = 5;

    private static final String KEY_PREFIX = "auth:email-code:";
    private static final String FIELD_HASH = "hash";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_EXHAUSTED = "exhausted";

    /**
     * 번호를 저장하며 이전 번호를 무효화한다.
     *
     * <p>{@code DEL}을 먼저 하는 것은 남아 있던 시도 횟수와 폐기 표시까지 함께 지우기 위해서다. 필드만 덮어쓰면 앞 번호가 쌓아 둔 시도 횟수가 새 번호의
     * 예산을 깎는다.
     */
    private static final DefaultRedisScript<Long> STORE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    redis.call('DEL', KEYS[1])
                    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2], ARGV[3], '0')
                    redis.call('PEXPIRE', KEYS[1], ARGV[4])
                    return 1
                    """,
                    Long.class);

    /**
     * 시도를 세고 대조한다. 결과는 {@link Result}의 순서값이다.
     *
     * <p>상한을 넘긴 요청은 대조하지 않고 그 시점에 번호를 폐기한다(API-079 처리 규칙). 폐기는 해시 필드를 지우고 표시를 남기는 것이며 TTL은 건드리지
     * 않는다.
     *
     * <p>성공하면 키를 지운다. 같은 번호로 토큰을 두 번 받을 수 없다.
     */
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local key = KEYS[1]
                    if redis.call('EXISTS', key) == 0 then return 0 end
                    if redis.call('HGET', key, ARGV[4]) then return 1 end
                    local attempts = tonumber(redis.call('HINCRBY', key, ARGV[3], 1))
                    if attempts > tonumber(ARGV[2]) then
                      redis.call('HDEL', key, ARGV[5])
                      redis.call('HSET', key, ARGV[4], '1')
                      return 2
                    end
                    if redis.call('HGET', key, ARGV[5]) == ARGV[1] then
                      redis.call('DEL', key)
                      return 4
                    end
                    return 3
                    """,
                    Long.class);

    private final StringRedisTemplate redisTemplate;

    /** 대조 결과. 순서값이 스크립트 반환값과 짝이므로 순서를 바꾸지 않는다. */
    public enum Result {
        /** 발급된 번호가 없거나 만료됐다. */
        NOT_FOUND,
        /** 시도 상한을 넘겨 이미 폐기된 번호다. */
        EXHAUSTED,
        /** 이 요청이 상한을 넘겨 번호를 폐기했다. */
        LIMIT_EXCEEDED,
        /** 번호가 다르다. */
        MISMATCH,
        /** 번호가 맞다. 번호는 무효화됐다. */
        MATCHED
    }

    /** 이메일의 인증번호를 저장한다. 이전 번호와 그 시도 기록은 사라진다. */
    public void store(String email, String rawCode) {
        redisTemplate.execute(
                STORE_SCRIPT,
                List.of(key(email)),
                FIELD_HASH,
                Sha256Hasher.hex(rawCode),
                FIELD_ATTEMPTS,
                Long.toString(TTL.toMillis()));
    }

    /**
     * 번호를 대조한다.
     *
     * <p>Redis가 값을 주지 못하면 통과시키지 않는다. 확인할 수 없는 상태에서 인증을 내주지 않는 쪽이 fail-closed다.
     */
    public Result verify(String email, String rawCode) {
        Long result =
                redisTemplate.execute(
                        VERIFY_SCRIPT,
                        List.of(key(email)),
                        Sha256Hasher.hex(rawCode),
                        Integer.toString(ATTEMPT_LIMIT),
                        FIELD_ATTEMPTS,
                        FIELD_EXHAUSTED,
                        FIELD_HASH);
        if (result == null) {
            return Result.NOT_FOUND;
        }
        return Result.values()[result.intValue()];
    }

    private String key(String email) {
        return KEY_PREFIX + Sha256Hasher.hex(email);
    }
}
