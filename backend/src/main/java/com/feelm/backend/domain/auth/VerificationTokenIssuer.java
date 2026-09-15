package com.feelm.backend.domain.auth;

import com.feelm.backend.domain.auth.entity.EmailVerificationToken;
import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 가입 이메일 인증 토큰의 발급과 무효화를 담당한다. 골격은 {@link OneTimeTokenIssuer}에 있다.
 *
 * <p>사용자 단위 직렬화({@link OneTimeTokenIssuer#beforeInvalidate})는 쓰지 않는다. 재설정 토큰과 달리 새 토큰 발급 없이 무효화만 하는
 * 경로가 없어, 같은 사용자의 동시 발급을 막아야 하는 지점이 재발송 간격 제한 밖에 없다(S15P21E106-284 플랜 D-4).
 */
@Component
public class VerificationTokenIssuer extends OneTimeTokenIssuer<EmailVerificationToken> {

    /** 인증 토큰 유효기간(API-001). 메일 본문의 "24시간" 문구와 짝이다({@code MailKind}). */
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    public VerificationTokenIssuer(EmailVerificationTokenRepository tokenRepository) {
        super(tokenRepository, TOKEN_TTL);
    }

    @Override
    protected EmailVerificationToken newToken(Long userId, String tokenHash, Instant expiresAt) {
        return EmailVerificationToken.issue(userId, tokenHash, expiresAt);
    }
}
