package com.feelm.backend.domain.notification.service;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.push.WebPushGateway;
import com.feelm.backend.domain.notification.push.WebPushMessage;
import com.feelm.backend.domain.notification.push.WebPushSendResult;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository.ClaimedDelivery;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "feelm.notification.web-push.mode", havingValue = "vapid")
public class WebPushDeliveryWorker {

    private final WebPushDeliveryRepository deliveryRepository;
    private final WebPushSubscriptionRepository subscriptionRepository;
    private final WebPushGateway gateway;
    private final WebPushProperties properties;
    private final WebPushPayloadFactory payloadFactory;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Scheduled(
            initialDelayString = "${feelm.notification.web-push.poll-interval:1s}",
            fixedDelayString = "${feelm.notification.web-push.poll-interval:1s}")
    public void dispatch() {
        Instant now = Instant.now(clock);
        int expiredSubscriptions = subscriptionRepository.deactivateExpired(now);
        if (expiredSubscriptions > 0) {
            meterRegistry
                    .counter("feelm.web.push.subscription.cleanup", "reason", "expired")
                    .increment(expiredSubscriptions);
        }
        deliveryRepository.cancelIneligible(now);
        for (int index = 0; index < properties.batchSize(); index++) {
            UUID claimToken = UUID.randomUUID();
            List<ClaimedDelivery> claimed =
                    deliveryRepository.claimDue(
                            Instant.now(clock), properties.leaseDuration(), 1, claimToken);
            if (claimed.isEmpty()) {
                break;
            }
            send(claimed.getFirst());
        }
    }

    private void send(ClaimedDelivery delivery) {
        if (!deliveryRepository.isClaimEligible(
                delivery.deliveryId(), delivery.claimToken(), Instant.now(clock))) {
            return;
        }
        WebPushSendResult result;
        try {
            result =
                    gateway.send(
                            new WebPushMessage(
                                    delivery.deliveryId(),
                                    delivery.notificationId(),
                                    delivery.endpoint(),
                                    delivery.p256dh(),
                                    delivery.auth(),
                                    payloadFactory.create(delivery),
                                    delivery.expiresAt()));
        } catch (RuntimeException exception) {
            log.warn("Web Push gateway failed unexpectedly: deliveryId={}", delivery.deliveryId());
            result = WebPushSendResult.retryable(null, "GATEWAY_ERROR");
        }
        complete(delivery, result);
    }

    private void complete(ClaimedDelivery delivery, WebPushSendResult result) {
        Instant now = Instant.now(clock);
        switch (result.kind()) {
            case SENT -> {
                if (!deliveryRepository.markSent(
                        delivery.deliveryId(), delivery.claimToken(), now, result.httpStatus())) {
                    return;
                }
                log.debug(
                        "Web Push delivery completed: deliveryId={}, notificationId={},"
                                + " attemptCount={}, outcome=SENT, httpStatus={}",
                        delivery.deliveryId(),
                        delivery.notificationId(),
                        delivery.attemptCount(),
                        result.httpStatus());
                count("sent");
            }
            case EXPIRED -> {
                if (!deliveryRepository.markFailed(
                        delivery.deliveryId(),
                        delivery.claimToken(),
                        now,
                        result.httpStatus(),
                        result.failureCode())) {
                    return;
                }
                subscriptionRepository.deactivateForDelivery(
                        delivery.subscriptionId(), delivery.capabilityVersion(), now);
                log.info(
                        "Web Push subscription expired: deliveryId={}, notificationId={},"
                                + " attemptCount={}, httpStatus={}",
                        delivery.deliveryId(),
                        delivery.notificationId(),
                        delivery.attemptCount(),
                        result.httpStatus());
                count("expired");
            }
            case RETRYABLE_FAILURE -> retryOrFail(delivery, result, now);
            case PERMANENT_FAILURE -> {
                if (!deliveryRepository.markFailed(
                        delivery.deliveryId(),
                        delivery.claimToken(),
                        now,
                        result.httpStatus(),
                        result.failureCode())) {
                    return;
                }
                log.warn(
                        "Web Push delivery failed: deliveryId={}, notificationId={},"
                                + " attemptCount={}, failureCode={}, httpStatus={}",
                        delivery.deliveryId(),
                        delivery.notificationId(),
                        delivery.attemptCount(),
                        result.failureCode(),
                        result.httpStatus());
                count("failed");
            }
        }
    }

    private void retryOrFail(ClaimedDelivery delivery, WebPushSendResult result, Instant now) {
        if (delivery.attemptCount() >= properties.maximumAttempts()) {
            if (!deliveryRepository.markFailed(
                    delivery.deliveryId(),
                    delivery.claimToken(),
                    now,
                    result.httpStatus(),
                    "RETRY_EXHAUSTED")) {
                return;
            }
            log.warn(
                    "Web Push retry exhausted: deliveryId={}, notificationId={},"
                            + " attemptCount={}, httpStatus={}",
                    delivery.deliveryId(),
                    delivery.notificationId(),
                    delivery.attemptCount(),
                    result.httpStatus());
            count("failed");
            return;
        }
        Instant calculatedNextAttemptAt =
                now.plus(
                        WebPushRetryPolicy.delay(
                                properties.initialRetryDelay(),
                                properties.maximumRetryDelay(),
                                delivery.attemptCount()));
        Instant nextAttemptAt =
                calculatedNextAttemptAt.isAfter(delivery.expiresAt())
                        ? delivery.expiresAt()
                        : calculatedNextAttemptAt;
        if (!deliveryRepository.scheduleRetry(
                delivery.deliveryId(),
                delivery.claimToken(),
                nextAttemptAt,
                result.httpStatus(),
                result.failureCode())) {
            return;
        }
        log.info(
                "Web Push retry scheduled: deliveryId={}, notificationId={}, attemptCount={},"
                        + " failureCode={}, httpStatus={}, nextAttemptAt={}",
                delivery.deliveryId(),
                delivery.notificationId(),
                delivery.attemptCount(),
                result.failureCode(),
                result.httpStatus(),
                nextAttemptAt);
        count("retry");
    }

    private void count(String outcome) {
        meterRegistry.counter("feelm.web.push.delivery", "outcome", outcome).increment();
    }
}
