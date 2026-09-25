package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.model.NotificationTargetType;
import com.feelm.backend.domain.notification.model.NotificationType;
import com.feelm.backend.domain.notification.repository.WebPushDeliveryRepository.ClaimedDelivery;
import com.feelm.backend.domain.notification.service.WebPushPayloadFactory;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WebPushPayloadContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebPushPayloadFactory factory = new WebPushPayloadFactory(objectMapper);

    @Test
    void serializesOnlyDocumentedDeepLinkData() throws Exception {
        for (NotificationType type : NotificationType.values()) {
            NotificationTargetType targetType =
                    switch (type) {
                        case PARTY_INVITE -> NotificationTargetType.PARTY_INVITATION;
                        case PARTY_INVITE_ACCEPTED -> NotificationTargetType.PARTY;
                        case WATCH_CONFIRM -> NotificationTargetType.WATCH_RECORD;
                        case REPORT_ISSUED -> NotificationTargetType.TASTE_REPORT;
                    };
            ClaimedDelivery delivery =
                    new ClaimedDelivery(
                            1,
                            2,
                            3,
                            1,
                            UUID.randomUUID(),
                            type,
                            "제목",
                            "본문",
                            targetType,
                            4,
                            Instant.parse("2026-09-19T00:00:00Z"),
                            "https://secret.example/endpoint",
                            "secret-p256dh",
                            "secret-auth",
                            1L);

            JsonNode payload = objectMapper.readTree(factory.create(delivery));

            assertThat(payload.propertyNames())
                    .containsExactlyInAnyOrderElementsOf(
                            Set.of(
                                    "notificationId",
                                    "type",
                                    "title",
                                    "body",
                                    "targetType",
                                    "targetId"));
            assertThat(payload.toString())
                    .doesNotContain("secret.example", "secret-p256dh", "secret-auth");
            assertThat(payload.path("type").asText()).isEqualTo(type.name());
            assertThat(payload.path("targetType").asText()).isEqualTo(targetType.name());
        }
    }
}
