package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;

/**
 * 소셜 계정 하나가 이미 다른 사용자에 연결되어 있는지 보는 공통 규칙이다(API-008·009).
 *
 * <p>가입(API-008)과 연결(API-009)이 같은 규칙을 각자 다시 구현하면 한쪽만 고치고 다른 쪽을 놓치기 쉬워 이곳에 모은다.
 */
final class OAuthAccountGuard {

    private OAuthAccountGuard() {}

    static void rejectIfLinkedToAnotherAccount(
            UserAuthProviderRepository authProviderRepository,
            OAuthProvider provider,
            String providerUserId) {
        if (authProviderRepository.existsByProviderAndProviderUserId(provider, providerUserId)) {
            throw new BusinessException(ErrorCode.OAUTH_ACCOUNT_ALREADY_LINKED);
        }
    }
}
