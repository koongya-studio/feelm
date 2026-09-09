package com.feelm.backend.domain.auth.event;

import com.feelm.backend.infrastructure.mail.MailDispatcher;
import com.feelm.backend.infrastructure.mail.MailKind;
import com.feelm.backend.infrastructure.mail.VerificationMailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 계정 저장이 커밋된 뒤에 인증 메일 발송을 요청한다.
 *
 * <p>외부 호출을 DB 트랜잭션 안에 넣지 않는다(컨벤션 8장). 커밋 전에 보내면 이후 롤백된 계정에 메일이 나갈 수 있고, SMTP 응답을 기다리는 동안 트랜잭션이 열려
 * 있게 된다.
 *
 * <p>발송은 요청 스레드에서 하지 않고 {@link MailDispatcher}에 넘긴다. 그래야 SMTP 왕복이 가입·재발송 응답 시간에 실리지 않는다
 * (S15P21E106-282). 발송 실패와 큐 포화가 요청을 되돌리지 않는 것도 디스패처가 보장하므로 이 리스너는 예외를 다루지 않는다.
 */
@Component
@RequiredArgsConstructor
public class VerificationMailEventListener {

    private final VerificationMailSender mailSender;
    private final MailDispatcher mailDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendVerificationMail(VerificationMailRequestedEvent event) {
        mailDispatcher.dispatch(
                MailKind.VERIFICATION,
                event.userId(),
                () -> mailSender.send(event.email(), event.verificationToken()));
    }
}
