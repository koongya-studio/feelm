package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.AuthRateLimiter;
import com.feelm.backend.domain.auth.EmailVerificationTokenConsumer;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.auth.dto.request.SignupRequest;
import com.feelm.backend.domain.user.WithdrawnAccount;
import com.feelm.backend.domain.user.entity.NotificationSettings;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.NotificationSettingsRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ConstraintViolations;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-001 이메일 회원가입. */
@Service
@RequiredArgsConstructor
public class SignupService {

    private final UserRepository userRepository;
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final EmailVerificationTokenConsumer tokenConsumer;
    private final TokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;

    /**
     * 계정과 알림 설정 기본행을 한 트랜잭션에서 만들고 세션을 발급한다.
     *
     * <p>가입할 이메일은 인증번호 확인(API-079)이 발급한 토큰에서만 읽는다(S15P21E106-603). 본문 값을 믿으면 남의 이메일로 가입할 수 있다. 이메일
     * 소유는 가입 전에 이미 확인했으므로 계정은 인증 대기 상태를 거치지 않고 곧바로 {@code ACTIVE}이고, 응답은 소셜 가입(API-008)과 같은 형태로
     * Access Token과 refresh 쿠키를 함께 내린다. <b>이 경로는 메일을 보내지 않는다.</b>
     *
     * <p>순서에는 이유가 있다.
     *
     * <ul>
     *   <li><b>요청 제한이 가장 앞</b>이다. 토큰 검증과 중복 검사보다 먼저 두어 {@code 429}가 이메일·닉네임의 사용 여부를 알아내는 수단이 되지 않게
     *       한다. 카운터는 메일 축과 공유하지 않는 가입 전용이다(601 결정 4).
     *   <li><b>토큰 재사용 확인은 중복 검사보다 앞</b>이고, <b>소진 기록은 중복 검사 다음, 계정 저장 직전</b>이다. 닉네임 중복처럼 사용자가 곧바로 고칠
     *       수 있는 실패가 토큰을 태우면 인증번호부터 다시 받아야 한다. 저장이 유일 제약에 걸려 되돌아갈 때도 같은 이유로 소진 기록을 되돌린다. 걸리는 제약이
     *       이메일만이 아니라 닉네임일 수도 있기 때문이다.
     *   <li><b>Refresh Token 발급은 계정 저장 뒤</b>다. 발급은 Redis에 쓰므로 트랜잭션이 되돌아도 남는다. 계정이 만들어진 뒤에만 부른다.
     * </ul>
     *
     * @param clientIp 요청을 보낸 클라이언트 IP. IP 단위 요청 제한에 쓴다
     */
    @Transactional
    public LoginService.LoginResult signup(SignupRequest request, String clientIp) {
        if (!rateLimiter.tryAcquireSignupSlot(clientIp)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        TokenProvider.EmailVerificationTokenClaims claims =
                tokenProvider.parseEmailVerificationToken(request.emailVerificationToken());
        String email = claims.email();
        rejectConsumedToken(claims.tokenId());

        rejectReservedEmail(email);
        rejectDuplicatedEmail(email);
        rejectDuplicatedNickname(request.nickname());
        if (!tokenConsumer.consume(claims.tokenId())) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
        }

        User user = save(request, email, claims.tokenId());
        notificationSettingsRepository.save(NotificationSettings.createDefault(user.getId()));

        RefreshTokenService.IssuedTokens tokens = refreshTokenService.issue(user.getId());
        return LoginService.LoginResult.of(tokens, user);
    }

    /**
     * 이미 쓰인 토큰은 중복 검사보다 먼저 거절한다.
     *
     * <p>가입을 마친 토큰은 그 이메일의 계정도 남아 있어 중복 검사가 먼저 {@code 409}를 낸다. 계약은 재사용을 {@code 401}로 알리므로 확인이 앞에
     * 와야 한다. 실제 소진 기록은 되돌릴 수 있는 실패를 지나 계정 저장 직전에 한다.
     */
    private void rejectConsumedToken(String tokenId) {
        if (tokenConsumer.isConsumed(tokenId)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
        }
    }

    /**
     * 계정 행을 만든다. 유일 제약에 걸리면 소진 기록을 되돌린 뒤 중복 오류로 옮긴다.
     *
     * <p>되돌리는 이유는 걸리는 제약이 이메일만이 아니기 때문이다(리뷰 A-F1 · B-1). <b>닉네임</b> 경합에서 진 요청은 닉네임만 바꾸면 곧바로 다시 가입할
     * 수 있는데, 토큰이 탄 채로 두면 인증번호부터 다시 받아야 한다. 계정은 만들어지지 않았으므로 토큰을 다시 쓸 수 있게 두는 편이 맞다. 이메일 경합이었다면 그 토큰은
     * 어차피 쓸 곳이 없다.
     */
    private User save(SignupRequest request, String email, String tokenId) {
        User user =
                User.register(
                        email,
                        passwordEncoder.encode(request.password()),
                        request.nickname(),
                        request.birthDate(),
                        request.gender());
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // 중복 검사와 삽입 사이에 다른 요청이 끼어든 경우다. 유일 제약을 같은 오류 코드로 옮긴다.
            tokenConsumer.release(tokenId);
            throw ConstraintViolations.accountDuplicationOf(exception);
        }
    }

    /**
     * 이미 쓰고 있는 이메일이면 거절한다.
     *
     * <p>보존 기간이 지난 미인증 계정을 지우고 재가입을 허용하던 분기는 없앴다(S15P21E106-604). 그 상태 자체가 사라졌고 잔존 행은 마이그레이션이 지웠다.
     * 탈퇴 계정은 이메일이 예약 도메인으로 치환되므로(D-9) 여기에 걸리지 않는다.
     *
     * <p>이 검사와 삽입 사이에 다른 요청이 끼어들 수 있다. 그 경합은 이메일 유일 제약이 막고 {@link #save}가 같은 오류 코드로 옮긴다.
     */
    private void rejectDuplicatedEmail(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
    }

    /**
     * 탈퇴 익명화가 쓰는 예약 도메인으로는 가입할 수 없다(D-9).
     *
     * <p>토큰은 인증번호 발송(API-078)이 이미 같은 검사를 통과한 이메일에만 발급되므로 이 검사는 두 번째 겹이다. 계약(API-001 처리 규칙)이 이 경로를
     * 지목하고 소셜 가입(API-008)도 자체 검사를 갖고 있어 한 겹만 남기지 않는다(리뷰 A-F6 · B-5).
     */
    private void rejectReservedEmail(String email) {
        if (WithdrawnAccount.isReservedEmail(email)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** DB 유일 인덱스와 같은 정규화 식으로 확인한다. 공백은 닉네임 규칙에서 이미 금지되므로 대소문자만 실질적으로 문제가 된다. */
    private void rejectDuplicatedNickname(String nickname) {
        if (userRepository.countByNormalizedNickname(nickname) > 0) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }
    }
}
