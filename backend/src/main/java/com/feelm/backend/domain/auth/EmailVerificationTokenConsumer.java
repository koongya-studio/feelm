package com.feelm.backend.domain.auth;

import com.feelm.backend.global.security.TokenProvider;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증 토큰(API-079 발급)을 가입(API-001)에서 한 번만 쓰게 한다.
 *
 * <p>서명과 만료만으로는 재사용을 막을 수 없다. 같은 토큰을 두 번 보내면 서명도 만료도 통과하므로, 쓴 토큰의 식별자(jti)를 만료 시점까지 기억해 두 번째 요청을
 * 거절한다. 기억은 Redis {@code SET NX}라 동시 요청도 정확히 한 건만 통과한다.
 *
 * <p>TTL은 토큰 수명과 같다. 토큰이 만료된 뒤에는 서명 검증이 먼저 거절하므로 더 기억할 이유가 없다.
 *
 * <p>식별자는 임의의 UUID라 해시하지 않는다. 키 목록에서 알 수 있는 것이 없고, 이메일이나 사용자 식별자와도 이어지지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationTokenConsumer {

    private static final String KEY_PREFIX = "auth:email-verification-token:";

    private final StringRedisTemplate redisTemplate;
    private final TokenProvider tokenProvider;

    /**
     * 이미 쓰인 토큰인지 소모 없이 본다.
     *
     * <p>소진 처리({@link #consume})와 나눠 둔 이유는 응답 코드 순서 때문이다. 재사용 요청은 계약상 {@code 401}인데, 이미 가입을 마친 토큰은
     * 그 이메일의 계정도 존재해 중복 검사가 먼저 {@code 409}를 내보낸다. 확인을 앞에 두고 기록은 뒤에 두어, 재사용은 {@code 401}로 알리면서도 닉네임
     * 중복처럼 되돌릴 수 있는 실패가 토큰을 태우지 않게 한다.
     *
     * @return 이미 쓰인 토큰이면 {@code true}
     */
    public boolean isConsumed(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
    }

    /**
     * 토큰 식별자를 소진 처리한다.
     *
     * <p>Redis가 값을 주지 못하면 통과시키지 않는다. 소진 여부를 확인할 수 없는 상태에서 계정을 만들면 같은 토큰으로 두 계정이 생길 수
     * 있다(fail-closed).
     *
     * @return 이 요청이 처음 쓴 토큰이면 {@code true}, 이미 쓰인 토큰이면 {@code false}
     */
    public boolean consume(String tokenId) {
        Boolean stored =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(
                                KEY_PREFIX + tokenId,
                                "1",
                                Duration.ofSeconds(
                                        tokenProvider.emailVerificationTokenTtlSeconds()));
        return Boolean.TRUE.equals(stored);
    }

    /**
     * 소진 기록을 되돌린다. 계정을 만들지 못한 채 트랜잭션이 되돌아갈 때 부른다(리뷰 A-F1 · B-1).
     *
     * <p>기록은 Redis라 DB 트랜잭션과 함께 되돌아가지 않는다. 되돌리지 않으면 닉네임 유일 제약 경합처럼 <b>사용자가 곧바로 고칠 수 있는 실패</b>에도 토큰이
     * 타서 인증번호부터 다시 받아야 한다. 계정이 만들어지지 않았으므로 같은 토큰을 다시 쓸 수 있게 두는 편이 맞다.
     *
     * <p>되돌리기가 실패해도 삼키지 않는다. 이 호출은 이미 실패를 처리하는 중이고 예외를 던지면 원래 오류가 가려진다. 지우지 못한 기록은 토큰 수명이 끝날 때 함께
     * 사라지며, 그 사이의 재시도는 {@code 401}로 거절된다(fail-closed).
     */
    public void release(String tokenId) {
        try {
            redisTemplate.delete(KEY_PREFIX + tokenId);
        } catch (RuntimeException failure) {
            log.debug("이메일 인증 토큰의 소진 기록을 되돌리지 못했습니다. 원인: {}", failure.toString());
        }
    }
}
