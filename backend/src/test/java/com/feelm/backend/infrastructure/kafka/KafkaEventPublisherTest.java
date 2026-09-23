package com.feelm.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.feelm.backend.domain.party.model.PartyRefreshPayload;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@JsonTest
@DisplayName("Kafka 공통 이벤트 기록")
class KafkaEventPublisherTest {

    private static final String PARTY_REFRESH_V2_FIXTURE_SHA256 =
            "99f9260db317b52d69ef22a8b54e626bcc256cb85184f7371ed170a803de79f5";

    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("고정된 식별자와 시각으로 완성한 이벤트 JSON을 아웃박스에 저장한다")
    void storesCompletedEventJson() throws Exception {
        OutboxEventStore store = mock(OutboxEventStore.class);
        UUID eventId = UUID.fromString("0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa");
        Instant occurredAt = Instant.parse("2026-09-07T00:00:00Z");
        KafkaEventPublisher publisher =
                new KafkaEventPublisher(
                        store,
                        objectMapper,
                        Clock.fixed(occurredAt, ZoneOffset.UTC),
                        () -> eventId);

        UUID publishedEventId =
                publisher.publish(
                        KafkaEventType.RATING,
                        1024L,
                        new RatingEventPayload(RatingAction.CREATE, 550L, new BigDecimal("4.5")));

        ArgumentCaptor<KafkaEventEnvelope<?>> eventCaptor =
                ArgumentCaptor.forClass(KafkaEventEnvelope.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).save(eventCaptor.capture(), messageCaptor.capture());
        KafkaEventEnvelope<?> stored = eventCaptor.getValue();
        JsonNode message = objectMapper.readTree(messageCaptor.getValue());
        assertThat(publishedEventId).isEqualTo(eventId);
        assertThat(stored.eventId()).isEqualTo(eventId);
        assertThat(stored.occurredAt()).isEqualTo(occurredAt);
        assertThat(stored.userId()).isEqualTo(1024L);
        assertThat(stored.eventType()).isEqualTo(KafkaEventType.RATING);
        assertThat(message.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(message.get("payload").get("movieId").asLong()).isEqualTo(550L);
        assertThat(message.get("payload").get("score").decimalValue()).isEqualByComparingTo("4.5");
    }

    @Test
    @DisplayName("파티 refresh는 사용자 이벤트 봉투 없이 전용 JSON을 아웃박스에 저장한다")
    void storesPartyRefreshBody() throws Exception {
        OutboxEventStore store = mock(OutboxEventStore.class);
        UUID eventId = UUID.fromString("0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa");
        Instant occurredAt = Instant.parse("2026-09-07T00:00:00Z");
        KafkaEventPublisher publisher =
                new KafkaEventPublisher(
                        store,
                        objectMapper,
                        Clock.fixed(occurredAt, ZoneOffset.UTC),
                        () -> eventId);

        publisher.publishPartyRefresh(
                9001L,
                new PartyRefreshPayload(
                        PartyRefreshPayload.SCHEMA_VERSION,
                        9001L,
                        "sha256:" + "c".repeat(64),
                        "gbt-v26",
                        "serving-r1",
                        List.of(
                                new PartyRefreshPayload.Member(
                                        1024L,
                                        "g1",
                                        "gbt-input-v1",
                                        "sha256:" + "a".repeat(64),
                                        List.of(550L)))));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(store)
                .savePartyRefresh(
                        org.mockito.ArgumentMatchers.eq(eventId),
                        org.mockito.ArgumentMatchers.eq(9001L),
                        message.capture(),
                        org.mockito.ArgumentMatchers.eq(occurredAt));
        JsonNode body = objectMapper.readTree(message.getValue());
        assertThat(body).isEqualTo(sharedPartyRefreshFixture());
    }

    private JsonNode sharedPartyRefreshFixture() throws Exception {
        byte[] fixture;
        try (InputStream input =
                Objects.requireNonNull(
                        getClass().getResourceAsStream("/contracts/party-refresh-v2.json"))) {
            fixture = input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture)))
                .isEqualTo(PARTY_REFRESH_V2_FIXTURE_SHA256);
        return objectMapper.readTree(fixture);
    }
}
