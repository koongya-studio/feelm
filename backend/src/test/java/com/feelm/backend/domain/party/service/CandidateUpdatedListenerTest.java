package com.feelm.backend.domain.party.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRefreshRequestRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.json.JsonMapper;

class CandidateUpdatedListenerTest {
    private final PartyMemberRepository members = mock(PartyMemberRepository.class);
    private final PartyRepository parties = mock(PartyRepository.class);
    private final PartyRefreshRequestRepository requests =
            mock(PartyRefreshRequestRepository.class);
    private final CandidateUpdatedRequestService requestService =
            new CandidateUpdatedRequestService(members, parties, requests);
    private final CandidateUpdatedListener listener =
            new CandidateUpdatedListener(JsonMapper.builder().build(), requestService);

    @Test
    void acceptsCurrentActiveParties() {
        Acknowledgment ack = mock(Acknowledgment.class);
        when(members.findActivePartyIdsByUserId(1024L)).thenReturn(List.of(10L, 20L));
        Party active = mock(Party.class);
        when(active.getStatus()).thenReturn(PartyStatus.ACTIVE);
        when(parties.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(active));
        when(parties.findByIdForUpdate(20L)).thenReturn(java.util.Optional.of(active));
        when(members.existsByPartyIdAndUserId(10L, 1024L)).thenReturn(true);
        when(members.existsByPartyIdAndUserId(20L, 1024L)).thenReturn(true);

        listener.onMessage(record("1024", "{\"userId\":1024}"), ack);

        verify(requests).request(10L);
        verify(requests).request(20L);
        verify(ack).acknowledge();
    }

    @Test
    void rejectsMalformedNotification() {
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.onMessage(record("1025", "{\"userId\":1024}"), ack);
        listener.onMessage(record("1024", "{\"userId\":1024,\"eventId\":\"x\"}"), ack);
        listener.onMessage(record("1024", "{"), ack);
        listener.onMessage(record("1024", null), ack);

        verify(members, never()).findActivePartyIdsByUserId(1024L);
        verify(ack, org.mockito.Mockito.times(4)).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("feelm.candidate-updated.v1", 0, 1L, key, value);
    }
}
