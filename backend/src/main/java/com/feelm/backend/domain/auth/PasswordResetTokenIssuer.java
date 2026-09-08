package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.entity.PasswordResetToken;
import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 토큰의 발급과 무효화를 담당한다.
 *
 * <p>토큰은 의미가 없는 난수 32바이트를 Base64URL(패딩 없음)로 인코딩한 문자열이고, DB에는 SHA-256 해시만 저장한다. 가입 인증 토큰({@link
 * VerificationTokenIssuer})과 같은 방식이며 수명만 다르다(API-010의 30분).
 *
 * <p>호출자의 트랜잭션 안에서 동작한다. 토큰 저장과 이전 토큰 무효화가 한 트랜잭션이어야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetTokenIssuer {

    /** 재설정 토큰 유효기간(API-010). */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private static final int TOKEN_BYTES = 32;

    private final PasswordResetTokenRepository tokenRepository;
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
                PasswordResetToken.issue(userId, hashOf(rawToken), now.plus(TOKEN_TTL)));
        return rawToken;
    }

    /** 저장된 해시와 비교할 수 있도록 토큰 원문을 같은 방식으로 변환한다. */
    public String hashOf(String rawToken) {
        return Sha256Hasher.hex(rawToken);
    }

    /** 다시 요청하면 이전 링크는 즉시 쓸 수 없어야 한다. 유효한 링크가 여러 개 떠 있으면 1회용의 의미가 약해진다. */
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
