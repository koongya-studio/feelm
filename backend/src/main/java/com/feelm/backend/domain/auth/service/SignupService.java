package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.AuthRateLimiter;
import com.feelm.backend.domain.auth.VerificationTokenIssuer;
import com.feelm.backend.domain.auth.dto.request.SignupRequest;
import com.feelm.backend.domain.auth.dto.response.SignupResponse;
import com.feelm.backend.domain.auth.event.AuthMailRequestedEvent;
import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import com.feelm.backend.domain.user.entity.NotificationSettings;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.NotificationSettingsRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.mail.MailKind;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final EmailVerificationTokenRepository tokenRepository;
    private final VerificationTokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthRateLimiter rateLimiter;

    /**
     * 계정과 알림 설정 기본행, 인증 토큰을 한 트랜잭션에서 만든다.
     *
     * <p>인증 메일은 커밋 이후에 발송한다. 이벤트만 발행하고 실제 발송은 {@code AuthMailEventListener}가 맡는다.
     *
     * <p>IP 단위 요청 제한을 가장 먼저 본다(S15P21E106-287 리뷰 B-1). 가입 인증 메일도 전역 발송 상한을 소모하므로, 이 경로에 제한이 없으면 한
     * IP의 반복 가입만으로 상한이 말라 정상 사용자의 계정 복구 메일이 나가지 않는다. 중복 검사보다 앞에 두어 제한을 넘긴 요청이 이메일·닉네임의 사용 여부를 알아내는
     * 수단이 되지 않게 한다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP. IP 단위 요청 제한에 쓴다.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request, String clientIp) {
        if (!rateLimiter.tryAcquireSignupSlot(clientIp)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        String email = normalize(request.email());
        userRepository.findByEmail(email).ifPresent(this::deleteStalePendingOrReject);
        rejectDuplicatedNickname(request.nickname());

        User user = save(request, email);
        notificationSettingsRepository.save(NotificationSettings.createDefault(user.getId()));

        String verificationToken = tokenIssuer.issue(user.getId());
        eventPublisher.publishEvent(
                new AuthMailRequestedEvent(
                        MailKind.VERIFICATION, user.getId(), email, verificationToken));
        return SignupResponse.from(user);
    }

    private User save(SignupRequest request, String email) {
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
            throw ConstraintViolations.accountDuplicationOf(exception);
        }
    }

    /**
     * 미인증 상태로 보존 기간이 지난 계정은 지우고 재가입을 허용한다(플랜 결정 3). 인증을 마친 계정과 탈퇴 계정은 대상이 아니다.
     *
     * <p>계정 삭제는 상태·생성 시각 조건이 붙은 삭제문으로 즉시 실행한다. 조회와 삭제 사이에 다른 요청이 인증을 마쳤으면 조건에 걸려 지워지지 않으므로, 그때는
     * 재가입을 거절하고 트랜잭션을 되돌려 먼저 지운 의존 행도 복구한다. 삭제문을 즉시 실행하는 것은 삽입이 먼저 나가 이메일 유일 제약을 위반하는 것도 막는다.
     */
    private void deleteStalePendingOrReject(User existing) {
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

    /** DB 유일 인덱스와 같은 정규화 식으로 확인한다. 공백은 닉네임 규칙에서 이미 금지되므로 대소문자만 실질적으로 문제가 된다. */
    private void rejectDuplicatedNickname(String nickname) {
        if (userRepository.countByNormalizedNickname(nickname) > 0) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }
    }

    /** 이메일은 소문자로 정규화해 저장한다(API-001 Request 규칙). */
    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
