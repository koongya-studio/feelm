package com.feelm.backend.infrastructure.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP로 인증 메일을 보낸다. dev·운영 프로파일의 기본 구현이다(플랜 결정 5). */
class SmtpVerificationMailSender implements VerificationMailSender {

    private static final String SUBJECT = "[Feelm] 이메일 인증을 완료해 주세요";

    private final JavaMailSender mailSender;
    private final VerificationMailProperties properties;

    SmtpVerificationMailSender(JavaMailSender mailSender, VerificationMailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String email, String verificationToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(SUBJECT);
        message.setText(body(verificationToken));
        mailSender.send(message);
    }

    private String body(String verificationToken) {
        return """
                Feelm 가입을 확인해 주세요.

                아래 링크를 열면 이메일 인증이 완료됩니다. 링크는 24시간 동안만 유효합니다.
                %s

                본인이 요청하지 않았다면 이 메일을 무시해 주세요."""
                .formatted(properties.verificationLink(verificationToken));
    }
}
