package com.feelm.backend.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.feelm.backend.support.SharedPostgresContractTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class PartyRefreshOutboxOrderIntegrationTest extends SharedPostgresContractTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Autowired OutboxEventStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.execute("TRUNCATE TABLE outbox_events RESTART IDENTITY");
    }

    @Test
    void failedEarlierPartyEventBlocksOnlyThatPartyUntilPublished() {
        long first = partyEvent(9001, "g1");
        long second = partyEvent(9001, "g2");
        long otherParty = partyEvent(9002, "g1");

        assertThat(ids(store.findDue(NOW.plusSeconds(1), 100))).containsExactly(first, otherParty);

        store.markFailed(first, NOW.plusSeconds(1), NOW.plusSeconds(30), "broker unavailable");
        assertThat(ids(store.findDue(NOW.plusSeconds(2), 100))).containsExactly(otherParty);

        store.markPublished(first, NOW.plusSeconds(31));
        assertThat(ids(store.findDue(NOW.plusSeconds(32), 100)))
                .containsExactly(second, otherParty);
    }

    @Test
    void relayRetriesOlderEventBeforeSendingNewerPartyInput() {
        long first = partyEvent(9001, "g1");
        long second = partyEvent(9001, "g2");
        long otherParty = partyEvent(9002, "g1");
        OutboxEvent firstEvent = event(first);
        OutboxEvent secondEvent = event(second);
        OutboxEvent otherEvent = event(otherParty);
        KafkaTemplate<Long, String> template = mock(KafkaTemplate.class);
        when(template.send(firstEvent.topic(), 9001L, firstEvent.message()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("broker unavailable")),
                        CompletableFuture.completedFuture(null));
        when(template.send(otherEvent.topic(), 9002L, otherEvent.message()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(template.send(secondEvent.topic(), 9001L, secondEvent.message()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay(template, NOW.plusSeconds(1)).publishPending();
        assertThat(status(first)).isEqualTo("PENDING");
        assertThat(status(second)).isEqualTo("PENDING");
        assertThat(status(otherParty)).isEqualTo("PUBLISHED");

        relay(template, NOW.plusSeconds(31)).publishPending();
        assertThat(status(first)).isEqualTo("PUBLISHED");
        assertThat(status(second)).isEqualTo("PENDING");

        relay(template, NOW.plusSeconds(32)).publishPending();
        assertThat(status(second)).isEqualTo("PUBLISHED");
        var order = inOrder(template);
        order.verify(template).send(firstEvent.topic(), 9001L, firstEvent.message());
        order.verify(template).send(otherEvent.topic(), 9002L, otherEvent.message());
        order.verify(template).send(firstEvent.topic(), 9001L, firstEvent.message());
        order.verify(template).send(secondEvent.topic(), 9001L, secondEvent.message());
    }

    private KafkaOutboxRelay relay(KafkaTemplate<Long, String> template, Instant now) {
        return new KafkaOutboxRelay(
                store,
                template,
                new KafkaOutboxProperties(
                        "localhost:9092", 100, Duration.ofSeconds(30), Duration.ofDays(7)),
                new OutboxRetryPolicy(Duration.ofSeconds(30), () -> 0.5),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private OutboxEvent event(long id) {
        return jdbc.queryForObject(
                "SELECT event_id, topic, partition_key, message::text, created_at"
                        + " FROM outbox_events WHERE id=?",
                (result, rowNumber) ->
                        new OutboxEvent(
                                id,
                                result.getObject("event_id", UUID.class),
                                KafkaEventType.PARTY_REFRESH,
                                result.getString("topic"),
                                result.getLong("partition_key"),
                                result.getString("message"),
                                0,
                                result.getTimestamp("created_at").toInstant()),
                id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class, id);
    }

    private long partyEvent(long partyId, String inputId) {
        String message =
                "{\"partyId\":"
                        + partyId
                        + ",\"inputId\":\"sha256:"
                        + inputId
                        + "\",\"members\":[{\"userId\":101}]}";
        return store.savePartyRefresh(UUID.randomUUID(), partyId, message, NOW);
    }

    private static List<Long> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::id).toList();
    }
}
