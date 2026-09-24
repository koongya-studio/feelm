package com.feelm.backend.domain.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.model.PartyInputId;
import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRefreshRequestRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.redis.RedisPartyInputReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PartyRefreshProcessorTest {
    private final PartyRepository parties = mock(PartyRepository.class);
    private final PartyMemberRepository members = mock(PartyMemberRepository.class);
    private final PartyRefreshRequestRepository requests =
            mock(PartyRefreshRequestRepository.class);
    private final RedisPartyInputReader redis = mock(RedisPartyInputReader.class);
    private final KafkaEventPublisher publisher = mock(KafkaEventPublisher.class);
    private final PartyRefreshProcessor processor =
            new PartyRefreshProcessor(parties, members, requests, redis, publisher);

    @Test
    void publishesCurrentInputThenDeletesRequest() throws Exception {
        activeParty(9001L);
        when(members.findUserIdsByPartyId(9001L)).thenReturn(List.of(1024L));
        when(redis.read(List.of(1024L))).thenReturn(snapshot("g1"));

        processor.process(9001L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishPartyRefresh(eq(9001L), payload.capture());
        JsonNode message = new ObjectMapper().valueToTree(payload.getValue());
        assertThat(message.path("partyId").longValue()).isEqualTo(9001L);
        assertThat(message.path("inputId").textValue()).startsWith("sha256:");
        assertThat(message.path("members").get(0).path("candidateMovieIds").get(0).longValue())
                .isEqualTo(550L);
        assertThat(message.has("schemaVersion")).isFalse();
        verify(requests).delete(9001L);
    }

    @Test
    void publishesGbtStateReferencesWithoutTopLevelModelFields() throws Exception {
        activeParty(9001L);
        when(members.findUserIdsByPartyId(9001L)).thenReturn(List.of(1024L));
        when(redis.read(List.of(1024L))).thenReturn(gbtSnapshot("g1", HASH));

        processor.process(9001L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishPartyRefresh(eq(9001L), payload.capture());
        JsonNode message = new ObjectMapper().valueToTree(payload.getValue());
        assertThat(message.propertyNames())
                .containsExactlyInAnyOrder("partyId", "inputId", "members");
        assertThat(message.path("members").get(0).path("generationId").textValue()).isEqualTo("g1");
        assertThat(message.path("members").get(0).path("inputSchemaVersion").textValue())
                .isEqualTo("gbt-input-v1");
        assertThat(message.path("members").get(0).path("inputHash").textValue()).isEqualTo(HASH);
        assertThat(message.path("members").get(0).has("vector")).isFalse();
    }

    @Test
    void keepsRequestWhenRedisFails() {
        activeParty(9001L);
        when(members.findUserIdsByPartyId(9001L)).thenReturn(List.of(1024L));
        when(redis.read(List.of(1024L))).thenThrow(new IllegalStateException("unavailable"));

        processor.process(9001L);

        verify(requests).retry(eq(9001L), eq(7L), any(Instant.class));
        verify(requests, never()).delete(9001L);
        verify(publisher, never()).publishPartyRefresh(eq(9001L), any());
    }

    @Test
    void skipsClosedParty() {
        Party party = mock(Party.class);
        when(party.getStatus()).thenReturn(PartyStatus.CLOSED);
        when(parties.findByIdForUpdate(9001L)).thenReturn(Optional.of(party));
        when(requests.findVersionForUpdate(9001L)).thenReturn(Optional.of(7L));

        processor.process(9001L);

        verify(requests).delete(9001L);
        verify(redis, never()).read(any());
    }

    @Test
    void inputIdDependsOnPartyModelMemberAndGeneration() {
        String initial = PartyInputId.from(9001L, snapshot("g1"));

        assertThat(PartyInputId.from(9001L, snapshot("g1"))).isEqualTo(initial);
        assertThat(PartyInputId.from(9001L, snapshot("g2"))).isNotEqualTo(initial);
        assertThat(PartyInputId.from(9002L, snapshot("g1"))).isNotEqualTo(initial);
        assertThat(PartyInputId.from(9001L, new PartyInputSnapshot("v2", snapshot("g1").members())))
                .isNotEqualTo(initial);
        assertThat(
                        PartyInputId.from(
                                9001L,
                                new PartyInputSnapshot(
                                        "v1",
                                        List.of(
                                                new PartyInputSnapshot.Member(
                                                        1025L, "g1", List.of(), List.of(550L))))))
                .isNotEqualTo(initial);
        assertThat(initial).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void gbtInputIdDependsOnModelRevisionAndInputReference() {
        String initial = PartyInputId.from(9001L, gbtSnapshot("g1", HASH));

        assertThat(PartyInputId.from(9001L, gbtSnapshot("g1", HASH))).isEqualTo(initial);
        assertThat(PartyInputId.from(9001L, gbtSnapshot("g1", OTHER_HASH))).isNotEqualTo(initial);
        PartyInputSnapshot changedModel =
                new PartyInputSnapshot(
                        PartyInputSnapshot.GBT_STATE_SCHEMA_VERSION,
                        "gbt-v27",
                        "serving-r1",
                        gbtSnapshot("g1", HASH).members());
        assertThat(PartyInputId.from(9001L, changedModel)).isNotEqualTo(initial);
        PartyInputSnapshot changedRevision =
                new PartyInputSnapshot(
                        PartyInputSnapshot.GBT_STATE_SCHEMA_VERSION,
                        "gbt-v26",
                        "serving-r2",
                        gbtSnapshot("g1", HASH).members());
        assertThat(PartyInputId.from(9001L, changedRevision)).isNotEqualTo(initial);
    }

    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final String OTHER_HASH = "sha256:" + "b".repeat(64);

    private void activeParty(long partyId) {
        Party party = mock(Party.class);
        when(party.getStatus()).thenReturn(PartyStatus.ACTIVE);
        when(parties.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(requests.findVersionForUpdate(partyId)).thenReturn(Optional.of(7L));
    }

    private static PartyInputSnapshot snapshot(String generation) {
        return new PartyInputSnapshot(
                "v1",
                List.of(
                        new PartyInputSnapshot.Member(
                                1024L, generation, List.of(), List.of(550L))));
    }

    private static PartyInputSnapshot gbtSnapshot(String generation, String inputHash) {
        return new PartyInputSnapshot(
                PartyInputSnapshot.GBT_STATE_SCHEMA_VERSION,
                "gbt-v26",
                "serving-r1",
                List.of(
                        PartyInputSnapshot.Member.gbt(
                                1024L, generation, "gbt-input-v1", inputHash, List.of(550L))));
    }
}
