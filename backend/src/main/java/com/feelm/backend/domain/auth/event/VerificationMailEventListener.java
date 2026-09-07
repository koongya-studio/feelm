package com.feelm.backend.domain.auth.event;

import com.feelm.backend.infrastructure.mail.VerificationMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 계정 저장이 커밋된 뒤에 인증 메일을 보낸다.
 *
 * <p>외부 호출을 DB 트랜잭션 안에 넣지 않는다(컨벤션 8장). 커밋 전에 보내면 이후 롤백된 계정에 메일이 나갈 수 있고, SMTP 응답을 기다리는 동안 트랜잭션이 열려
 * 있게 된다.
 *
 * <p>발송 실패는 가입을 되돌리지 않는다. 계정은 이미 만들어졌고 사용자는 재발송(API-003)으로 복구할 수 있으므로, 예외를 호출자에게 전파하지 않고 기록만 남긴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VerificationMailEventListener {

    private final VerificationMailSender mailSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendVerificationMail(VerificationMailRequestedEvent event) {
        try {
            mailSender.send(event.email(), event.verificationToken());
        } catch (RuntimeException exception) {
            log.error(
                    "인증 메일 발송에 실패했습니다. 사용자 {}는 재발송(API-003)으로 복구할 수 있습니다.",
                    event.userId(),
                    exception);
        }
    }
}
