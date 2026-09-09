package com.feelm.backend.domain.auth.event;

import com.feelm.backend.infrastructure.mail.MailDispatcher;
import com.feelm.backend.infrastructure.mail.MailKind;
import com.feelm.backend.infrastructure.mail.PasswordResetMailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재설정 토큰 저장이 커밋된 뒤에 메일 발송을 요청한다.
 *
 * <p>외부 호출을 DB 트랜잭션 안에 넣지 않는다(컨벤션 8장). 커밋 전에 보내면 이후 롤백된 토큰의 링크가 나갈 수 있고, SMTP 응답을 기다리는 동안 트랜잭션이 열려
 * 있게 된다.
 *
 * <p>발송은 요청 스레드에서 하지 않고 {@link MailDispatcher}에 넘긴다. 이 경로는 응답이 계정 존재 여부와 무관하게 202이므로(API-010 처리 규칙)
 * 발송을 기다리면 응답 시간이 계정 존재 여부를 알려 주는 신호가 된다(S15P21E106-282). 발송 실패와 큐 포화가 요청을 되돌리지 않는 것은 디스패처가 보장한다.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetMailEventListener {

    private final PasswordResetMailSender mailSender;
    private final MailDispatcher mailDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendPasswordResetMail(PasswordResetMailRequestedEvent event) {
        mailDispatcher.dispatch(
                MailKind.PASSWORD_RESET,
                event.userId(),
                () -> mailSender.send(event.email(), event.passwordResetToken()));
    }
}
