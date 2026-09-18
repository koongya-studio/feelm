package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.onboarding.repository.OnboardingPreferenceRepository;
import com.feelm.backend.domain.ott.repository.UserOttRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.NotificationSettingsRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Instant;
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
    private final EmailVerificationTokenRepository verificationTokenRepository;
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
     * <p>검사 순서는 계정 판정 → 비밀번호 보유 → 값 유무 → 대조다. 활성 계정이 아니면 그 뒤를 보지 않고 {@code UNAUTHORIZED}로 거절한다.
     * Access Token은 계정 상태를 담지 않아 탈퇴 뒤에도 잔여 수명 동안 서명이 유효하기 때문이다(152에서 사용자 도메인에 세운 규칙). 비밀번호 보유 판정을 값
     * 검증보다 앞에 두어야 소셜 전용 계정이 계약대로 {@code PASSWORD_NOT_SET}에 도달한다(리뷰 A-F7·B-F6).
     *
     * <p>소셜 연결 수단만 이 트랜잭션에서 지운다. 남으면 {@code UK(provider, provider_user_id)} 때문에 그 소셜 계정으로 다시 가입할 수
     * 없어(D-9가 삭제를 택한 이유) 나중에 정리하는 것으로는 늦다.
     *
     * <p>계정 치환을 벌크 삭제보다 <b>먼저</b> 호출한다. 벌크 삭제는 영속성 컨텍스트를 flush·clear 하므로, 그 뒤에 Entity를 고치면 준영속 상태여서
     * UPDATE가 나가지 않는다. 순서를 바꾸면 치환이 조용히 사라지고 응답은 그대로 204다.
     */
    @Transactional
    void withdrawAccount(long userId, String password) {
        User user =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!user.hasPassword()) {
            throw new BusinessException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.withdraw(Instant.now());
        authProviderRepository.deleteByUserId(userId);
        userOttRepository.deleteByUserId(userId);
        notificationSettingsRepository.deleteByUserId(userId);
        notificationCommandService.deleteByUserId(userId);
        onboardingPreferenceRepository.deleteByUserId(userId);
    }

    /**
     * 남은 인증 토큰을 지운다(D-9). 계정이 이미 {@code WITHDRAWN}으로 커밋된 뒤에만 불린다.
     *
     * <p>계정 잠금을 들고 있지 않아 {@link UserRepository#findByIdForUpdate}의 잠금 순서와 무관하다. 이 단계가 실패해도 토큰은 이미
     * 무력하다 — 재설정(API-011)은 활성 계정만 통과시키고({@code RESET_TOKEN_INVALID}), 이메일 인증(API-002)은 {@code
     * PENDING_VERIFICATION}이 아닌 계정을 거절한다. 그래서 정리를 뒤로 미룰 수 있다.
     */
    @Transactional
    void deleteAuthTokens(long userId) {
        verificationTokenRepository.deleteByUserId(userId);
        resetTokenRepository.deleteByUserId(userId);
    }
}
