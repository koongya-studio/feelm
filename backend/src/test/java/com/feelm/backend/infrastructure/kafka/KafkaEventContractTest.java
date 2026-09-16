package com.feelm.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Kafka 공통 이벤트 계약")
class KafkaEventContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    @DisplayName("이벤트 종류를 확정된 토픽에 매핑한다")
    void mapsEventTypesToTopics() {
        assertThat(KafkaEventType.RATING.topic()).isEqualTo("feelm.rating.v1");
        assertThat(KafkaEventType.ONBOARDING.topic()).isEqualTo("feelm.rating.v1");
        assertThat(KafkaEventType.DISMISS.topic()).isEqualTo("feelm.dismiss.v1");
        assertThat(KafkaEventType.VIEW.topic()).isEqualTo("feelm.view.v1");
        assertThat(KafkaEventType.RECOMMENDATION_CLICK.topic())
                .isEqualTo("feelm.recommendation-click.v1");
        assertThat(KafkaEventType.OTT_CLICK.topic()).isEqualTo("feelm.ott-click.v1");
    }

    @Test
    @DisplayName("공통 봉투와 평가 payload를 계약 JSON으로 직렬화한다")
    void serializesRatingEnvelope() throws Exception {
        UUID eventId = UUID.fromString("0f3c9a1e-6b2d-4f77-9a10-5c2b8e41d0aa");
        KafkaEventEnvelope<RatingEventPayload> event =
                new KafkaEventEnvelope<>(
                        eventId,
                        KafkaEventType.RATING,
                        Instant.parse("2026-08-31T07:21:00Z"),
                        1024L,
                        new RatingEventPayload(RatingAction.CREATE, 550L, new BigDecimal("4.5")));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("eventType").asText()).isEqualTo("RATING");
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-08-31T07:21:00Z");
        assertThat(json.get("userId").asLong()).isEqualTo(1024L);
        assertThat(json.get("payload").get("action").asText()).isEqualTo("CREATE");
        assertThat(json.get("payload").get("movieId").asLong()).isEqualTo(550L);
        assertThat(json.get("payload").get("score").decimalValue()).isEqualByComparingTo("4.5");
    }

    @Test
    @DisplayName("평가 삭제 payload는 score를 생략한다")
    void omitsScoreForRatingDelete() throws Exception {
        RatingEventPayload payload = new RatingEventPayload(RatingAction.DELETE, 550L, null);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertThat(json.has("score")).isFalse();
    }
}
