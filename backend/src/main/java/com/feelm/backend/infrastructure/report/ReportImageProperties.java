package com.feelm.backend.infrastructure.report;

import com.feelm.backend.domain.report.service.ReportImagePolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feelm.report-image")
public record ReportImageProperties(
        Path storagePath,
        String signingSecret,
        @DefaultValue("10m") Duration urlTtl,
        @DefaultValue("1m") Duration regenerationInterval,
        @DefaultValue("/api/v1/report-images") String downloadUrlPrefix)
        implements ReportImagePolicy {

    public ReportImageProperties {
        if (storagePath == null || storagePath.toString().isBlank() || !storagePath.isAbsolute()) {
            throw new IllegalArgumentException(
                    "report image storage-path must be an absolute path");
        }
        storagePath = storagePath.normalize();
        if (signingSecret == null || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "report image signing-secret must contain at least 32 UTF-8 bytes");
        }
        if (urlTtl == null || urlTtl.isNegative() || urlTtl.isZero()) {
            throw new IllegalArgumentException("report image url-ttl must be positive");
        }
        if (regenerationInterval == null
                || regenerationInterval.isNegative()
                || regenerationInterval.isZero()) {
            throw new IllegalArgumentException(
                    "report image regeneration-interval must be positive");
        }
        if (downloadUrlPrefix == null
                || downloadUrlPrefix.isBlank()
                || !downloadUrlPrefix.startsWith("/")
                || downloadUrlPrefix.startsWith("//")
                || downloadUrlPrefix.contains("..")
                || downloadUrlPrefix.contains("\\")
                || downloadUrlPrefix.contains("?")
                || downloadUrlPrefix.contains("#")) {
            throw new IllegalArgumentException(
                    "report image download-url-prefix must be a safe path");
        }
        downloadUrlPrefix = downloadUrlPrefix.replaceAll("/+$", "");
        if (downloadUrlPrefix.isEmpty()) {
            throw new IllegalArgumentException(
                    "report image download-url-prefix must not be empty");
        }
    }
}
