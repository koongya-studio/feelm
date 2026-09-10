package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.AuthRateLimiter;
import com.feelm.backend.domain.auth.VerificationTokenIssuer;
import com.feelm.backend.domain.auth.dto.response.EmailVerificationResponse;
import com.feelm.backend.domain.auth.entity.EmailVerificationToken;
import com.feelm.backend.domain.auth.event.AuthMailRequestedEvent;
import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.mail.MailKind;
import com.feelm.backend.infrastructure.mail.MailSendBudget;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-002 이메일 인증 확인과 API-003 인증 메일 재발송. */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final VerificationTokenIssuer tokenIssuer;
    private final AuthRateLimiter rateLimiter;
    private final MailSendBudget sendBudget;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 토큰을 소진하고 계정을 활성화한다.
     *
     * <p>검증 순서는 토큰 유효성이 먼저이고 계정 상태가 나중이다. 만료·재사용된 토큰으로는 계정 상태를 알 수 없어야 하기 때문이다. 이미 활성화된 계정에 유효한 토큰이
     * 들어오면 {@code ALREADY_VERIFIED}로 구분해 응답한다.
     *
     * <p>토큰과 계정 행을 이 순서로 잠근다. 같은 토큰의 동시 요청은 앞의 커밋을 기다린 뒤 소진된 토큰을 보고, 재가입(API-001)의 미인증 계정 삭제와 겹치면
     * 어느 한쪽만 반영된다.
     */
    @Transactional
    public EmailVerificationResponse verify(String rawToken) {
        EmailVerificationToken token =
                tokenRepository
                        .findByTokenHash(tokenIssuer.hashOf(rawToken))
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID));
        Instant now = Instant.now();
        if (token.isUsed()) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }
        if (token.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOKEN_EXPIRED);
        }

        User user =
                userRepository
                        .findByIdForUpdate(token.getUserId())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.VERIFICATION_TOKEN_INVALID));
        user.activate();
        token.markUsed(now);
        return EmailVerificationResponse.from(user);
    }

    /**
     * 미인증 계정에 인증 메일을 다시 보낸다.
     *
     * <p>계정이 없거나 이미 활성화된 경우에도 예외 없이 끝난다. 응답만 보고 계정 존재 여부를 알 수 없어야 하기 때문이다(API-003 처리 규칙). 실제로 발송하는지
     * 여부와 무관하게 발송 제한을 먼저 적용해, 제한 응답으로도 계정 존재 여부가 드러나지 않게 한다.
     *
     * <p>제한은 이메일 단위 간격뿐 아니라 IP 단위로도 걸린다(S15P21E106-281). 어느 축에서 걸렸든 응답은 같은 {@code 429
     * RATE_LIMITED}다. 축을 구분해 알려 주면 공격자에게 무엇을 우회해야 하는지 알려 주는 셈이기 때문이다(결정 D-6).
     *
     * <p>계정 메일 전체의 전역 발송 상한은 이 자리에 없다. 그 상한은 실제 발송을 세고 발송 스레드에서 소모하므로 응답을 바꾸지 않는다(S15P21E106-287 결정
     * D-2). 여기서 통과한 요청의 메일이 상한 때문에 나가지 않을 수 있고, 그때도 응답은 {@code 202}다.
     *
     * <p>보존 기간이 지난 미인증 계정에도 보내지 않는다. 그 계정은 재가입(API-001)이 지울 수 있어 새 링크가 죽은 링크가 되기 때문이다. 재가입이 복구 경로다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP. IP 단위 발송 제한에 쓴다.
     */
    @Transactional
    public void resend(String rawEmail, String clientIp) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!rateLimiter.tryAcquireResendSlot(email, clientIp)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        // 예산이 마른 동안에는 토큰을 회전시키지 않는다(S15P21E106-287 리뷰 B-2). 회전은 트랜잭션 안에서 기존 링크를
        // 무효화하는데, 발송 여부는 커밋 뒤 발송 스레드가 정하므로 그대로 두면 사용자가 이미 받은 살아 있는 링크만 죽고
        // 새 메일은 오지 않는다. 확인은 소모 없이 읽기만 하고 계정 존재 여부와 무관하게 모든 요청에 건다.
        if (!sendBudget.hasCapacity()) {
            return;
        }
        Instant now = Instant.now();
        userRepository
                .findByEmail(email)
                .filter(user -> user.isAwaitingVerification(now))
                .ifPresent(
                        user ->
                                eventPublisher.publishEvent(
                                        new AuthMailRequestedEvent(
                                                MailKind.VERIFICATION,
                                                user.getId(),
                                                email,
                                                tokenIssuer.issue(user.getId()))));
    }
}
