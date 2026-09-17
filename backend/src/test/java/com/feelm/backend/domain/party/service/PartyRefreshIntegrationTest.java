package com.feelm.backend.domain.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRefreshRequestRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.infrastructure.redis.RedisPartyInputReader;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class PartyRefreshIntegrationTest extends SharedPostgresContractTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PartyRefreshProcessor processor;
    @Autowired PartyRefreshRequestRepository requests;
    @Autowired PartyMemberRepository members;
    @Autowired PartyRepository parties;
    @Autowired CandidateUpdatedListener listener;
    @Autowired PartyService partyService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RedisPartyInputReader redis;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE outbox_events");
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long userId : new long[] {101L, 102L}) {
            jdbc.update(
                    "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                            + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                    userId,
                    "refresh" + userId + "@example.test",
                    "refresh" + userId);
        }
        jdbc.update(
                "INSERT INTO parties(id,name,owner_id,status) VALUES (9001,'party',101,'ACTIVE')");
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (9001,101,'OWNER',now())");
        requests.request(9001L);
    }

    @Test
    void retriesRedisFailureAfterLifecycleCommit() {
        when(redis.read(List.of(101L)))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenReturn(snapshot(101L));

        processor.process(9001L);
        assertThat(count("party_refresh_requests")).isEqualTo(1);
        assertThat(count("outbox_events")).isZero();

        processor.process(9001L);
        assertThat(count("party_refresh_requests")).isZero();
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void retriesRedisFailureAfterPartyCreateCommit() {
        long partyId = partyService.create(101L, "created party").partyId();
        when(redis.read(List.of(101L)))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenReturn(snapshot(101L));

        assertThat(requestCount(partyId)).isEqualTo(1);
        processor.process(partyId);
        assertThat(requestCount(partyId)).isEqualTo(1);
        assertThat(eventCount(partyId)).isZero();

        processor.process(partyId);
        assertThat(requestCount(partyId)).isZero();
        assertThat(eventCount(partyId)).isEqualTo(1);
    }

    @Test
    void membershipChangeBeforeWorkerUsesNewMemberSet() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            jdbc.queryForObject(
                                    "SELECT id FROM parties WHERE id=9001 FOR UPDATE", Long.class);
                            jdbc.update(
                                    "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                                            + " VALUES (9001,102,'MEMBER',now())");
                            requests.request(9001L);
                        });
        when(redis.read(List.of(101L, 102L))).thenReturn(snapshot(101L, 102L));

        processor.process(9001L);

        assertThat(requestCount(9001L)).isZero();
        assertThat(eventCount(9001L)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT jsonb_array_length(message -> 'members')"
                                        + " FROM outbox_events WHERE event_type='PARTY_REFRESH'"
                                        + " AND partition_key=9001",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void duplicateNoticeUsesCurrentInputAndCanReemitSameInputId() {
        when(redis.read(List.of(101L))).thenReturn(snapshot(101L));
        requests.request(9001L);
        assertThat(count("party_refresh_requests")).isEqualTo(1);

        processor.process(9001L);
        String first =
                jdbc.queryForObject(
                        "SELECT message ->> 'inputId' FROM outbox_events"
                                + " WHERE event_type='PARTY_REFRESH' LIMIT 1",
                        String.class);
        requests.request(9001L);
        processor.process(9001L);

        assertThat(count("party_refresh_requests")).isZero();
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT message ->> 'inputId' FROM outbox_events"
                                        + " WHERE event_type='PARTY_REFRESH'",
                                String.class))
                .containsExactlyInAnyOrder(first, first);
    }

    @Test
    void acknowledgesOnlyAfterAllPartyRequestsCommit() {
        jdbc.execute("DELETE FROM party_refresh_requests");
        jdbc.update(
                "INSERT INTO parties(id,name,owner_id,status) VALUES (9002,'second',101,'ACTIVE')");
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (9002,101,'OWNER',now())");
        Acknowledgment acknowledgement = mock(Acknowledgment.class);
        ConsumerRecord<String, String> notice =
                new ConsumerRecord<>(
                        "feelm.candidate-updated.v1", 0, 1L, "101", "{\"userId\":101}");

        jdbc.execute(
                "ALTER TABLE party_refresh_requests ADD CONSTRAINT test_reject_second_party"
                        + " CHECK (party_id <> 9002)");
        try {
            assertThatThrownBy(() -> listener.onMessage(notice, acknowledgement))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("party_refresh_requests")).isZero();
            org.mockito.Mockito.verify(acknowledgement, org.mockito.Mockito.never()).acknowledge();
        } finally {
            jdbc.execute(
                    "ALTER TABLE party_refresh_requests DROP CONSTRAINT test_reject_second_party");
        }

        doAnswer(
                        invocation -> {
                            assertThat(count("party_refresh_requests")).isEqualTo(2);
                            return null;
                        })
                .when(acknowledgement)
                .acknowledge();
        listener.onMessage(notice, acknowledgement);
        org.mockito.Mockito.verify(acknowledgement).acknowledge();
    }

    @Test
    void closingAfterNotificationLookupDoesNotRecreateRequest() throws Exception {
        jdbc.execute("DELETE FROM party_refresh_requests");
        PartyMemberRepository pausedMembers = mock(PartyMemberRepository.class);
        CountDownLatch lookedUp = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(pausedMembers.findActivePartyIdsByUserId(101L))
                .thenAnswer(
                        invocation -> {
                            List<Long> result = members.findActivePartyIdsByUserId(101L);
                            lookedUp.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("notification lookup timed out");
                            }
                            return result;
                        });
        CandidateUpdatedRequestService pausedService =
                new CandidateUpdatedRequestService(pausedMembers, parties, requests);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var notification =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    status ->
                                                            pausedService.requestCurrentParties(
                                                                    101L)));
            assertThat(lookedUp.await(5, TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                jdbc.queryForObject(
                                        "SELECT id FROM parties WHERE id=9001 FOR UPDATE",
                                        Long.class);
                                jdbc.update(
                                        "UPDATE parties SET status='CLOSED',"
                                                + " closed_reason='OWNER_CLOSED', closed_at=now()"
                                                + " WHERE id=9001");
                                requests.delete(9001L);
                            });
            release.countDown();
            notification.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        assertThat(count("party_refresh_requests")).isZero();
    }

    @Test
    void serializesMembershipChangeWithRefreshProcessing() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redis.read(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<Long> ids = invocation.getArgument(0);
                            if (ids.size() == 1) {
                                reading.countDown();
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test read timed out");
                                }
                            }
                            return ids.size() == 1 ? snapshot(101L) : snapshot(101L, 102L);
                        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> processor.process(9001L));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            var membershipChange =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM parties WHERE id=9001 FOR UPDATE",
                                                                Long.class);
                                                        jdbc.update(
                                                                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                                                                        + " VALUES (9001,102,'MEMBER',now())");
                                                        requests.request(9001L);
                                                    }));
            assertThat(membershipChange.isDone()).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            membershipChange.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        assertThat(count("party_refresh_requests")).isEqualTo(1);
        processor.process(9001L);
        assertThat(count("party_refresh_requests")).isZero();
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT message -> 'members' -> 1 ->> 'userId' FROM outbox_events"
                                        + " WHERE event_type='PARTY_REFRESH'"
                                        + " ORDER BY created_at DESC LIMIT 1",
                                String.class))
                .isEqualTo("102");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long requestCount(long partyId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM party_refresh_requests WHERE party_id=?",
                Long.class,
                partyId);
    }

    private long eventCount(long partyId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type='PARTY_REFRESH'"
                        + " AND partition_key=?",
                Long.class,
                partyId);
    }

    private static PartyInputSnapshot snapshot(long... ids) {
        return new PartyInputSnapshot(
                "v1",
                java.util.Arrays.stream(ids)
                        .mapToObj(
                                id ->
                                        new PartyInputSnapshot.Member(
                                                id, "g1", List.of(), List.of(550L)))
                        .toList());
    }
}
