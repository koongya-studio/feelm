package com.feelm.backend.infrastructure.report;

import com.feelm.backend.domain.report.service.ReportImageUrlSigner;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
class HmacReportImageUrlSigner implements ReportImageUrlSigner {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final int ENCODED_SIGNATURE_LENGTH = 43;

    private final ReportImageProperties properties;
    private final Clock clock;

    @Override
    public SignedUrl issue(long imageId) {
        Instant expiresAt =
                clock.instant().plus(properties.urlTtl()).truncatedTo(ChronoUnit.SECONDS);
        long expires = expiresAt.getEpochSecond();
        String signature = encode(sign(imageId, expires));
        String url =
                UriComponentsBuilder.fromPath(properties.downloadUrlPrefix())
                        .pathSegment(Long.toString(imageId), "download")
                        .queryParam("expires", expires)
                        .queryParam("signature", signature)
                        .build()
                        .toUriString();
        return new SignedUrl(url, expiresAt);
    }

    @Override
    public boolean verify(long imageId, long expiresEpochSecond, String signature) {
        if (imageId <= 0
                || expiresEpochSecond <= clock.instant().getEpochSecond()
                || signature == null
                || signature.length() != ENCODED_SIGNATURE_LENGTH) {
            return false;
        }
        try {
            byte[] supplied = Base64.getUrlDecoder().decode(signature);
            return MessageDigest.isEqual(sign(imageId, expiresEpochSecond), supplied);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] sign(long imageId, long expiresEpochSecond) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(
                    new SecretKeySpec(
                            properties.signingSecret().getBytes(StandardCharsets.UTF_8),
                            HMAC_SHA_256));
            String payload = imageId + "\n" + expiresEpochSecond;
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String encode(byte[] signature) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }
}
