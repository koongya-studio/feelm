package com.feelm.backend.domain.notification.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "feelm.notification.web-push")
public record WebPushProperties(
        @DefaultValue("disabled") Mode mode,
        String publicKey,
        String privateKey,
        String subject,
        @DefaultValue({"fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com"})
                List<String> allowedEndpointHostSuffixes,
        @DefaultValue("25") int batchSize,
        @DefaultValue("5") int maximumAttempts,
        @DefaultValue("1m") Duration initialRetryDelay,
        @DefaultValue("1h") Duration maximumRetryDelay,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("15s") Duration requestTimeout) {

    public WebPushProperties {
        mode = mode == null ? Mode.DISABLED : mode;
        allowedEndpointHostSuffixes =
                allowedEndpointHostSuffixes == null
                        ? List.of()
                        : allowedEndpointHostSuffixes.stream()
                                .map(WebPushProperties::normalizeHostSuffix)
                                .filter(value -> !value.isBlank())
                                .distinct()
                                .toList();
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("web push batch-size는 1~100이어야 합니다");
        }
        if (maximumAttempts < 1 || maximumAttempts > 20) {
            throw new IllegalArgumentException("web push maximum-attempts는 1~20이어야 합니다");
        }
        requirePositive(initialRetryDelay, "initial-retry-delay");
        requirePositive(maximumRetryDelay, "maximum-retry-delay");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(requestTimeout, "request-timeout");
        if (initialRetryDelay.compareTo(maximumRetryDelay) > 0) {
            throw new IllegalArgumentException(
                    "web push initial-retry-delay는 maximum-retry-delay 이하여야 합니다");
        }
        if (requestTimeout.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "web push request-timeout은 lease-duration보다 짧아야 합니다");
        }
        if (mode == Mode.VAPID) {
            requireText(publicKey, "public-key");
            requireText(privateKey, "private-key");
            requireText(subject, "subject");
            if (allowedEndpointHostSuffixes.isEmpty()) {
                throw new IllegalArgumentException(
                        "web push allowed-endpoint-host-suffixes는 비어 있을 수 없습니다");
            }
            URI subjectUri = parseUri(subject, "subject");
            if (!("mailto".equalsIgnoreCase(subjectUri.getScheme())
                    || "https".equalsIgnoreCase(subjectUri.getScheme()))) {
                throw new IllegalArgumentException("web push subject는 mailto 또는 https URI여야 합니다");
            }
        }
    }

    public boolean enabled() {
        return mode == Mode.VAPID;
    }

    public boolean allows(URI endpoint) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            return false;
        }
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        return allowedEndpointHostSuffixes.stream()
                .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
    }

    private static String normalizeHostSuffix(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("*.") || normalized.startsWith(".")) {
            normalized = normalized.substring(normalized.indexOf('.') + 1);
        }
        return normalized;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("web push " + name + "가 필요합니다");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("web push " + name + "는 0보다 커야 합니다");
        }
    }

    private static URI parseUri(String value, String name) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("web push " + name + " URI가 올바르지 않습니다", exception);
        }
    }

    public enum Mode {
        DISABLED,
        VAPID
    }
}
