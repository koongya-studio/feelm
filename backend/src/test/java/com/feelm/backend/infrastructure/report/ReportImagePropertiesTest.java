package com.feelm.backend.infrastructure.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.security.JwtProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("리포트 이미지 설정")
class ReportImagePropertiesTest {

    @TempDir Path temporary;

    @Test
    @DisplayName("상대 저장 경로와 짧은 Secret을 거절한다")
    void validatesPathAndSecret() {
        assertThatThrownBy(
                        () ->
                                new ReportImageProperties(
                                        Path.of("relative"),
                                        "report-image-signing-key-at-least-32-bytes",
                                        Duration.ofMinutes(10),
                                        Duration.ofMinutes(1),
                                        "/api/v1/report-images"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ReportImageProperties(
                                        temporary,
                                        "too-short",
                                        Duration.ofMinutes(10),
                                        Duration.ofMinutes(1),
                                        "/api/v1/report-images"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JWT Secret 재사용을 기동 시점에 거절한다")
    void rejectsJwtSecretReuse() {
        String shared = "shared-signing-key-at-least-32-bytes-long";
        ReportImageProperties properties =
                new ReportImageProperties(
                        temporary,
                        shared,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1),
                        "/api/v1/report-images");
        JwtProperties jwt = new JwtProperties(shared, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ReportImageConfig(properties, jwt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not reuse");
    }
}
