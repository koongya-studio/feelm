package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * API-009 소셜 계정 연결. 로그인한 사용자의 계정에 소셜 로그인 수단을 추가한다.
 *
 * <p>이메일은 필요하지 않다. 연결은 이미 있는 계정에 로그인 수단만 더하는 일이고, 제공자가 이메일을 주지 않을 수 있기 때문이다(D-2).
 *
 * <p>제공자 호출이 포함되므로 트랜잭션을 열지 않는다(컨벤션 8장). 변경은 연결 한 건의 삽입뿐이라 원자성이 따로 필요하지 않고, 사전 검사와 삽입 사이의 경합은 두 유일
 * 제약이 막는다.
 */
@Service
@RequiredArgsConstructor
public class OAuthLinkService {

    private final OAuthProfileLoader profileLoader;
    private final UserAuthProviderRepository authProviderRepository;
    private final UserRepository userRepository;

    public UserAuthProvider link(
            long userId, OAuthProvider provider, String code, String redirectUri) {
        OAuthUserProfile profile = profileLoader.load(provider, code, redirectUri);

        // 토큰은 유효하지만 제공자 호출 사이 탈퇴한 계정일 수 있다. 느린 외부 호출 이후, 삽입 직전에 다시 확인해
        // 경합 구간을 좁힌다. 없는 사용자로 연결을 만들지 않는다.
        userRepository
                .findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        rejectAlreadyLinked(userId, provider, profile.providerUserId());

        try {
            return authProviderRepository.saveAndFlush(
                    UserAuthProvider.link(
                            userId, provider, profile.providerUserId(), Instant.now()));
        } catch (DataIntegrityViolationException exception) {
            throw ConstraintViolations.linkConflictOf(exception);
        }
    }

    /**
     * 두 가지 중복을 구분해 거절한다.
     *
     * <p>같은 제공자를 두 번 연결하는 경우가 {@code PROVIDER_ALREADY_LINKED}, 그 소셜 계정이 이미 다른 사용자에 연결된 경우가 {@code
     * OAUTH_ACCOUNT_ALREADY_LINKED}다. 자기 계정에 이미 연결한 소셜 계정을 다시 연결하면 두 조건 모두에 걸리는데, 사용자에게 필요한 안내는 "이미
     * 연결된 제공자"이므로 그쪽을 먼저 본다.
     */
    private void rejectAlreadyLinked(long userId, OAuthProvider provider, String providerUserId) {
        if (authProviderRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new BusinessException(ErrorCode.PROVIDER_ALREADY_LINKED);
        }
        OAuthAccountGuard.rejectIfLinkedToAnotherAccount(
                authProviderRepository, provider, providerUserId);
    }
}
