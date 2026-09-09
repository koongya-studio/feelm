package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.AuthRateLimiter;
import com.feelm.backend.domain.auth.PasswordResetTokenIssuer;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.auth.entity.PasswordResetToken;
import com.feelm.backend.domain.auth.event.PasswordResetMailRequestedEvent;
import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.mail.MailSendBudget;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-010 비밀번호 재설정 요청과 API-011 비밀번호 재설정. */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordResetTokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;
    private final MailSendBudget sendBudget;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 재설정 링크를 메일로 보낸다.
     *
     * <p>계정이 없거나 미인증·탈퇴 상태여도 예외 없이 끝난다. 응답만 보고 계정 존재 여부를 알 수 없어야 하기 때문이다(API-010 처리 규칙). 실제로 발송하는지와
     * 무관하게 발송 제한을 먼저 적용해, 제한 응답으로도 계정 존재 여부가 드러나지 않게 한다.
     *
     * <p>제한은 이메일 단위 간격뿐 아니라 IP 단위로도 걸리고, IP 카운터는 재발송(API-003)과 공유한다(S15P21E106-281 결정 D-2). 어느 축에서
     * 걸렸든 응답은 같은 {@code 429 RATE_LIMITED}다(결정 D-6).
     *
     * <p>계정 메일 전체의 전역 발송 상한은 이 자리에 없다. 그 상한은 실제 발송을 세고 발송 스레드에서 소모하므로 응답을 바꾸지 않는다(S15P21E106-287 결정
     * D-2). 여기서 통과한 요청의 메일이 상한 때문에 나가지 않을 수 있고, 그때도 응답은 {@code 202}다.
     *
     * <p>대상은 이메일 로그인 수단을 가진 활성 계정뿐이다. 미인증 계정은 인증 메일 재발송(API-003)이 복구 경로이고, 소셜 전용 계정에 이메일 로그인 수단을 붙여
     * 주는 것은 API-008의 미정 사항이라 이 경로로 열지 않는다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP. IP 단위 발송 제한에 쓴다.
     */
    @Transactional
    public void request(String rawEmail, String clientIp) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquirePasswordResetSlot(email, clientIp)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        // 예산이 마른 동안에는 토큰을 회전시키지 않는다(S15P21E106-287 리뷰 B-2). 회전은 트랜잭션 안에서 기존 링크를
        // 무효화하는데, 발송 여부는 커밋 뒤 발송 스레드가 정하므로 그대로 두면 사용자가 이미 받은 살아 있는 링크만 죽고
        // 새 메일은 오지 않는다. 확인은 소모 없이 읽기만 하고 계정 존재 여부와 무관하게 모든 요청에 건다.
        if (!sendBudget.hasCapacity()) {
            return;
        }
        userRepository
                .findByEmail(email)
                .filter(user -> user.isActive() && user.hasPassword())
                .ifPresent(
                        user ->
                                eventPublisher.publishEvent(
                                        new PasswordResetMailRequestedEvent(
                                                user.getId(),
                                                email,
                                                tokenIssuer.issue(user.getId()))));
    }

    /**
     * 토큰을 소진하고 새 비밀번호를 저장한 뒤 해당 사용자의 모든 Refresh Token을 폐기한다(API-011 처리 규칙).
     *
     * <p>검증 순서는 토큰 유효성이 먼저이고 계정 상태가 나중이다. 만료·재사용된 토큰으로는 계정 상태를 알 수 없어야 하기 때문이다. 활성 계정이 아니면 토큰 자체를
     * 무효로 응답해, 탈퇴 여부가 오류 코드로 드러나지 않게 한다.
     *
     * <p>토큰과 계정 행을 이 순서로 잠근다. 이메일 인증(API-002)도 같은 순서를 쓰므로 두 경로가 교착하지 않는다. 같은 토큰의 동시 요청은 앞의 커밋을 기다린
     * 뒤 소진된 토큰을 보게 되어 1회용이 지켜진다.
     *
     * <p>토큰 폐기는 커밋 전에 수행한다. 커밋 후로 미루면 비밀번호가 바뀐 뒤 폐기가 실패하는 창이 생기고, 반대로 롤백되면 세션만 끊겨 사용자가 다시 로그인하면 되므로
     * 이쪽이 안전한 방향이다.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        PasswordResetToken token =
                tokenRepository
                        .findByTokenHash(tokenIssuer.hashOf(rawToken))
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESET_TOKEN_INVALID));
        Instant now = Instant.now();
        if (token.isUsed()) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        if (token.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_EXPIRED);
        }

        User user =
                userRepository
                        .findByIdForUpdate(token.getUserId())
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESET_TOKEN_INVALID));
        user.changePassword(passwordEncoder.encode(newPassword));
        token.markUsed(now);
        refreshTokenService.revokeAll(user.getId());
    }
}
