package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.infrastructure.mail.VerificationMailProperties.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메일 링크 생성 규칙을 검증한다.
 *
 * <p>링크는 메일 본문에만 실려 계약 테스트로 확인할 수 없다(스파이 빈은 토큰 원문만 기록한다). 프론트 경로와 쿼리 파라미터 이름이 바뀌면 사용자가 링크를 눌러도 화면이
 * 토큰을 읽지 못하므로 여기서 고정한다.
 */
@DisplayName("계정 메일 설정 — 링크 생성")
class VerificationMailPropertiesTest {

    private static final VerificationMailProperties PROPERTIES =
            new VerificationMailProperties(
                    Mode.LOG,
                    "no-reply@feelm.local",
                    "https://feelm.example/verify-email",
                    "https://feelm.example/reset-password");

    @Test
    @DisplayName("인증 링크는 설정된 화면 주소에 token 쿼리를 붙인다")
    void verificationLinkAppendsToken() {
        assertThat(PROPERTIES.verificationLink("raw-token"))
                .isEqualTo("https://feelm.example/verify-email?token=raw-token");
    }

    @Test
    @DisplayName("재설정 링크는 설정된 화면 주소에 token 쿼리를 붙인다")
    void passwordResetLinkAppendsToken() {
        assertThat(PROPERTIES.passwordResetLink("raw-token"))
                .isEqualTo("https://feelm.example/reset-password?token=raw-token");
    }

    @Test
    @DisplayName("두 링크는 서로 다른 화면으로 간다")
    void linksTargetDifferentScreens() {
        assertThat(PROPERTIES.passwordResetLink("raw-token"))
                .isNotEqualTo(PROPERTIES.verificationLink("raw-token"));
    }
}
