package com.feelm.backend.infrastructure.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP로 계정 메일을 보낸다. dev·운영 프로파일의 기본 구현이다(플랜 결정 5).
 *
 * <p>제목과 본문은 {@link MailKind}가, 링크는 {@link AuthMailProperties}가 종류별로 만든다. 이 클래스는 두 메일 종류에서 달라지지 않는
 * 부분(발신 주소, 수신 주소, 전송)만 담당한다(S15P21E106-284).
 */
class SmtpAuthMailSender implements AuthMailSender {

    private final JavaMailSender mailSender;
    private final AuthMailProperties properties;

    SmtpAuthMailSender(JavaMailSender mailSender, AuthMailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(MailKind kind, String email, String rawToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(kind.subject());
        message.setText(kind.body(properties.link(kind, rawToken)));
        mailSender.send(message);
    }
}
