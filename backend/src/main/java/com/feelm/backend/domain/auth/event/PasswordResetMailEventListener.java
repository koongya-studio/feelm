package com.feelm.backend.domain.auth.event;

import com.feelm.backend.infrastructure.mail.PasswordResetMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재설정 토큰 저장이 커밋된 뒤에 메일을 보낸다.
 *
 * <p>외부 호출을 DB 트랜잭션 안에 넣지 않는다(컨벤션 8장). 커밋 전에 보내면 이후 롤백된 토큰의 링크가 나갈 수 있고, SMTP 응답을 기다리는 동안 트랜잭션이 열려
 * 있게 된다.
 *
 * <p>발송 실패는 요청을 되돌리지 않는다. 응답은 이미 202이고 사용자는 간격이 지난 뒤 다시 요청할 수 있으므로, 예외를 호출자에게 전파하지 않고 기록만 남긴다. 사용자
 * 식별자만 남기고 이메일과 토큰은 남기지 않는다(컨벤션 12장).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetMailEventListener {

    private final PasswordResetMailSender mailSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendPasswordResetMail(PasswordResetMailRequestedEvent event) {
        try {
            mailSender.send(event.email(), event.passwordResetToken());
        } catch (RuntimeException exception) {
            log.error(
                    "비밀번호 재설정 메일 발송에 실패했습니다. 사용자 {}는 잠시 뒤 다시 요청할 수 있습니다.",
                    event.userId(),
                    exception);
        }
    }
}
