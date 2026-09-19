package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.AuthRateLimiter;
import com.feelm.backend.domain.auth.EmailVerificationCodeStore;
import com.feelm.backend.domain.auth.dto.response.EmailVerificationTokenResponse;
import com.feelm.backend.domain.auth.event.AuthMailRequestedEvent;
import com.feelm.backend.domain.user.WithdrawnAccount;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.mail.MailKind;
import com.feelm.backend.infrastructure.mail.MailSendBudget;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-078 이메일 인증번호 발송과 API-079 인증번호 확인.
 *
 * <p>계정을 만들기 전에 이메일 소유를 확인하는 경로다. 확인을 마치면 가입(API-001)이 쓰는 단기 토큰을 발급하며, 확인만 하고 가입하지 않으면 아무 흔적도 남지
 * 않는다. 인증번호는 DB가 아니라 Redis에 해시로만 있고({@link EmailVerificationCodeStore}) TTL이 정리한다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationCodeService {

    /** 인증번호의 자릿수. 자릿수를 바꾸면 요청 검증 정규식과 메일 문구도 함께 바꾼다. */
    private static final int CODE_BOUND = 1_000_000;

    private static final String CODE_FORMAT = "%06d";

    /** 예측 가능한 번호는 무차별 대입 없이도 통과하므로 암호학적 난수를 쓴다(API-078 처리 규칙). */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailVerificationCodeStore codeStore;
    private final AuthRateLimiter rateLimiter;
    private final MailSendBudget sendBudget;
    private final TokenProvider tokenProvider;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 인증번호를 만들어 메일로 보낸다.
     *
     * <p>순서가 계약이다(API-078 처리 규칙). <b>요청 제한이 가장 앞</b>이고 그중 IP 축은 시도 시점에 소모한다. 이 경로는 이미 가입된 이메일을
     * {@code 409}로 알려 주어 이메일 열거 수단이 되므로, 중복 검사 뒤로 제한을 미루면 열거 속도를 묶을 수 없다.
     *
     * <p>반대로 <b>이메일 간격 슬롯은 처리를 마친 뒤</b>에 심는다. 슬롯은 요청자가 아니라 그 이메일 주인의 것이라, 거절될 요청이 슬롯을 심으면 예산을 이미 넘긴
     * 쪽이 임의의 이메일에 대해 발송을 분당 한 건씩 지연시킬 수 있다.
     *
     * <p>전역 발송 상한이 마른 동안에는 <b>새 번호를 발급하지 않는다</b>. 발급은 이전 번호를 무효화하는데 발송 여부는 커밋 뒤 발송 스레드가 정하므로, 그대로
     * 두면 사용자가 가진 살아 있는 번호만 사라지고 새 메일은 오지 않는다(재발송 경로와 같은 판단). 이때 간격 슬롯도 심지 않아 예산이 회복되면 곧바로 다시 요청할 수
     * 있다. 응답은 어느 경우에도 {@code 202}다.
     *
     * <p>메일은 커밋 이후에 보낸다. 이벤트만 발행하고 실제 발송은 {@code AuthMailEventListener}가 맡으므로 SMTP 왕복이 응답에 실리지 않는다.
     *
     * <p><b>DB에 쓰지 않는데도 트랜잭션을 여는 이유</b>가 여기에 있다. 그 리스너는 {@code AFTER_COMMIT}에 걸려 있어 트랜잭션 밖에서 발행한
     * 이벤트를 받지 못한다. 떼면 메일이 조용히 나가지 않으므로 {@code readOnly = true}로 열어 둔다. 중복 검사 조회도 이 경계 안에 있다.
     *
     * @param clientIp 요청을 보낸 클라이언트 IP. IP 단위 발송 제한에 쓴다
     */
    @Transactional(readOnly = true)
    public void send(String rawEmail, String clientIp) {
        String email = normalize(rawEmail);
        if (!rateLimiter.tryAcquireVerificationCodeSlot(email, clientIp)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        rejectReservedEmail(email);
        rejectDuplicatedEmail(email);
        if (!sendBudget.hasCapacity()) {
            return;
        }
        if (!rateLimiter.recordVerificationCodeInterval(email)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        String code = generateCode();
        codeStore.store(email, code);
        eventPublisher.publishEvent(
                new AuthMailRequestedEvent(MailKind.VERIFICATION_CODE, null, email, code));
    }

    /**
     * 인증번호를 대조하고 가입에 쓸 토큰을 발급한다.
     *
     * <p>시도 상한은 {@link EmailVerificationCodeStore}가 원자적으로 센다. 상한을 넘긴 요청은 대조하지 않고 그 시점에 번호를 폐기하므로,
     * 이후 같은 번호의 요청은 만료가 아니라 소진으로 구분해 안내한다.
     *
     * <p>별도의 IP 축은 두지 않는다. 번호 발급 자체가 메일 축 제한(API-078)을 받아 한 IP가 얻을 수 있는 대입 횟수가 발송 한도와 시도 상한의 곱으로
     * 묶인다.
     */
    public EmailVerificationTokenResponse verify(String rawEmail, String code) {
        String email = normalize(rawEmail);
        EmailVerificationCodeStore.Result result = codeStore.verify(email, code);
        switch (result) {
            case NOT_FOUND -> throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXPIRED);
            case EXHAUSTED -> throw new BusinessException(ErrorCode.VERIFICATION_CODE_EXHAUSTED);
            case LIMIT_EXCEEDED -> throw new BusinessException(ErrorCode.RATE_LIMITED);
            case MISMATCH -> throw new BusinessException(ErrorCode.VERIFICATION_CODE_MISMATCH);
            case MATCHED -> {
                // 아래에서 토큰을 발급한다. switch를 값으로 쓰지 않는 이유는 성공만 값을 내기 때문이다.
            }
        }
        String token =
                tokenProvider.createEmailVerificationToken(
                        new TokenProvider.EmailVerificationTokenClaims(
                                email, UUID.randomUUID().toString()));
        return new EmailVerificationTokenResponse(
                token, tokenProvider.emailVerificationTokenTtlSeconds());
    }

    /**
     * 이미 가입된 이메일은 번호를 보내지 않고 거절한다(API-078 처리 규칙, 팀 결정).
     *
     * <p>미인증 계정을 예외로 두던 분기는 없앴다(S15P21E106-604). 그 상태가 사라졌고 잔존 행은 마이그레이션이 지웠다. 탈퇴 계정은 이메일이 예약 도메인으로
     * 치환되므로(D-9) 여기에 걸리지 않는다.
     */
    private void rejectDuplicatedEmail(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
    }

    /** 탈퇴 익명화가 쓰는 예약 도메인으로는 가입할 수 없다(D-9). 가입(API-001)과 같은 이유로 이 경로에서도 막는다. */
    private void rejectReservedEmail(String email) {
        if (WithdrawnAccount.isReservedEmail(email)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String generateCode() {
        return CODE_FORMAT.formatted(RANDOM.nextInt(CODE_BOUND));
    }

    /** 이메일은 소문자로 정규화한다. 저장·대조·제한이 모두 같은 값을 써야 한다(API-078 Request 규칙). */
    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
