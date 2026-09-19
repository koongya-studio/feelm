package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.dto.request.WebPushSubscriptionRequest;
import com.feelm.backend.domain.notification.dto.response.WebPushConfigResponse;
import com.feelm.backend.domain.notification.dto.response.WebPushSubscriptionResponse;
import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebPushSubscriptionService {

    private final WebPushProperties properties;
    private final NotificationRepository notificationRepository;
    private final WebPushSubscriptionRepository subscriptionRepository;
    private final WebPushDeliveryRepository deliveryRepository;
    private final Clock clock;

    public WebPushConfigResponse config(long userId) {
        requireActiveUser(userId);
        return new WebPushConfigResponse(
                properties.enabled(), properties.enabled() ? properties.publicKey() : null);
    }

    @Transactional
    public WebPushSubscriptionResponse register(long userId, WebPushSubscriptionRequest request) {
        requireEnabled();
        requireActiveUser(userId);
        Instant now = Instant.now(clock);
        String endpointValue = validateAndCanonicalizeEndpoint(request.endpoint());
        validateKey(request.keys().p256dh(), 65);
        validateKey(request.keys().auth(), 16);
        Instant expirationAt = expiration(request.expirationTime(), now);
        String fingerprint = fingerprint(endpointValue);
        subscriptionRepository
                .findOwnerByFingerprint(fingerprint)
                .filter(owner -> owner.userId() != userId || !owner.active())
                .ifPresent(
                        owner ->
                                deliveryRepository.cancelForSubscription(
                                        owner.subscriptionId(),
                                        now,
                                        owner.userId() == userId
                                                ? "SUBSCRIPTION_REACTIVATED"
                                                : "SUBSCRIPTION_TRANSFERRED"));
        long subscriptionId =
                subscriptionRepository.upsert(
                        userId,
                        endpointValue,
                        fingerprint,
                        request.keys().p256dh(),
                        request.keys().auth(),
                        expirationAt);
        return new WebPushSubscriptionResponse(subscriptionId);
    }

    @Transactional
    public void unregister(long userId, long subscriptionId) {
        requireActiveUser(userId);
        Instant now = Instant.now(clock);
        if (!subscriptionRepository.deactivate(userId, subscriptionId, now)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        deliveryRepository.cancelForSubscription(subscriptionId, now, "SUBSCRIPTION_REMOVED");
    }

    private String validateAndCanonicalizeEndpoint(String value) {
        try {
            URI endpoint = URI.create(value);
            if (!properties.allows(endpoint)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            StringBuilder canonical =
                    new StringBuilder("https://")
                            .append(endpoint.getHost().toLowerCase(Locale.ROOT));
            if (endpoint.getRawPath() != null) {
                canonical.append(endpoint.getRawPath());
            }
            if (endpoint.getRawQuery() != null) {
                canonical.append('?').append(endpoint.getRawQuery());
            }
            return canonical.toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validateKey(String value, int expectedLength) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != expectedLength || (expectedLength == 65 && decoded[0] != 0x04)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (expectedLength == 65) {
                validateP256Point(decoded);
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validateP256Point(byte[] decoded) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            BigInteger prime = ((ECFieldFp) spec.getCurve().getField()).getP();
            BigInteger x = new BigInteger(1, decoded, 1, 32);
            BigInteger y = new BigInteger(1, decoded, 33, 32);
            if (x.compareTo(prime) >= 0
                    || y.compareTo(prime) >= 0
                    || !y.modPow(BigInteger.TWO, prime)
                            .equals(
                                    x.modPow(BigInteger.valueOf(3), prime)
                                            .add(spec.getCurve().getA().multiply(x))
                                            .add(spec.getCurve().getB())
                                            .mod(prime))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("secp256r1 validation is unavailable", exception);
        }
    }

    private static Instant expiration(Long expirationTime, Instant now) {
        if (expirationTime == null) {
            return null;
        }
        Instant expirationAt;
        try {
            expirationAt = Instant.ofEpochMilli(expirationTime);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!expirationAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return expirationAt;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.PUSH_UNAVAILABLE);
        }
    }

    private void requireActiveUser(long userId) {
        if (!notificationRepository.isActiveUser(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private static String fingerprint(String endpoint) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
