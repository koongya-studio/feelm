package com.feelm.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자에게 연결된 소셜 로그인 수단(API-007~009).
 *
 * <p>유일 제약 두 개가 서로 다른 규칙을 지킨다. {@code (provider, provider_user_id)}는 한 소셜 계정이 여러 사용자에 연결되는 것을,
 * {@code (user_id, provider)}는 한 사용자가 같은 제공자를 두 번 연결하는 것을 막는다. 두 위반은 각각 {@code
 * OAUTH_ACCOUNT_ALREADY_LINKED}와 {@code PROVIDER_ALREADY_LINKED}로 구분해 응답한다.
 *
 * <p>제약 이름을 마이그레이션과 똑같이 선언한다. 테스트 스키마는 Flyway가 아니라 JPA가 만들기 때문에, 이름을 맞춰 두어야 동시 요청으로 유일 제약이 걸리는 경로를
 * 같은 방식으로 확인할 수 있다.
 *
 * <p>{@code User}와 양방향 연관을 만들지 않고 식별자만 보관한다(컨벤션 7장).
 */
@Entity
@Table(
        name = "user_auth_providers",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_user_auth_providers_provider_provider_user_id",
                    columnNames = {"provider", "provider_user_id"}),
            @UniqueConstraint(
                    name = "uk_user_auth_providers_user_id_provider",
                    columnNames = {"user_id", "provider"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20, updatable = false)
    private OAuthProvider provider;

    /** 제공자가 발급한 불투명 식별자. 이메일과 달리 사용자가 바꿀 수 없어 연결의 기준으로 쓴다. */
    @Column(name = "provider_user_id", nullable = false, updatable = false)
    private String providerUserId;

    /**
     * 연결 시각. DB에 기본값이 있지만 애플리케이션이 채운다. API-009 응답이 이 값을 그대로 돌려주므로 저장 직후 다시 조회하지 않으려면 값을 알고 있어야 한다.
     */
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    private UserAuthProvider(
            Long userId, OAuthProvider provider, String providerUserId, Instant linkedAt) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.linkedAt = linkedAt;
    }

    public static UserAuthProvider link(
            Long userId, OAuthProvider provider, String providerUserId, Instant linkedAt) {
        return new UserAuthProvider(userId, provider, providerUserId, linkedAt);
    }
}
