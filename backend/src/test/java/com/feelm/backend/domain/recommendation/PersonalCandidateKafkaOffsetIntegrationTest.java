package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.party.service.CandidateUpdatedRequestService;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.service.RecommendationBufferService;
import com.feelm.backend.domain.recommendation.service.RecommendationReasonService;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.redis.RedisRecommendationCandidateReader;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.github.dockerjava.api.command.InspectContainerResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 Kafka·Redis·PostgreSQL에서 개인 버퍼 커밋과 두 Consumer group의 offset을 확인한다. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "feelm.party.refresh.consumer-enabled=true",
            "feelm.party.refresh.worker-enabled=false",
            "feelm.recommendation.candidate-consumer-enabled=true"
        })
class PersonalCandidateKafkaOffsetIntegrationTest extends SharedPostgresContractTest {
    private static final String TOPIC = "feelm.candidate-updated.v1";
    private static final String PERSONAL = "feelm-personal-buffer-v1";
    private static final String PARTY = "feelm-party-refresh-v1";
    private static final long USER = 58501L;
    private static final long NEXT_USER = 58502L;
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final MappedKafkaContainer KAFKA = startKafka();
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired TokenProvider tokens;
    @Autowired RecommendationBufferService bufferService;
    @MockitoSpyBean RecommendationBufferRepository bufferRepository;
    @MockitoSpyBean RedisRecommendationCandidateReader candidateReader;
    @MockitoSpyBean CandidateUpdatedRequestService partyRequestService;
    @MockitoSpyBean RecommendationReasonService reasonService;

    private CountDownLatch reasonRelease;
    private CountDownLatch readerRelease;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("feelm.kafka.outbox.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void fixtures() throws Exception {
        awaitOffset(PERSONAL, endOffset());
        awaitOffset(PARTY, endOffset());
        jdbc.execute("TRUNCATE TABLE users, movies RESTART IDENTITY CASCADE");
        for (long userId : new long[] {USER, NEXT_USER}) {
            jdbc.update(
                    "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                            + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                    userId,
                    "kafka-" + userId + "@example.test",
                    "kafka-" + userId);
        }
        for (long movieId : new long[] {90, 101, 102, 103, 201, 202}) {
            jdbc.update(
                    "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                    movieId,
                    movieId + 50000,
                    "영화 " + movieId);
        }
        jdbc.update(
                "INSERT INTO parties(id,name,owner_id,status) VALUES (9001,'party',?,'ACTIVE')",
                USER);
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (9001,?,'OWNER',now())",
                USER);
    }

    @AfterEach
    void cleanup() {
        if (reasonRelease != null) {
            reasonRelease.countDown();
            reasonRelease = null;
        }
        if (readerRelease != null) {
            readerRelease.countDown();
            readerRelease = null;
        }
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_second_item ON recommendation_items");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_second_item()");
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_commit ON recommendation_items");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_commit()");
        redis.delete(
                Set.of(
                        key(USER, "candidates"),
                        key(USER, "vector"),
                        key(NEXT_USER, "candidates"),
                        key(NEXT_USER, "vector")));
    }

    @Test
    void redisFailureKeepsPersonalLagWhilePartyRequestCommitsThenRetriesOnce() throws Exception {
        long next = endOffset() + 1;
        sendNotice(USER);

        awaitOffset(PARTY, next);
        assertThat(lag(PERSONAL)).isEqualTo(1);
        assertThat(count("recommendation_sets")).isZero();
        assertThat(count("party_refresh_requests")).isEqualTo(1);

        publish(USER, "g-redis", 101, 102, 201, false);
        awaitOffset(PERSONAL, next);
        assertThat(lag(PERSONAL)).isZero();
        assertThat(count("recommendation_sets")).isEqualTo(1);
        assertThat(count("recommendation_items")).isEqualTo(3);

        sendNotice(USER);
        awaitOffset(PERSONAL, next + 1);
        awaitOffset(PARTY, next + 1);
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    void itemInsertFailureRollsBackBeforeOffsetAndRetriesSameRecord() throws Exception {
        publish(USER, "g-db", 101, 102, 201, false);
        jdbc.execute(
                "CREATE FUNCTION test_fail_second_item() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'test item insert failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER test_fail_second_item BEFORE INSERT ON recommendation_items"
                        + " FOR EACH ROW WHEN (NEW.movie_id=102)"
                        + " EXECUTE FUNCTION test_fail_second_item()");
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            try {
                                return invocation.callRealMethod();
                            } finally {
                                attempted.countDown();
                            }
                        })
                .when(bufferRepository)
                .insertItems(anyLong(), anyList());
        long next = endOffset() + 1;
        sendNotice(USER);

        assertThat(attempted.await(15, TimeUnit.SECONDS)).isTrue();
        awaitOffset(PARTY, next);
        assertThat(lag(PERSONAL)).isEqualTo(1);
        assertThat(count("recommendation_sets")).isZero();
        assertThat(count("recommendation_items")).isZero();

        jdbc.execute("DROP TRIGGER test_fail_second_item ON recommendation_items");
        awaitOffset(PERSONAL, next);
        assertThat(count("recommendation_sets")).isEqualTo(1);
        assertThat(count("recommendation_items")).isEqualTo(3);
    }

    @Test
    void deferredCommitFailureLeavesNoPartialRowsAndRetriesSameRecord() throws Exception {
        publish(USER, "g-commit", 101, 102, 201, false);
        jdbc.execute(
                "CREATE FUNCTION test_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'test commit failure'; END $$");
        jdbc.execute(
                "CREATE CONSTRAINT TRIGGER test_fail_commit AFTER INSERT ON recommendation_items"
                        + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW"
                        + " EXECUTE FUNCTION test_fail_commit()");
        CountDownLatch inserted = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            try {
                                return invocation.callRealMethod();
                            } finally {
                                inserted.countDown();
                            }
                        })
                .when(bufferRepository)
                .insertItems(anyLong(), anyList());
        long next = endOffset() + 1;
        sendNotice(USER);

        assertThat(inserted.await(15, TimeUnit.SECONDS)).isTrue();
        awaitOffset(PARTY, next);
        assertThat(lag(PERSONAL)).isEqualTo(1);
        assertThat(count("recommendation_sets")).isZero();
        assertThat(count("recommendation_items")).isZero();

        jdbc.execute("DROP TRIGGER test_fail_commit ON recommendation_items");
        awaitOffset(PERSONAL, next);
        assertThat(count("recommendation_sets")).isEqualTo(1);
        assertThat(count("recommendation_items")).isEqualTo(3);
    }

    @Test
    void partyFailureDoesNotBlockPersonalCommit() throws Exception {
        publish(USER, "g-party-failure", 101, 102, 201, false);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch retry = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (calls.getAndIncrement() == 0) {
                                failed.countDown();
                                throw new IllegalStateException("test party failure");
                            }
                            assertThat(retry.await(15, TimeUnit.SECONDS)).isTrue();
                            return invocation.callRealMethod();
                        })
                .when(partyRequestService)
                .requestCurrentParties(USER);
        long next = endOffset() + 1;
        try {
            sendNotice(USER);
            assertThat(failed.await(15, TimeUnit.SECONDS)).isTrue();
            awaitOffset(PERSONAL, next);
            assertThat(lag(PARTY)).isEqualTo(1);
            assertThat(count("recommendation_sets")).isEqualTo(1);
        } finally {
            retry.countDown();
        }
        awaitOffset(PARTY, next);
        assertThat(count("party_refresh_requests")).isEqualTo(1);
    }

    @Test
    void delayedNoticeUsesLatestRedisGenerationAndHistoryKeepsExposedSet() throws Exception {
        publish(USER, "g-one", 101, 102, 201, false);
        long firstOffset = endOffset() + 1;
        sendNotice(USER);
        awaitOffset(PERSONAL, firstOffset);
        awaitOffset(PARTY, firstOffset);
        long firstSet = latestSet(USER);
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(firstSet));

        publish(USER, "g-two", 102, 103, 202, false);
        sendNotice(USER);
        awaitOffset(PERSONAL, firstOffset + 1);
        long secondSet = latestSet(USER);
        assertThat(secondSet).isGreaterThan(firstSet);
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationSetId").value(secondSet));
        mvc.perform(get("/api/v1/recommendations/history").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationSetId").value(secondSet))
                .andExpect(jsonPath("$.items[1].recommendationSetId").value(firstSet));

        sendNotice(USER);
        awaitOffset(PERSONAL, firstOffset + 2);
        awaitOffset(PARTY, firstOffset + 2);
        assertThat(count("recommendation_sets")).isEqualTo(2);
    }

    @Test
    void userLockMakesDelayedNoticeReadNewerRedisSnapshot() throws Exception {
        publish(USER, "g-old", 101, 102, 201, false);
        long next = endOffset() + 1;
        try (Connection blocker = dataSource.getConnection();
                PreparedStatement lock =
                        blocker.prepareStatement(
                                "SELECT id FROM users WHERE id=? FOR NO KEY UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setLong(1, USER);
            lock.executeQuery();
            sendNotice(USER);
            awaitOffset(PARTY, next);
            assertThat(lag(PERSONAL)).isEqualTo(1);
            publish(USER, "g-new", 102, 103, 202, false);
            blocker.commit();
        }
        awaitOffset(PERSONAL, next);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation_id FROM recommendation_sets WHERE user_id=?",
                                String.class,
                                USER))
                .isEqualTo("g-new");
        assertThat(count("recommendation_sets")).isEqualTo(1);
    }

    @Test
    void twoWritersSerializeAroundUserLockAndLaterGenerationWins() throws Exception {
        publish(USER, "g-first", 101, 102, 201, false);
        CountDownLatch firstRead = new CountDownLatch(1);
        readerRelease = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            Object batch = invocation.callRealMethod();
                            if (reads.getAndIncrement() == 0) {
                                firstRead.countDown();
                                assertThat(readerRelease.await(15, TimeUnit.SECONDS)).isTrue();
                            }
                            return batch;
                        })
                .when(candidateReader)
                .read(USER);
        long next = endOffset() + 1;
        CompletableFuture<Long> second = null;
        try {
            sendNotice(USER);
            assertThat(firstRead.await(15, TimeUnit.SECONDS)).isTrue();
            publish(USER, "g-second", 102, 103, 202, false);
            CountDownLatch secondStarted = new CountDownLatch(1);
            second =
                    CompletableFuture.supplyAsync(
                            () -> {
                                secondStarted.countDown();
                                return bufferService
                                        .createBufferFromNotice(USER)
                                        .recommendationSetId();
                            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second).isNotDone();
        } finally {
            readerRelease.countDown();
        }
        awaitOffset(PERSONAL, next);
        long secondSet = second.get(15, TimeUnit.SECONDS);
        assertThat(latestSet(USER)).isEqualTo(secondSet);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation_id FROM recommendation_sets WHERE id=?",
                                String.class,
                                secondSet))
                .isEqualTo("g-second");
        assertThat(count("recommendation_sets")).isEqualTo(2);
    }

    @Test
    void inactiveNoticeDoesNotBlockFollowingUserOnSamePartition() throws Exception {
        jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", USER);
        publish(NEXT_USER, "g-next", 101, 102, 201, false);
        long next = endOffset() + 2;
        sendNotice(USER);
        sendNotice(NEXT_USER);
        awaitOffset(PERSONAL, next);
        awaitOffset(PARTY, next);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_sets WHERE user_id=?",
                                Integer.class,
                                USER))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM recommendation_sets WHERE user_id=?",
                                Integer.class,
                                NEXT_USER))
                .isEqualTo(1);
    }

    @Test
    void lastRatingDeletionPublishesPopularCandidatesAndFollowingUserStillAdvances()
            throws Exception {
        JsonNode fixture = coldStartFixture();
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,1,5)", USER);
        publishGbtFixture(fixture, "before");
        long next = endOffset() + 1;
        sendNotice(USER);
        awaitOffset(PERSONAL, next);
        awaitOffset(PARTY, next);

        jdbc.update("DELETE FROM ratings WHERE user_id=?", USER);
        publishGbtFixture(fixture, "cold");
        publishGbtFixture(fixture, "next");
        sendNotice(USER);
        sendNotice(NEXT_USER);
        awaitOffset(PERSONAL, next + 2);
        awaitOffset(PARTY, next + 2);

        assertThat(count("recommendation_sets")).isEqualTo(3);
        assertThat(
                        jdbc.queryForList(
                                "SELECT movie_id FROM recommendation_items WHERE set_id=? ORDER BY position",
                                Long.class,
                                latestSet(USER)))
                .containsExactly(5L, 6L, 4L, 3L, 2L, 1L);
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(USER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(NEXT_USER)))
                .andExpect(status().isOk());

        sendNotice(USER);
        awaitOffset(PERSONAL, next + 3);
        awaitOffset(PARTY, next + 3);
        assertThat(count("recommendation_sets")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void popularNoticeUsesLatestBackendExclusionsAndAcknowledgesFullyFilteredBatch(
            boolean allExcluded) throws Exception {
        JsonNode fixture = coldStartFixture();
        publishGbtFixture(fixture, "cold");
        publishGbtFixture(fixture, "next");
        // Changes after the Consumer snapshot must still be filtered by Backend.
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,1,5)", USER);
        jdbc.update("INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,5)", USER);
        if (allExcluded) {
            for (long movieId : new long[] {2, 3, 4, 6}) {
                jdbc.update(
                        "INSERT INTO user_movie_dismissals(user_id,movie_id) VALUES (?,?)",
                        USER,
                        movieId);
            }
        }
        long next = endOffset() + 2;
        sendNotice(USER);
        sendNotice(NEXT_USER);
        awaitOffset(PERSONAL, next);
        awaitOffset(PARTY, next);
        assertThat(lag(PERSONAL)).isZero();
        if (allExcluded) {
            assertThat(count("recommendation_sets")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT user_id FROM recommendation_sets", Long.class))
                    .isEqualTo(NEXT_USER);
        } else {
            assertThat(
                            jdbc.queryForList(
                                    "SELECT movie_id FROM recommendation_items WHERE set_id=? ORDER BY position",
                                    Long.class,
                                    latestSet(USER)))
                    .containsExactly(6L, 4L, 3L, 2L);
        }
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(NEXT_USER)))
                .andExpect(status().isOk());
    }

    private JsonNode coldStartFixture() throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/gbt-personal-cold-state.json")) {
            fixture = new ObjectMapper().readTree(stream);
        }
        for (long movieId = 1; movieId <= 6; movieId++) {
            jdbc.update(
                    "INSERT INTO movies(id,tmdb_id,title) VALUES (?,?,?)",
                    movieId,
                    movieId + 50000,
                    "인기 영화 " + movieId);
        }
        redis.opsForValue().set("feelm:model:current", fixture.get("model").toString());
        return fixture;
    }

    private void publishGbtFixture(JsonNode fixture, String name) {
        JsonNode result = fixture.get(name);
        long userId = result.get("state").get("userId").longValue();
        redis.opsForValue()
                .multiSet(
                        Map.of(
                                key(userId, "recommendation-state"), result.get("state").toString(),
                                key(userId, "candidates"), result.get("candidates").toString()));
    }

    @Test
    void deletedUserNoticeDoesNotBlockFollowingUserOnSamePartition() throws Exception {
        jdbc.update("DELETE FROM party_members WHERE user_id=?", USER);
        jdbc.update("DELETE FROM parties WHERE owner_id=?", USER);
        jdbc.update("DELETE FROM users WHERE id=?", USER);
        publish(NEXT_USER, "g-after-delete", 101, 102, 201, false);
        long next = endOffset() + 2;
        sendNotice(USER);
        sendNotice(NEXT_USER);
        awaitOffset(PERSONAL, next);
        awaitOffset(PARTY, next);
        assertThat(count("recommendation_sets")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM recommendation_sets", Long.class))
                .isEqualTo(NEXT_USER);
    }

    @Test
    void reasonDelayKeepsGroundedFirstExposureAndDoesNotHoldOffset() throws Exception {
        publish(USER, "g-reason", 101, 102, 201, true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        reasonRelease = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            started.countDown();
                            try {
                                assertThat(reasonRelease.await(15, TimeUnit.SECONDS)).isTrue();
                                return invocation.callRealMethod();
                            } finally {
                                completed.countDown();
                            }
                        })
                .when(reasonService)
                .completeUnexposed(anyLong());
        long next = endOffset() + 1;
        sendNotice(USER);
        assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
        awaitOffset(PERSONAL, next);
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].reason.summary").value("좋아하신 영화 '영화 90'을 바탕으로 골랐어요."));
        mvc.perform(get("/api/v1/recommendations/history").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].items[0].reason.summary")
                                .value("좋아하신 영화 '영화 90'을 바탕으로 골랐어요."));
        long firstItem =
                jdbc.queryForObject(
                        "SELECT id FROM recommendation_items WHERE set_id=? AND movie_id=101",
                        Long.class,
                        latestSet(USER));
        mvc.perform(
                        get("/api/v1/movies/101")
                                .param("recommendationItemId", Long.toString(firstItem))
                                .header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.recommendation.reason.summary")
                                .value("좋아하신 영화 '영화 90'을 바탕으로 골랐어요."));
        reasonRelease.countDown();
        assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'summary' FROM recommendation_items"
                                        + " WHERE set_id=? AND movie_id=101",
                                String.class,
                                latestSet(USER)))
                .isEqualTo("좋아하신 영화 '영화 90'을 바탕으로 골랐어요.");
    }

    @Test
    void reasonCompletionBeforeExposureReplacesPendingSnapshot() throws Exception {
        publish(USER, "g-reason-complete", 101, 102, 201, true);
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            try {
                                return invocation.callRealMethod();
                            } finally {
                                completed.countDown();
                            }
                        })
                .when(reasonService)
                .completeUnexposed(anyLong());
        long next = endOffset() + 1;
        sendNotice(USER);
        awaitOffset(PERSONAL, next);
        assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason->>'_reasonPending' FROM recommendation_items"
                                        + " WHERE set_id=? AND movie_id=101",
                                String.class,
                                latestSet(USER)))
                .isNull();
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].reason.summary").value("좋아하신 영화 '영화 90'을 바탕으로 골랐어요."))
                .andExpect(jsonPath("$.items[0].reason._reasonPending").doesNotExist());
    }

    @Test
    void reasonDatabaseFailureAfterBufferCommitDoesNotHoldOffset() throws Exception {
        publish(USER, "g-reason-failure", 101, 102, 201, true);
        CountDownLatch failed = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            failed.countDown();
                            throw new DataAccessResourceFailureException(
                                    "test reason database failure");
                        })
                .when(reasonService)
                .completeUnexposed(anyLong());
        long next = endOffset() + 1;
        sendNotice(USER);
        assertThat(failed.await(15, TimeUnit.SECONDS)).isTrue();
        awaitOffset(PERSONAL, next);
        assertThat(count("recommendation_sets")).isEqualTo(1);
        assertThat(count("recommendation_items")).isEqualTo(3);
        mvc.perform(get("/api/v1/recommendations").header("Authorization", bearer(USER)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].reason.summary").value("좋아하신 영화 '영화 90'을 바탕으로 골랐어요."));
    }

    private void publish(
            long userId,
            String generation,
            long tasteOne,
            long tasteTwo,
            long discovery,
            boolean withReason) {
        String reason =
                withReason
                        ? "[{\"code\":\"TOP_RATED_SOURCE\",\"sourceMovieIds\":[90],\"matchedGenreCodes\":[]}]"
                        : "[]";
        redis.opsForValue()
                .set(
                        key(userId, "vector"),
                        "{\"modelVersion\":\"kafka-test\",\"candidateVersion\":6,"
                                + "\"generationId\":\""
                                + generation
                                + "\"}");
        redis.opsForValue()
                .set(
                        key(userId, "candidates"),
                        "{\"modelVersion\":\"kafka-test\",\"candidateVersion\":6,"
                                + "\"generationId\":\""
                                + generation
                                + "\","
                                + "\"updatedAt\":\"2026-09-18T00:00:00Z\","
                                + "\"taste\":[{\"movieId\":"
                                + tasteOne
                                + ",\"score\":0.9,\"reasons\":"
                                + reason
                                + "},"
                                + "{\"movieId\":"
                                + tasteTwo
                                + ",\"score\":0.8,\"reasons\":[]}],"
                                + "\"discovery\":[{\"movieId\":"
                                + discovery
                                + ",\"score\":0.7,\"reasons\":[]}]}");
    }

    private static String key(long userId, String suffix) {
        return "feelm:user:" + userId + ":" + suffix;
    }

    private static void sendNotice(long userId) throws Exception {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            producer.send(
                            new ProducerRecord<>(
                                    TOPIC, 0, Long.toString(userId), "{\"userId\":" + userId + "}"))
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private long latestSet(long userId) {
        return jdbc.queryForObject(
                "SELECT max(id) FROM recommendation_sets WHERE user_id=?", Long.class, userId);
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private static void awaitOffset(String group, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (committedOffset(group) == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(committedOffset(group)).as(group + " committed offset").isEqualTo(expected);
    }

    private static long committedOffset(String group) throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            OffsetAndMetadata offset =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS)
                            .get(PARTITION);
            return offset == null ? 0 : offset.offset();
        }
    }

    private static long endOffset() throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            return admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest()))
                    .all()
                    .get(5, TimeUnit.SECONDS)
                    .get(PARTITION)
                    .offset();
        }
    }

    private static long lag(String group) throws Exception {
        return endOffset() - committedOffset(group);
    }

    private static MappedKafkaContainer startKafka() {
        // Class initialization output may not be attached to JUnit's system-out/system-err.
        // Capture before start: Testcontainers removes a failed container before start() returns.
        StringBuilder startupLogs = new StringBuilder();
        MappedKafkaContainer container =
                new MappedKafkaContainer()
                        .withEnv("KAFKA_NODE_ID", "1")
                        .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                        .withEnv(
                                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                                "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,BROKER:PLAINTEXT")
                        .withEnv(
                                "KAFKA_LISTENERS",
                                "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,BROKER://0.0.0.0:9094")
                        .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER")
                        .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                        .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                        .withEnv("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk")
                        .withLogConsumer(
                                frame -> {
                                    if (frame.getType() == OutputFrame.OutputType.END) {
                                        return;
                                    }
                                    synchronized (startupLogs) {
                                        startupLogs.append(frame.getUtf8String());
                                        int excess = startupLogs.length() - 8192;
                                        if (excess > 0) {
                                            startupLogs.delete(0, excess);
                                        }
                                    }
                                })
                        .waitingFor(Wait.forListeningPort());
        try {
            container.start();
        } catch (RuntimeException exception) {
            String logTail;
            synchronized (startupLogs) {
                logTail =
                        startupLogs.isEmpty()
                                ? "(no container output captured)"
                                : startupLogs.toString();
            }
            throw new IllegalStateException(
                    "Kafka test container startup failed; container port=9092;"
                            + " last 8192 characters of stdout/stderr:\n"
                            + logTail,
                    exception);
        }
        try (AdminClient admin =
                AdminClient.create(Map.of("bootstrap.servers", container.getBootstrapServers()))) {
            admin.createTopics(java.util.List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all()
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka test topic creation failed", exception);
        }
        return container;
    }

    static class MappedKafkaContainer extends GenericContainer<MappedKafkaContainer> {
        private static final String START_SCRIPT = "/tmp/feelm-kafka-start.sh";

        MappedKafkaContainer() {
            super(DockerImageName.parse("apache/kafka:4.3.1"));
            withExposedPorts(9092);
            // Docker assigns the host port only after creation. Delay Kafka until it is known.
            withCommand(
                    "bash",
                    "-c",
                    "while [ ! -f "
                            + START_SCRIPT
                            + " ]; do sleep 0.1; done; exec bash "
                            + START_SCRIPT);
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse containerInfo) {
            String script =
                    "#!/bin/bash\n"
                            + "export KAFKA_ADVERTISED_LISTENERS='PLAINTEXT://"
                            + getBootstrapServers()
                            + ",BROKER://localhost:9094'\n"
                            + "exec /etc/kafka/docker/run\n";
            copyFileToContainer(Transferable.of(script, 0644), START_SCRIPT);
        }

        String getBootstrapServers() {
            return getHost() + ":" + getMappedPort(9092);
        }
    }
}
