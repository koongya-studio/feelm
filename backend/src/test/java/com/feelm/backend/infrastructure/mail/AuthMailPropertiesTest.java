package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.infrastructure.mail.AuthMailProperties.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메일 링크 생성 규칙을 검증한다.
 *
 * <p>링크는 메일 본문에만 실려 계약 테스트로 확인할 수 없다(스파이 빈은 토큰 원문만 기록한다). 프론트 경로와 쿼리 파라미터 이름이 바뀌면 사용자가 링크를 눌러도 화면이
 * 토큰을 읽지 못하므로 여기서 고정한다.
 *
 * <p>종류별 링크를 한 메서드가 분기로 만들게 됐으므로(S15P21E106-284) 종류가 뒤바뀌지 않는지도 함께 본다.
 */
@DisplayName("계정 메일 설정 — 링크 생성")
class AuthMailPropertiesTest {

    private static final AuthMailProperties PROPERTIES =
            new AuthMailProperties(
                    Mode.LOG, "no-reply@feelm.local", "https://feelm.example/reset-password");

    @Test
    @DisplayName("재설정 링크는 설정된 화면 주소에 token 쿼리를 붙인다")
    void passwordResetLinkAppendsToken() {
        assertThat(PROPERTIES.content(MailKind.PASSWORD_RESET, "raw-token"))
                .isEqualTo("https://feelm.example/reset-password?token=raw-token");
    }

    /** 인증번호 메일에는 링크가 없다. 번호 자체가 본문에 들어가는 값이다(S15P21E106-602·604). */
    @Test
    @DisplayName("인증번호 메일은 값을 그대로 쓴다")
    void verificationCodeUsesRawValue() {
        assertThat(PROPERTIES.content(MailKind.VERIFICATION_CODE, "032815")).isEqualTo("032815");
    }
}
