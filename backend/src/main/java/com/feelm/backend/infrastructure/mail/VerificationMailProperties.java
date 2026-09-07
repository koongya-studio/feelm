package com.feelm.backend.infrastructure.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 메일 발송 설정값이다.
 *
 * <p>기본값은 로컬·테스트 기준(플랜 결정 6)이며, dev·운영 프로파일은 {@code smtp} 모드와 실제 주소를 환경변수로 주입한다. Secret인 SMTP 비밀번호는
 * 이 설정이 아니라 Spring의 {@code spring.mail.password}로 주입하고 소스에 기본값을 두지 않는다({@code
 * docs/secret-environment-variables.md}).
 */
@ConfigurationProperties(prefix = "feelm.mail")
public record VerificationMailProperties(
        Mode mode,
        @DefaultValue("no-reply@feelm.local") String from,
        @DefaultValue("http://localhost:5173/verify-email") String verificationUrl) {

    /** 발송 수단. {@code LOG}는 실제로 보내지 않고 링크만 남기는 로컬 확인용이다. */
    public enum Mode {
        SMTP,
        LOG
    }

    /**
     * 인증 링크를 만든다(플랜 결정 7).
     *
     * <p>링크는 프론트 화면으로 가고 프론트가 API-002를 호출한다. 토큰은 Base64URL(패딩 없음) 문자만 쓰므로 별도 URL 인코딩이 필요하지 않다.
     */
    public String verificationLink(String verificationToken) {
        return verificationUrl + "?token=" + verificationToken;
    }
}
