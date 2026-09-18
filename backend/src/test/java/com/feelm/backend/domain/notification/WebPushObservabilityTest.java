package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.notification.config.WebPushProperties;
import com.feelm.backend.domain.notification.config.WebPushProperties.Mode;
import com.feelm.backend.domain.notification.model.NotificationTargetType;
import com.feelm.backend.domain.notification.model.NotificationType;
import com.feelm.backend.domain.notification.push.WebPushGateway;
import com.feelm.backend.domain.notification.push.WebPushSendResult;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository.ClaimedDelivery;
import com.feelm.backend.domain.notification.repository.WebPushSubscriptionRepository;
import com.feelm.backend.domain.notification.service.WebPushDeliveryWorker;
import com.feelm.backend.domain.notification.service.WebPushPayloadFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
@ResourceLock("backend-output-capture")
class WebPushObservabilityTest {

    @Test
    void neverLogsEndpointKeysOrPayload(CapturedOutput output) {
        WebPushDeliveryRepository deliveries = mock(WebPushDeliveryRepository.class);
        WebPushSubscriptionRepository subscriptions = mock(WebPushSubscriptionRepository.class);
        WebPushGateway gateway = mock(WebPushGateway.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        UUID token = UUID.randomUUID();
        ClaimedDelivery delivery =
                new ClaimedDelivery(
                        91,
                        92,
                        93,
                        1,
                        token,
                        NotificationType.PARTY_INVITE,
                        "secret-title",
                        "secret-body",
                        NotificationTargetType.PARTY_INVITATION,
                        94,
                        now.plusSeconds(600),
                        "https://secret.example/endpoint",
                        "secret-p256dh",
                        "secret-auth",
                        1L);
        when(deliveries.claimDue(any(), any(), anyInt(), any()))
                .thenReturn(List.of(delivery), List.of());
        when(deliveries.isClaimEligible(91, token, now)).thenReturn(true);
        when(deliveries.scheduleRetry(eq(91L), eq(token), any(), eq(null), eq("GATEWAY_ERROR")))
                .thenReturn(true);
        when(gateway.send(any()))
                .thenThrow(
                        new IllegalStateException(
                                "https://secret.example/endpoint secret-p256dh secret-auth"));

        new WebPushDeliveryWorker(
                        deliveries,
                        subscriptions,
                        gateway,
                        properties(),
                        new WebPushPayloadFactory(new ObjectMapper()),
                        meterRegistry,
                        Clock.fixed(now, ZoneOffset.UTC))
                .dispatch();

        assertThat(output).contains("deliveryId=91");
        assertThat(output)
                .doesNotContain(
                        "secret.example",
                        "secret-p256dh",
                        "secret-auth",
                        "secret-title",
                        "secret-body");
        assertThat(meterRegistry.counter("feelm.web.push.delivery", "outcome", "retry").count())
                .isEqualTo(1.0);
        verify(deliveries).scheduleRetry(eq(91L), eq(token), any(), eq(null), eq("GATEWAY_ERROR"));
    }

    @Test
    void countsPersistedSuccessPermanentFailureAndExpiredCapability(CapturedOutput output) {
        WebPushDeliveryRepository deliveries = mock(WebPushDeliveryRepository.class);
        WebPushSubscriptionRepository subscriptions = mock(WebPushSubscriptionRepository.class);
        WebPushGateway gateway = mock(WebPushGateway.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        ClaimedDelivery sent = delivery(101, 201, UUID.randomUUID(), now);
        ClaimedDelivery failed = delivery(102, 202, UUID.randomUUID(), now);
        ClaimedDelivery expired = delivery(103, 203, UUID.randomUUID(), now);
        when(deliveries.claimDue(any(), any(), anyInt(), any()))
                .thenReturn(List.of(sent), List.of(failed), List.of(expired), List.of());
        when(deliveries.isClaimEligible(anyLong(), any(), eq(now))).thenReturn(true);
        when(deliveries.markSent(101, sent.claimToken(), now, 201)).thenReturn(true);
        when(deliveries.markFailed(102, failed.claimToken(), now, 400, "INVALID_REQUEST"))
                .thenReturn(true);
        when(deliveries.markFailed(103, expired.claimToken(), now, 410, "SUBSCRIPTION_EXPIRED"))
                .thenReturn(true);
        when(subscriptions.deactivateForDelivery(203, 1L, now)).thenReturn(true);
        when(gateway.send(any()))
                .thenReturn(
                        WebPushSendResult.sent(201),
                        WebPushSendResult.permanent(400, "INVALID_REQUEST"),
                        WebPushSendResult.expired(410));

        new WebPushDeliveryWorker(
                        deliveries,
                        subscriptions,
                        gateway,
                        properties(),
                        new WebPushPayloadFactory(new ObjectMapper()),
                        meterRegistry,
                        Clock.fixed(now, ZoneOffset.UTC))
                .dispatch();

        assertThat(meterRegistry.counter("feelm.web.push.delivery", "outcome", "sent").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("feelm.web.push.delivery", "outcome", "failed").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("feelm.web.push.delivery", "outcome", "expired").count())
                .isEqualTo(1.0);
        assertThat(output)
                .doesNotContain(
                        "secret.example",
                        "secret-p256dh",
                        "secret-auth",
                        "secret-title",
                        "secret-body");
    }

    private static ClaimedDelivery delivery(
            long deliveryId, long subscriptionId, UUID token, Instant now) {
        return new ClaimedDelivery(
                deliveryId,
                deliveryId + 1_000,
                subscriptionId,
                1,
                token,
                NotificationType.PARTY_INVITE,
                "secret-title",
                "secret-body",
                NotificationTargetType.PARTY_INVITATION,
                deliveryId + 2_000,
                now.plusSeconds(600),
                "https://secret.example/endpoint",
                "secret-p256dh",
                "secret-auth",
                1L);
    }

    private static WebPushProperties properties() {
        return new WebPushProperties(
                Mode.VAPID,
                "public",
                "private",
                "mailto:test@feelm.local",
                List.of("fcm.googleapis.com"),
                25,
                5,
                Duration.ofMinutes(1),
                Duration.ofHours(1),
                Duration.ofMinutes(2),
                Duration.ofSeconds(15));
    }
}
