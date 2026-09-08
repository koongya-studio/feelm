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
 * 비밀번호 재설정 토큰. 1회용이며 유효기간은 30분이다(API-010).
 *
 * <p>토큰 원문은 저장하지 않고 SHA-256 해시만 둔다. 원문은 재설정 메일의 링크에만 실리므로 서버는 발급 시점 외에는 원문을 알지 못한다. 가입 인증 토큰({@link
 * EmailVerificationToken})과 같은 구조이지만, 수명과 발급 조건이 다르고 한쪽 변경이 다른 쪽으로 번지면 안 되므로 테이블과 엔티티를 따로 둔다(ERD
 * 2.1).
 *
 * <p>{@code User}와 양방향 연관을 만들지 않고 식별자만 보관한다(컨벤션 7장의 단방향 우선 규칙).
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

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

    private PasswordResetToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static PasswordResetToken issue(Long userId, String tokenHash, Instant expiresAt) {
        return new PasswordResetToken(userId, tokenHash, expiresAt);
    }

    /** 토큰을 소진 처리한다. 재설정 성공과 재요청으로 인한 무효화가 같은 표시를 사용한다. */
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
