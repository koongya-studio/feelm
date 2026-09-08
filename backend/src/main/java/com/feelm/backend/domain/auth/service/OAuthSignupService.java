package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.auth.dto.request.OAuthSignupRequest;
import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import com.feelm.backend.domain.auth.repository.UserAuthProviderRepository;
import com.feelm.backend.domain.user.entity.NotificationSettings;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.NotificationSettingsRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-008 소셜 회원가입 완료.
 *
 * <p>이메일과 제공자 식별자는 서명된 가입 토큰에서만 읽는다. 요청 본문의 값을 믿으면 남의 소셜 계정으로 가입할 수 있기 때문이다.
 *
 * <p>같은 이메일의 이메일 계정이 이미 있으면 통합하지 않고 거절한다(D-1). 사용자는 이메일로 로그인한 뒤 계정 연결(API-009)로 같은 결과를 얻는다.
 *
 * <p>제공자 호출은 앞선 API-007에서 끝났으므로 이 유스케이스는 DB 작업만 한 트랜잭션으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class OAuthSignupService {

    private final TokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final UserAuthProviderRepository authProviderRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public LoginService.LoginResult signup(OAuthSignupRequest request) {
        TokenProvider.SignupTokenClaims claims =
                tokenProvider.parseSignupToken(request.signupToken());
        OAuthProvider provider = OAuthProvider.from(claims.provider());
        String email = claims.email();
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_REQUIRED);
        }

        rejectAlreadyLinked(provider, claims.providerUserId());
        rejectDuplicatedEmail(email);
        rejectDuplicatedNickname(request.nickname());

        User user = saveUser(request, email);
        notificationSettingsRepository.save(NotificationSettings.createDefault(user.getId()));
        saveLink(user.getId(), provider, claims.providerUserId());

        RefreshTokenService.IssuedTokens tokens = refreshTokenService.issue(user.getId());
        return LoginService.LoginResult.of(tokens, user);
    }

    private User saveUser(OAuthSignupRequest request, String email) {
        User user =
                User.registerSocial(
                        email, request.nickname(), request.birthDate(), request.gender());
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // 중복 검사와 삽입 사이에 다른 요청이 끼어든 경우다. 유일 제약을 같은 오류 코드로 옮긴다.
            throw ConstraintViolations.accountDuplicationOf(exception);
        }
    }

    private void saveLink(Long userId, OAuthProvider provider, String providerUserId) {
        try {
            authProviderRepository.saveAndFlush(
                    UserAuthProvider.link(userId, provider, providerUserId, Instant.now()));
        } catch (DataIntegrityViolationException exception) {
            throw ConstraintViolations.linkConflictOf(exception);
        }
    }

    /** 토큰을 받은 뒤 같은 소셜 계정으로 이미 가입·연결이 끝난 경우다(API-008 오류 응답). */
    private void rejectAlreadyLinked(OAuthProvider provider, String providerUserId) {
        OAuthAccountGuard.rejectIfLinkedToAnotherAccount(
                authProviderRepository, provider, providerUserId);
    }

    /**
     * 이메일 계정과의 자동 통합은 하지 않는다(D-1). 이메일은 가입 토큰에서 이미 정규화된 값이다.
     *
     * <p>미인증 상태로 보존 기간이 지난 이메일 계정은 이메일 가입(API-001)과 같은 규칙으로 지우고 소셜 가입을 허용한다. 그렇지 않으면 이메일 인증을 끝내 마치지
     * 못한 계정이 그 이메일을 영구히 붙들어, 같은 이메일의 소셜 가입도 항상 거절된다.
     */
    private void rejectDuplicatedEmail(String email) {
        userRepository.findByEmail(email).ifPresent(this::reapStaleOrReject);
    }

    private void reapStaleOrReject(User existing) {
        Instant now = Instant.now();
        if (!existing.isStalePendingVerification(now)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        tokenRepository.deleteByUserId(existing.getId());
        notificationSettingsRepository.deleteByUserId(existing.getId());
        int deleted =
                userRepository.deleteIfStalePendingVerification(
                        existing.getId(), now.minus(User.PENDING_VERIFICATION_RETENTION));
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
    }

    /** DB 유일 인덱스와 같은 정규화 식으로 확인한다(API-001과 동일 규칙). */
    private void rejectDuplicatedNickname(String nickname) {
        if (userRepository.countByNormalizedNickname(nickname) > 0) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }
    }
}
