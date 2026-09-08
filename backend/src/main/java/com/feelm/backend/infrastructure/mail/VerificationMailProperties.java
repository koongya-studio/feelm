package com.feelm.backend.infrastructure.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 계정 관련 메일 발송 설정값이다. {@code feelm.mail} 아래의 값을 모두 담는다.
 *
 * <p>기본값은 로컬·테스트 기준(플랜 결정 6)이며, dev·운영 프로파일은 {@code smtp} 모드와 실제 주소를 환경변수로 주입한다. Secret인 SMTP 비밀번호는
 * 이 설정이 아니라 Spring의 {@code spring.mail.password}로 주입하고 소스에 기본값을 두지 않는다({@code
 * docs/secret-environment-variables.md}).
 *
 * <p>가입 인증 메일과 비밀번호 재설정 메일이 {@code mode}·{@code from}을 공유하므로 설정도 한 record에 둔다. 이름은 가입 인증만 있던
 * 시점(S15P21E106-201)에 정해진 것이라 지금의 범위보다 좁다. 개명은 이 업무의 diff를 넓히지 않기 위해 미뤘다(S15P21E106-151 플랜 결정 E).
 */
@ConfigurationProperties(prefix = "feelm.mail")
public record VerificationMailProperties(
        Mode mode,
        @DefaultValue("no-reply@feelm.local") String from,
        @DefaultValue("http://localhost:5173/verify-email") String verificationUrl,
        @DefaultValue("http://localhost:5173/reset-password") String passwordResetUrl) {

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

    /**
     * 비밀번호 재설정 링크를 만든다(API-010).
     *
     * <p>인증 링크와 같은 방식이다. 링크는 프론트 화면으로 가고 프론트가 API-011을 호출한다. 토큰은 Base64URL(패딩 없음) 문자만 쓰므로 별도 URL
     * 인코딩이 필요하지 않다.
     */
    public String passwordResetLink(String passwordResetToken) {
        return passwordResetUrl + "?token=" + passwordResetToken;
    }
}
