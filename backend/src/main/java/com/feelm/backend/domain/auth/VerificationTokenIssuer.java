package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.entity.EmailVerificationToken;
import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가입 이메일 인증 토큰의 발급과 무효화를 담당한다.
 *
 * <p>토큰은 의미가 없는 난수 32바이트를 Base64URL(패딩 없음)로 인코딩한 문자열이고, DB에는 SHA-256 해시만 저장한다(플랜 결정 8). 원문은 인증 메일의
 * 링크에만 실리므로 서버는 발급 시점 외에는 원문을 알지 못한다.
 *
 * <p>호출자의 트랜잭션 안에서 동작한다. 토큰 저장과 계정 생성·상태 변경이 한 트랜잭션이어야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class VerificationTokenIssuer {

    /** 인증 토큰 유효기간(API-001). */
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private static final int TOKEN_BYTES = 32;

    private final EmailVerificationTokenRepository tokenRepository;
    private final SecureRandom random = new SecureRandom();

    /**
     * 기존 미사용 토큰을 무효화하고 새 토큰을 발급한다.
     *
     * @return 메일 링크에 실을 토큰 원문
     */
    public String issue(Long userId) {
        Instant now = Instant.now();
        invalidateUnused(userId, now);

        String rawToken = generateRawToken();
        tokenRepository.save(
                EmailVerificationToken.issue(userId, hashOf(rawToken), now.plus(TOKEN_TTL)));
        return rawToken;
    }

    /** 저장된 해시와 비교할 수 있도록 토큰 원문을 같은 방식으로 변환한다. */
    public String hashOf(String rawToken) {
        return Sha256Hasher.hex(rawToken);
    }

    /** 재발송하면 이전 토큰은 즉시 쓸 수 없어야 한다(API-003 처리 규칙). */
    private void invalidateUnused(Long userId, Instant now) {
        tokenRepository
                .findAllByUserIdAndUsedAtIsNull(userId)
                .forEach(token -> token.markUsed(now));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
