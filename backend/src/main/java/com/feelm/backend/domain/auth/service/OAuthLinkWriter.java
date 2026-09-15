package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ConstraintViolations;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 계정 연결(API-009)의 DB 쓰기 단위.
 *
 * <p>{@link OAuthLinkService}에서 분리한 이유는 트랜잭션 경계 때문이다. 같은 클래스의 메서드를 직접 부르면 프록시를 거치지 않아
 * {@code @Transactional}이 적용되지 않는다. 제공자 호출은 {@link OAuthLinkService}에 남겨 트랜잭션 밖에 둔다(컨벤션 8장).
 */
@Service
@RequiredArgsConstructor
class OAuthLinkWriter {

    private final UserAuthProviderRepository authProviderRepository;
    private final UserRepository userRepository;

    /**
     * 계정 행을 잠근 채 활성 여부를 확인하고 연결을 삽입한다.
     *
     * <p>잠금 없이 확인만 하던 것을 <b>잠금</b>으로 바꾼 이유는 탈퇴(API-013)와의 경합이다. 확인과 삽입 사이에 탈퇴가 커밋되면 이 삽입은 탈퇴가 이미 지운
     * 뒤에 새 연결을 남긴다. 그러면 {@code UK(provider, provider_user_id)} 때문에 그 소셜 계정으로 다시 가입할 수 없게 되는데, D-9가
     * 연결 수단을 삭제하기로 한 이유가 정확히 그것이다(리뷰 B-F4). 탈퇴도 같은 계정 행을 잠그므로 둘은 직렬화되고, 탈퇴가 먼저 커밋되면 이 경로는 {@code
     * UNAUTHORIZED}로 거절된다.
     *
     * <p>잠금 순서는 <b>계정 → 연결 수단</b>으로 탈퇴와 같다. 반대로 잠그는 경로는 없다.
     */
    @Transactional
    UserAuthProvider insert(long userId, OAuthProvider provider, String providerUserId) {
        userRepository
                .findByIdForUpdate(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        rejectAlreadyLinked(userId, provider, providerUserId);

        try {
            return authProviderRepository.saveAndFlush(
                    UserAuthProvider.link(userId, provider, providerUserId, Instant.now()));
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
