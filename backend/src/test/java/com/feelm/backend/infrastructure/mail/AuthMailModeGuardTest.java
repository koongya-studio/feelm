package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.infrastructure.mail.AuthMailProperties.Mode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code log} 모드 기동 가드(플랜 결정 13)를 검증한다. */
@DisplayName("계정 메일 모드 가드")
class AuthMailModeGuardTest {

    @ParameterizedTest(name = "프로파일 {0}")
    @ValueSource(strings = {"default", "local", "test"})
    @DisplayName("log 모드는 로컬·테스트 프로파일에서 허용된다")
    void logModeIsAllowedOnLocalProfiles(String profile) {
        assertThatCode(() -> AuthMailModeGuard.verify(Mode.LOG, List.of(profile)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("log 모드가 dev 프로파일에서 켜지면 기동에 실패한다")
    void logModeIsRejectedOnDeployedProfiles() {
        assertThatThrownBy(() -> AuthMailModeGuard.verify(Mode.LOG, List.of("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev");
    }

    @Test
    @DisplayName("허용 프로파일과 함께 지정돼도 허용되지 않은 프로파일이 있으면 실패한다")
    void logModeIsRejectedWhenAnyProfileIsNotAllowed() {
        assertThatThrownBy(() -> AuthMailModeGuard.verify(Mode.LOG, List.of("local", "prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }

    @Test
    @DisplayName("모드가 설정되지 않으면 어떤 프로파일에서도 기동에 실패한다")
    void missingModeIsRejected() {
        assertThatThrownBy(() -> AuthMailModeGuard.verify(null, List.of("local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_MODE");
    }

    @Test
    @DisplayName("smtp 모드는 프로파일과 무관하게 통과한다")
    void smtpModeIsAlwaysAllowed() {
        assertThatCode(() -> AuthMailModeGuard.verify(Mode.SMTP, List.of("dev", "prod")))
                .doesNotThrowAnyException();
    }
}
