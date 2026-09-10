package com.feelm.backend.domain.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 가입 이메일 인증 토큰. 1회용이며 유효기간은 24시간이다(API-001).
 *
 * <p>컬럼과 소진·만료 판정은 {@link OneTimeTokenEntity}에 있다. 재설정 토큰({@link PasswordResetToken})과 구조가 같지만 수명과
 * 발급 조건이 다르고 한쪽 변경이 다른 쪽으로 번지면 안 되므로 테이블과 엔티티는 따로 둔다(ERD 2.1).
 */
@Entity
@Table(name = "email_verification_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationToken extends OneTimeTokenEntity {

    private EmailVerificationToken(Long userId, String tokenHash, Instant expiresAt) {
        super(userId, tokenHash, expiresAt);
    }

    public static EmailVerificationToken issue(Long userId, String tokenHash, Instant expiresAt) {
        return new EmailVerificationToken(userId, tokenHash, expiresAt);
    }
}
