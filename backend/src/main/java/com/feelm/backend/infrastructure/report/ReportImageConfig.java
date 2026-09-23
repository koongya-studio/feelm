package com.feelm.backend.infrastructure.report;

import com.feelm.backend.global.security.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReportImageProperties.class)
class ReportImageConfig {

    ReportImageConfig(ReportImageProperties properties, JwtProperties jwtProperties) {
        byte[] reportSecret = properties.signingSecret().getBytes(StandardCharsets.UTF_8);
        byte[] jwtSecret = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(reportSecret, jwtSecret)) {
            throw new IllegalArgumentException(
                    "report image signing-secret must not reuse the JWT secret");
        }
    }
}
