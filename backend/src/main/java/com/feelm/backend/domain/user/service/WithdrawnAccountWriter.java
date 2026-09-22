package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.onboarding.repository.OnboardingPreferenceRepository;
import com.feelm.backend.domain.ott.repository.UserOttRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.NotificationSettingsRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.domain.user.service.UserWithdrawalService.SocialCredential;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴(API-013)의 트랜잭션 단위를 나눠 담는다. 순서와 근거는 {@link UserWithdrawalService}에 있다.
 *
 * <p>{@link UserWithdrawalService}와 분리한 이유는 두 단계가 <b>서로 다른 트랜잭션</b>이어야 하기 때문이다. 같은 클래스의 메서드를 직접 부르면
 * 프록시를 거치지 않아 {@code @Transactional}이 적용되지 않고 한 트랜잭션으로 합쳐진다.
 */
@Service
@RequiredArgsConstructor
class WithdrawnAccountWriter {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserAuthProviderRepository authProviderRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final UserOttRepository userOttRepository;
    private final OnboardingPreferenceRepository onboardingPreferenceRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final NotificationCommandService notificationCommandService;

    /**
     * 자격을 확인하고 계정을 파기한다. 이 트랜잭션이 커밋되면 계정은 되살아나지 않는다.
     *
     * <p>계정 행을 잠그고 읽어 프로필 수정(API-015)·비밀번호 변경(API-012)과 동시에 들어와도 치환한 값이 되살아나지 않게 한다. 잠금 뒤에 자격을
     * 확인하므로 사전 검사와 최종 판단이 갈라지지 않고, 사전 검사를 따로 두지 않아 영속성 컨텍스트에 낡은 인스턴스가 남는 문제도 없다.
     *
     * <p>이 트랜잭션은 <b>인증 토큰 테이블을 잠그지 않는다.</b> 저장소는 인증 경로 전체에 <b>토큰 → 계정</b> 잠금 순서를 세워 두었는데({@link
     * UserRepository#findByIdForUpdate}), 계정을 먼저 잠근 채 토큰을 지우면 그 순서를 거슬러 재설정(API-011)·비밀번호
     * 변경(API-012)과 교착한다(리뷰 A-F1·B-F1). 토큰 정리는 커밋 뒤 {@link #deleteAuthTokens}가 맡는다.
     *
     * <p>검사 순서는 계정 판정 → 확인 수단 선택 → (비밀번호 보유 → 값 유무 → 대조 | 연결 대조)다. 활성 계정이 아니면 그 뒤를 보지 않고 {@code
     * UNAUTHORIZED}로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴 뒤에도 잔여 수명 동안 서명이 유효하기 때문이다(152에서 사용자 도메인에
     * 세운 규칙). 비밀번호 경로에서 보유 판정을 값 검증보다 앞에 두어야 소셜 전용 계정이 계약대로 {@code PASSWORD_NOT_SET}에 도달한다(리뷰
     * A-F7·B-F6).
     *
     * <p>소셜 경로의 대조를 <b>이 잠금 안에서</b> 하는 것이 요점이다. 코드 교환은 이미 트랜잭션 밖에서 끝났지만, 교환 결과가 정말 이 계정의 연결인지는 계정을
     * 잠근 뒤에 봐야 한다. 잠금 밖에서 확인하면 그 사이에 연결을 지운 탈퇴나 다른 계정으로의 재연결이 끼어들 수 있다. 지금 저장소에서 연결 행을 만들고 지우는 경로는
     * 연결 삽입({@code OAuthLinkWriter})과 이 탈퇴뿐이고 <b>둘 다 그 행의 주인 계정을 잠그므로</b> 셋은 직렬화된다. 계정 행을 잠그지 않고
     * 연결을 지우는 경로(연결 해제 API 등)가 생기면 이 근거가 무너지므로 그때 다시 본다(리뷰 B-7).
     *
     * <p>소셜 연결 수단만 이 트랜잭션에서 지운다. 남으면 {@code UK(provider, provider_user_id)} 때문에 그 소셜 계정으로 다시 가입할 수
     * 없어(D-9가 삭제를 택한 이유) 나중에 정리하는 것으로는 늦다.
     *
     * <p>계정 치환을 벌크 삭제보다 <b>먼저</b> 호출한다. 벌크 삭제는 영속성 컨텍스트를 flush·clear 하므로, 그 뒤에 Entity를 고치면 준영속 상태여서
     * UPDATE가 나가지 않는다. 순서를 바꾸면 치환이 조용히 사라지고 응답은 그대로 204다.
     */
    @Transactional
    String withdrawAccount(long userId, String password, SocialCredential credential) {
        User user =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (credential == null) {
            verifyPassword(user, password);
        } else {
            verifyLinkedProvider(userId, credential);
        }

        String avatarStorageKey = user.removeAvatar();
        user.withdraw(Instant.now());
        authProviderRepository.deleteByUserId(userId);
        userOttRepository.deleteByUserId(userId);
        notificationSettingsRepository.deleteByUserId(userId);
        notificationCommandService.deleteByUserId(userId);
        onboardingPreferenceRepository.deleteByUserId(userId);
        return avatarStorageKey;
    }

    /** 이메일 경로의 본인 확인. 순서의 근거는 {@link #withdrawAccount} 주석에 있다. */
    private void verifyPassword(User user, String password) {
        if (!user.hasPassword()) {
            throw new BusinessException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    /**
     * 소셜 경로의 본인 확인. 교환으로 확인한 제공자 계정이 <b>이 사용자에게</b> 연결돼 있어야 한다(S15P21E106-435 결정 D1).
     *
     * <p>연결이 없든 다른 사용자의 것이든 {@code INVALID_CREDENTIALS} 하나로 거절한다(결정 D4). 비밀번호 불일치와 같은 판정이고, 코드를 구분해
     * 주면 어떤 제공자 계정이 이 서비스에 연결돼 있는지 알려 주는 조회 수단이 된다.
     *
     * <p>{@code provider}까지 함께 비교하는 이유는 식별자가 제공자 안에서만 유일하기 때문이다. 제공자가 다르면 같은 문자열이 다른 사람을 가리킬 수 있다.
     */
    private void verifyLinkedProvider(long userId, SocialCredential credential) {
        boolean ownedByUser =
                authProviderRepository
                        .findByProviderAndProviderUserId(
                                credential.provider(), credential.providerUserId())
                        .map(link -> Objects.equals(link.getUserId(), userId))
                        .orElse(false);
        if (!ownedByUser) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    /**
     * 남은 재설정 토큰을 지운다(D-9). 계정이 이미 {@code WITHDRAWN}으로 커밋된 뒤에만 불린다.
     *
     * <p>계정 잠금을 들고 있지 않아 {@link UserRepository#findByIdForUpdate}의 잠금 순서와 무관하다. 이 단계가 실패해도 토큰은 이미
     * 무력하다 — 재설정(API-011)은 활성 계정만 통과시킨다({@code RESET_TOKEN_INVALID}). 그래서 정리를 뒤로 미룰 수 있다.
     *
     * <p>인증 토큰 삭제는 함께 사라졌다(S15P21E106-604). 메일 링크 인증이 없어지면서 그 테이블도 없앴다.
     */
    @Transactional
    void deleteAuthTokens(long userId) {
        resetTokenRepository.deleteByUserId(userId);
    }
}
