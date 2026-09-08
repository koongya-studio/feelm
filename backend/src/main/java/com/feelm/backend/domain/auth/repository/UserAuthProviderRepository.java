package com.feelm.backend.domain.auth.repository;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, Long> {

    /** 소셜 로그인(API-007)이 연결된 사용자를 찾는다. */
    Optional<UserAuthProvider> findByProviderAndProviderUserId(
            OAuthProvider provider, String providerUserId);

    /** 같은 소셜 계정이 다른 사용자에 연결되어 있는지 확인한다(API-008·009). */
    boolean existsByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    /** 같은 제공자를 이미 연결했는지 확인한다(API-009). */
    boolean existsByUserIdAndProvider(Long userId, OAuthProvider provider);
}
