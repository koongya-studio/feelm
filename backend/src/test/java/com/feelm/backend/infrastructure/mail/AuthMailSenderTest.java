package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feelm.backend.infrastructure.mail.AuthMailProperties.Mode;
import com.feelm.backend.support.SharedTestRuntime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 계정 메일 두 종류가 하나의 발송 인터페이스를 지나면서도 종류별 제목·본문·링크를 유지하는지 검증한다(S15P21E106-284).
 *
 * <p>종류별 발송 인터페이스를 하나로 합치면(플랜 D-1) 제목·본문·링크가 {@link MailKind}와 {@link AuthMailProperties}로 옮겨진다. 그
 * 값들은 메일 본문에만 실려 계약 테스트가 볼 수 없다(스파이 빈은 토큰 원문만 기록한다). 인증 메일에 재설정 링크가 실리는 식의 뒤바뀜을 여기서 막는다.
 *
 * <p>제목과 본문을 <b>글자 단위로</b> 고정한다(284 리뷰 A-4). 이 업무의 전제가 "메일 문안 불변"이므로 문안이 바뀌면 테스트가 함께 고쳐지는 편이 낫다.
 * 본문이 바뀌어야 하는 변경이라면 그 변경의 diff에 이 테스트가 함께 드러난다.
 */
@SharedTestRuntime
@DisplayName("계정 메일 발송 — 종류별 제목·본문·본문 값")
class AuthMailSenderTest {

    private static final AuthMailProperties PROPERTIES =
            new AuthMailProperties(
                    Mode.LOG, "no-reply@feelm.local", "https://feelm.example/reset-password");

    @Test
    @DisplayName("인증번호 메일은 링크 없이 번호와 10분 안내를 담는다")
    void smtpSenderSendsVerificationCodeMail() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        AuthMailSender sender = new SmtpAuthMailSender(mailSender, PROPERTIES);

        sender.send(MailKind.VERIFICATION_CODE, "user@example.com", "032815");

        SimpleMailMessage message = captureSent(mailSender);
        assertThat(message.getFrom()).isEqualTo("no-reply@feelm.local");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("[Feelm] 이메일 인증번호를 입력해 주세요");
        assertThat(message.getText())
                .isEqualTo(
                        """
                        Feelm 가입을 확인해 주세요.

                        아래 인증번호를 회원가입 화면에 입력하면 이메일 인증이 완료됩니다. 번호는 10분 동안만 유효합니다.
                        032815

                        본인이 요청하지 않았다면 이 메일을 무시해 주세요.""");
    }

    @Test
    @DisplayName("재설정 메일은 재설정 화면 링크와 30분 안내를 담는다")
    void smtpSenderSendsPasswordResetMail() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        AuthMailSender sender = new SmtpAuthMailSender(mailSender, PROPERTIES);

        sender.send(MailKind.PASSWORD_RESET, "user@example.com", "raw-token");

        SimpleMailMessage message = captureSent(mailSender);
        assertThat(message.getSubject()).isEqualTo("[Feelm] 비밀번호 재설정 안내");
        assertThat(message.getText())
                .isEqualTo(
                        """
                        Feelm 비밀번호 재설정을 요청하셨습니다.

                        아래 링크에서 새 비밀번호를 설정할 수 있습니다. 링크는 30분 동안만 유효하며 한 번만 사용할 수 있습니다.
                        https://feelm.example/reset-password?token=raw-token

                        본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 변경되지 않습니다.""");
    }

    @Test
    @DisplayName("두 종류의 제목과 본문은 서로 다르다")
    void mailKindsDoNotShareSubjectOrBody() {
        assertThat(MailKind.VERIFICATION_CODE.subject())
                .isNotEqualTo(MailKind.PASSWORD_RESET.subject());
        assertThat(MailKind.VERIFICATION_CODE.body("value"))
                .isNotEqualTo(MailKind.PASSWORD_RESET.body("value"));
    }

    @Test
    @DisplayName("log 모드는 종류를 밝히고 그 종류의 링크만 남긴다")
    void loggingSenderLogsKindSpecificLink() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingAuthMailSender.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        AuthMailSender sender = new LoggingAuthMailSender(PROPERTIES);

        try {
            sender.send(MailKind.PASSWORD_RESET, "user@example.com", "raw-token");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .anySatisfy(
                        message ->
                                assertThat(message)
                                        .contains("비밀번호 재설정 메일")
                                        .contains(
                                                "https://feelm.example/reset-password?token=raw-token"))
                .allSatisfy(message -> assertThat(message).doesNotContain("user@example.com"));
    }

    private static SimpleMailMessage captureSent(JavaMailSender mailSender) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
