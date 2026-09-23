package com.feelm.backend.infrastructure.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.report.service.ReportImageUrlSigner.SignedUrl;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("리포트 이미지 HMAC URL")
class HmacReportImageUrlSignerTest {

    private static final Instant NOW = Instant.parse("2030-06-30T15:00:00Z");
    private static final String SECRET = "report-image-signing-key-at-least-32-bytes";

    @TempDir Path temporary;

    @Test
    @DisplayName("imageId와 만료 시각을 서명하고 정확히 10분간 검증한다")
    void signsAndVerifiesForTenMinutes() {
        HmacReportImageUrlSigner signer = signerAt(NOW);

        SignedUrl signed = signer.issue(811);
        Map<String, String> query = query(signed.url());
        long expires = Long.parseLong(query.get("expires"));

        assertThat(signed.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(signed.url()).startsWith("/api/v1/report-images/811/download?");
        assertThat(signer.verify(811, expires, query.get("signature"))).isTrue();
        assertThat(signer.verify(812, expires, query.get("signature"))).isFalse();
        assertThat(signer.verify(811, expires + 1, query.get("signature"))).isFalse();
        assertThat(signer.verify(811, expires, "A" + query.get("signature"))).isFalse();

        assertThat(signerAt(signed.expiresAt()).verify(811, expires, query.get("signature")))
                .isFalse();
    }

    private HmacReportImageUrlSigner signerAt(Instant instant) {
        ReportImageProperties properties =
                new ReportImageProperties(
                        temporary,
                        SECRET,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1),
                        "/api/v1/report-images");
        return new HmacReportImageUrlSigner(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private Map<String, String> query(String url) {
        return Arrays.stream(URI.create(url).getQuery().split("&"))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(value -> value[0], value -> value[1]));
    }
}
