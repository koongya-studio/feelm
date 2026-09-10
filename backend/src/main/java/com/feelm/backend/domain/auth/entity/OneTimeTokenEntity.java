package com.feelm.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 메일 링크에 실리는 1회용 토큰의 공통 매핑이다(S15P21E106-284).
 *
 * <p>가입 이메일 인증 토큰과 비밀번호 재설정 토큰은 컬럼 구성과 소진·만료 판정이 같다. 두 엔티티가 그것을 각각 들고 있어 한쪽만 고쳐지는 형태였다.
 *
 * <p><b>테이블은 합치지 않는다</b>(ERD 2.1). 수명과 발급 조건이 다르고 한쪽 변경이 다른 쪽으로 번지면 안 되기 때문이다.
 * {@code @MappedSuperclass}는 상속 매핑 전략이 아니라 매핑 재사용이라, 하위 엔티티는 여전히 자기 테이블에 자기 컬럼으로만 매핑된다(컬럼명·테이블·DDL
 * 불변).
 *
 * <p>토큰 원문은 저장하지 않고 SHA-256 해시만 둔다(플랜 결정 8). 원문은 메일 링크에만 실리므로 서버는 발급 시점 외에는 원문을 알지 못한다.
 *
 * <p>{@code User}와 양방향 연관을 만들지 않고 식별자만 보관한다(컨벤션 7장의 단방향 우선 규칙).
 */
@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class OneTimeTokenEntity {

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

    protected OneTimeTokenEntity(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** 토큰을 소진 처리한다. 사용 성공과 재발급으로 인한 무효화가 같은 표시를 사용한다. */
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
