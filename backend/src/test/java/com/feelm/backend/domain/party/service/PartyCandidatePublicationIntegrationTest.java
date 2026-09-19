package com.feelm.backend.domain.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "feelm.party.candidate-updated.consumer-enabled=false"
        })
class PartyCandidatePublicationIntegrationTest extends SharedPostgresContractTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PartyCandidateUpdatedListener listener;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (101,'publication@example.test','publication',"
                        + " DATE '2000-01-01','OTHER','ACTIVE')");
        jdbc.update(
                "INSERT INTO parties(id,name,owner_id,status)"
                        + " VALUES (9001,'party',101,'ACTIVE')");
    }

    @Test
    void recordsOnlyTheHighestSourceOffsetForOneParty() {
        acknowledge(body("a", "als-v1", 10));
        acknowledge(body("b", "als-v2", 9));
        acknowledge(body("c", "als-v3", 10));

        Map<String, Object> first = publication();
        assertThat(first.get("input_id")).isEqualTo(inputId("a"));
        assertThat(first.get("model_version")).isEqualTo("als-v1");
        assertThat(first.get("source_offset")).isEqualTo(10L);

        acknowledge(body("d", "als-v4", 11));

        Map<String, Object> latest = publication();
        assertThat(latest.get("input_id")).isEqualTo(inputId("d"));
        assertThat(latest.get("model_version")).isEqualTo("als-v4");
        assertThat(latest.get("source_offset")).isEqualTo(11L);
        assertThat(latest.get("generated_at")).isNotNull();
    }

    @Test
    void acknowledgesACompletionForAPartyDeletedBeforeDelivery() {
        jdbc.update("DELETE FROM parties WHERE id=9001");
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.onMessage(record(body("a", "als-v1", 10)), acknowledgment);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM party_candidate_publications", Long.class))
                .isZero();
        verify(acknowledgment).acknowledge();
    }

    private void acknowledge(String value) {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        listener.onMessage(record(value), acknowledgment);
        verify(acknowledgment).acknowledge();
    }

    private Map<String, Object> publication() {
        return jdbc.queryForMap(
                "SELECT input_id,model_version,source_offset,generated_at"
                        + " FROM party_candidate_publications WHERE party_id=9001");
    }

    private static ConsumerRecord<byte[], String> record(String value) {
        byte[] key = java.nio.ByteBuffer.allocate(Long.BYTES).putLong(9001L).array();
        return new ConsumerRecord<>("feelm.party-candidate-updated.v1", 0, 1L, key, value);
    }

    private static String body(String digestCharacter, String modelVersion, long sourceOffset) {
        return """
                {"partyId":9001,"inputId":"%s","modelVersion":"%s",\
                "generatedAt":"2026-09-18T12:00:00Z","sourcePartition":1,"sourceOffset":%d}
                """
                .formatted(inputId(digestCharacter), modelVersion, sourceOffset);
    }

    private static String inputId(String character) {
        return "sha256:" + character.repeat(64);
    }
}
