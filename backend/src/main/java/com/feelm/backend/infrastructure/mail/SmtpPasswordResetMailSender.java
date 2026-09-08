package com.feelm.backend.infrastructure.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP로 비밀번호 재설정 메일을 보낸다. dev·운영 프로파일의 기본 구현이다. */
class SmtpPasswordResetMailSender implements PasswordResetMailSender {

    private static final String SUBJECT = "[Feelm] 비밀번호 재설정 안내";

    private final JavaMailSender mailSender;
    private final VerificationMailProperties properties;

    SmtpPasswordResetMailSender(JavaMailSender mailSender, VerificationMailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String email, String passwordResetToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(SUBJECT);
        message.setText(body(passwordResetToken));
        mailSender.send(message);
    }

    private String body(String passwordResetToken) {
        return """
                Feelm 비밀번호 재설정을 요청하셨습니다.

                아래 링크에서 새 비밀번호를 설정할 수 있습니다. 링크는 30분 동안만 유효하며 한 번만 사용할 수 있습니다.
                %s

                본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 변경되지 않습니다."""
                .formatted(properties.passwordResetLink(passwordResetToken));
    }
}
