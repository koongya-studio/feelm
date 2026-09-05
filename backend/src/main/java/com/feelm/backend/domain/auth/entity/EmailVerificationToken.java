package com.feelm.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가입 이메일 인증 토큰. 1회용이며 유효기간은 24시간이다(API-001).
 *
 * <p>토큰 원문은 저장하지 않고 SHA-256 해시만 둔다(플랜 결정 8). 원문은 인증 메일의 링크에만 실린다.
 *
 * <p>{@code User}와 양방향 연관을 만들지 않고 식별자만 보관한다(컨벤션 7장의 단방향 우선 규칙).
 */
@Entity
@Table(name = "email_verification_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** 사용 전에는 NULL이다. 값이 있으면 소진된 토큰이다. */
    @Column(name = "used_at")
    private Instant usedAt;

    private EmailVerificationToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static EmailVerificationToken issue(Long userId, String tokenHash, Instant expiresAt) {
        return new EmailVerificationToken(userId, tokenHash, expiresAt);
    }

    /** 토큰을 소진 처리한다. 인증 성공과 재발송으로 인한 무효화가 같은 표시를 사용한다. */
    public void markUsed(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
