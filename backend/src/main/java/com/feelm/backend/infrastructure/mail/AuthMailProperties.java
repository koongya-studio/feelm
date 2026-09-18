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
 * <p>가입 인증 메일과 비밀번호 재설정 메일이 {@code mode}·{@code from}을 공유하므로 설정도 한 record에 둔다. 이름이 {@code
 * VerificationMailProperties}였던 것은 가입 인증만 있던 시점(S15P21E106-201)의 흔적이라 실제 범위보다 좁았고, 계정 메일 계열을 하나로
 * 합치는 S15P21E106-284에서 {@code AuthMail}로 개명했다. 설정 키({@code feelm.mail.*})는 그대로다.
 */
@ConfigurationProperties(prefix = "feelm.mail")
public record AuthMailProperties(
        Mode mode,
        @DefaultValue("no-reply@feelm.local") String from,
        @DefaultValue("http://localhost:5173/app/verify-email") String verificationUrl,
        @DefaultValue("http://localhost:5173/app/account/reset") String passwordResetUrl) {

    /** 발송 수단. {@code LOG}는 실제로 보내지 않고 링크만 남기는 로컬 확인용이다. */
    public enum Mode {
        SMTP,
        LOG
    }

    /**
     * 메일 종류에 맞는 본문 값을 만든다(플랜 결정 7). 링크를 쓰는 종류는 화면 주소에 토큰을 붙이고, 인증번호 메일은 번호를 그대로 쓴다.
     *
     * <p>링크는 프론트 화면으로 가고 프론트가 API를 호출한다(인증은 API-002, 재설정은 API-011). 토큰은 Base64URL(패딩 없음) 문자만 쓰므로 별도
     * URL 인코딩이 필요하지 않다.
     *
     * <p><b>경로는 프론트엔드 라우트에서 파생된 값이다.</b> 앱이 {@code /app} 아래에 있고 화면별 경로는 프론트엔드가 가지므로, 프론트 경로가 바뀌면 이
     * 기본값과 {@code application.yml}의 설정값이 함께 바뀌어야 한다. 값이 어긋나면 메일은 정상 발송되는데 링크를 눌러야 드러나므로 배포 후에야
     * 발견된다(S15P21E106-367).
     *
     * <p>종류별 분기를 발송 구현이 아니라 여기에 두는 이유는 화면 주소가 설정값이기 때문이다. 종류가 늘면 {@link MailKind}에 값을 추가하고 이 분기에 그
     * 주소를 연결한다. {@code switch}에 default를 두지 않아 값을 추가하면 컴파일이 실패하며 여기를 가리킨다.
     */
    public String content(MailKind kind, String rawValue) {
        return switch (kind) {
            case VERIFICATION -> verificationUrl + "?token=" + rawValue;
            case PASSWORD_RESET -> passwordResetUrl + "?token=" + rawValue;
            // 인증번호 메일(API-078)에는 링크가 없다. 번호 자체가 본문에 들어가는 값이다.
            case VERIFICATION_CODE -> rawValue;
        };
    }
}
