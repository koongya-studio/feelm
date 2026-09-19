package com.feelm.backend.domain.party.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

class PartyCandidateUpdatedListenerTest {
    private final PartyCandidatePublicationService publications =
            mock(PartyCandidatePublicationService.class);
    private final PartyCandidateUpdatedListener listener =
            new PartyCandidateUpdatedListener(JsonMapper.builder().build(), publications);

    @Test
    void acceptsStrictPublicationIdentityAndAcknowledgesAfterRecord() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.onMessage(record(7L, body("als-v1", 31L)), acknowledgment);

        verify(publications)
                .record(
                        org.mockito.ArgumentMatchers.argThat(
                                publication ->
                                        publication.partyId() == 7L
                                                && publication.inputId().equals(inputId())
                                                && publication.modelVersion().equals("als-v1")
                                                && publication.sourcePartition() == 2
                                                && publication.sourceOffset() == 31L));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void discardsMalformedNotificationsWithoutWriting() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        listener.onMessage(record(8L, body("als-v1", 31L)), acknowledgment);
        listener.onMessage(record(new byte[7], body("als-v1", 31L)), acknowledgment);
        listener.onMessage(record(7L, body("x".repeat(51), 31L)), acknowledgment);
        listener.onMessage(record(7L, body("ALS-V1", 31L)), acknowledgment);
        listener.onMessage(
                record(7L, body("als-v1", 31L).replace("}", ",\"extra\":true}")), acknowledgment);
        listener.onMessage(record(7L, "{"), acknowledgment);

        verify(publications, never()).record(any());
        verify(acknowledgment, org.mockito.Mockito.times(6)).acknowledge();
    }

    @Test
    void doesNotAcknowledgeDatabaseFailure() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("database unavailable")).when(publications).record(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> listener.onMessage(record(7L, body("als-v1", 31L)), acknowledgment))
                .isInstanceOf(IllegalStateException.class);

        verify(acknowledgment, never()).acknowledge();
    }

    private static ConsumerRecord<byte[], String> record(Long key, String value) {
        byte[] serialized =
                key == null ? null : java.nio.ByteBuffer.allocate(Long.BYTES).putLong(key).array();
        return record(serialized, value);
    }

    private static ConsumerRecord<byte[], String> record(byte[] key, String value) {
        return new ConsumerRecord<>("feelm.party-candidate-updated.v1", 0, 1L, key, value);
    }

    private static String body(String modelVersion, long sourceOffset) {
        return """
                {"partyId":7,"inputId":"%s","modelVersion":"%s",\
                "generatedAt":"2026-09-18T12:00:00Z","sourcePartition":2,"sourceOffset":%d}
                """
                .formatted(inputId(), modelVersion, sourceOffset);
    }

    private static String inputId() {
        return "sha256:" + "a".repeat(64);
    }
}
