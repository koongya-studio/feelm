package com.feelm.backend.domain.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("평가 수정·삭제 API 계약 — API-037·038")
class RatingMutationApiContractTest extends SharedPostgresContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource dataSource;

    private long userId;
    private long otherUserId;
    private long movieId;
    private long secondMovieId;
    private long flavorId;

    @BeforeEach
    void setUp() {
        truncate();
        flavorId = insertFlavor();
        userId = insertUser("mutation-owner@feelm.test", "평가 수정자", "ACTIVE");
        otherUserId = insertUser("mutation-other@feelm.test", "다른 평가자", "ACTIVE");
        movieId = insertMovie(213_001, flavorId);
        secondMovieId = insertMovie(213_002, flavorId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_mutation_outbox ON outbox_events").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_mutation_outbox()").update();
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_mutation_movie ON movies").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_mutation_movie()").update();
        truncate();
    }

    @Test
    @DisplayName("수정은 평가·프레임·감상 ID와 생성 시각을 유지한다")
    void updateRatingPreservesFrameAndWatch() throws Exception {
        JsonNode created = create(movieId, userId, publicBody("4.5", "기존"));
        long ratingId = created.path("ratingId").asLong();
        long frameId = created.path("frame").path("frameId").asLong();
        jdbc.sql("UPDATE ratings SET updated_at=TIMESTAMPTZ '2026-09-01 00:00:00+00' WHERE id=:id")
                .param("id", ratingId)
                .update();
        RatingRow before = rating(ratingId);
        Instant watchedAt = Instant.parse("2026-09-01T12:30:00Z");
        jdbc.sql("UPDATE watch_records SET watched_at=:watchedAt WHERE id=:id")
                .param("watchedAt", watchedAt.atOffset(ZoneOffset.UTC))
                .param("id", before.watchRecordId())
                .update();

        JsonNode updated =
                update(
                        ratingId,
                        userId,
                        "{\"score\":3.5,\"comment\":\"수정\",\"visibility\":\"PRIVATE\",\"spoiler\":true}");

        RatingRow after = rating(ratingId);
        assertThat(updated.path("ratingId").asLong()).isEqualTo(ratingId);
        assertThat(updated.path("frame").path("frameId").asLong()).isEqualTo(frameId);
        assertThat(updated.path("frame").path("createdAt").asText())
                .isEqualTo(created.path("frame").path("createdAt").asText());
        assertThat(updated.path("createdAt").asText())
                .isEqualTo(created.path("createdAt").asText());
        assertThat(updated.path("watchStatus").asText()).isEqualTo("RATED");
        assertThat(updated.path("spoiler").asBoolean()).isTrue();
        assertThat(after.watchRecordId()).isEqualTo(before.watchRecordId());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.updatedAt()).isAfter(before.updatedAt());
        assertThat(after.comment()).isEqualTo("수정");
        assertThat(after.visibility()).isEqualTo("PRIVATE");
        assertThat(after.spoiler()).isTrue();
        assertThat(watch(before.watchRecordId()).watchedAt()).isEqualTo(watchedAt);
        assertThat(watch(before.watchRecordId()).status()).isEqualTo("RATED");
        assertThat(frameId(ratingId)).isEqualTo(frameId);
        mockMvc.perform(
                        get("/api/v1/frames/{frameId}", frameId)
                                .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating.score").value(3.5));
    }

    @Test
    @DisplayName("별점 수정은 영화 평균을 실제 평가에서 재계산한다")
    void updateScoreRecalculatesAverage() throws Exception {
        long ratingId = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();
        create(movieId, otherUserId, privateBody("3.5"));

        update(ratingId, userId, privateBody("1.5"));

        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(2, "2.50"));
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("영화 상세 집계는 여러 사용자 평가의 생성·수정·삭제를 실제 ratings와 동일하게 반영한다")
    void movieDetailAggregateTracksCreateUpdateAndDelete() throws Exception {
        long thirdUserId = insertUser("aggregate-third@feelm.test", "세 번째 평가자", "ACTIVE");
        long fourthUserId = insertUser("aggregate-fourth@feelm.test", "네 번째 평가자", "ACTIVE");
        long firstRatingId = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();
        long secondRatingId =
                create(movieId, otherUserId, privateBody("4.5")).path("ratingId").asLong();
        long thirdRatingId =
                create(movieId, thirdUserId, privateBody("4.5")).path("ratingId").asLong();
        long fourthRatingId =
                create(movieId, fourthUserId, privateBody("4.0")).path("ratingId").asLong();

        assertDetailAggregate(movieId, "4.38", 4);
        assertAggregateMatchesRatings(movieId);

        update(firstRatingId, userId, privateBody("2.5"));

        assertDetailAggregate(movieId, "3.88", 4);
        assertAggregateMatchesRatings(movieId);

        remove(fourthRatingId, fourthUserId).andExpect(status().isNoContent());

        assertDetailAggregate(movieId, "3.83", 3);
        assertAggregateMatchesRatings(movieId);

        remove(firstRatingId, userId).andExpect(status().isNoContent());
        remove(secondRatingId, otherUserId).andExpect(status().isNoContent());
        remove(thirdRatingId, thirdUserId).andExpect(status().isNoContent());

        assertDetailAggregate(movieId, "0", 0);
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("한줄평 null 수정은 공개 목록에서 제거한다")
    void updateCommentToNullHidesReview() throws Exception {
        long ratingId =
                create(movieId, userId, publicBody("4.5", "공개 글")).path("ratingId").asLong();
        assertThat(reviews(otherUserId).path("items").size()).isEqualTo(1);

        JsonNode response = update(ratingId, userId, "{\"score\":4.5,\"comment\":null}");

        assertThat(response.path("comment").isNull()).isTrue();
        assertThat(response.path("visibility").isNull()).isTrue();
        assertThat(response.path("spoiler").asBoolean()).isFalse();
        assertThat(rating(ratingId).comment()).isNull();
        assertThat(reviews(otherUserId).path("items").size()).isZero();
    }

    @Test
    @DisplayName("PUBLIC에서 PRIVATE로 수정하면 타인 공개 목록에서 제거한다")
    void updatePublicToPrivate() throws Exception {
        long ratingId =
                create(movieId, userId, publicBody("4.5", "공개 글")).path("ratingId").asLong();

        update(
                ratingId,
                userId,
                "{\"score\":4.5,\"comment\":\"비공개 글\",\"visibility\":\"PRIVATE\"}");

        assertThat(reviews(otherUserId).path("items").size()).isZero();
        assertThat(reviews(userId).path("items").path(0).path("comment").asText())
                .isEqualTo("비공개 글");
    }

    @Test
    @DisplayName("수정은 점수가 있는 PENDING RATING UPDATE를 기록한다")
    void updateEmitsPendingRatingEvent() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();

        update(ratingId, userId, privateBody("4.5"));

        assertThat(eventCount("UPDATE")).isEqualTo(1);
        JsonNode event = event("UPDATE");
        assertThat(event.path("eventType").asText()).isEqualTo("RATING");
        assertThat(event.path("userId").asLong()).isEqualTo(userId);
        assertThat(event.path("payload").path("movieId").asLong()).isEqualTo(movieId);
        assertThat(event.path("payload").path("score").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(eventStatus("UPDATE")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("동일 PUT 재시도는 수정 시각과 UPDATE 이벤트를 다시 만들지 않는다")
    void repeatedIdenticalUpdateIsIdempotent() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        JsonNode first = update(ratingId, userId, privateBody("4.5"));
        RatingRow afterFirst = rating(ratingId);

        JsonNode second = update(ratingId, userId, privateBody("4.5"));

        assertThat(second).isEqualTo(first);
        assertThat(rating(ratingId)).isEqualTo(afterFirst);
        assertThat(eventCount("UPDATE")).isEqualTo(1);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "4.50"));
    }

    @Test
    @DisplayName("삭제는 프레임을 지우고 감상 기록과 감상 시각을 보존한다")
    void deleteRatingRemovesFrameAndPreservesWatch() throws Exception {
        JsonNode created = create(movieId, userId, privateBody("4.0"));
        long ratingId = created.path("ratingId").asLong();
        long frameId = created.path("frame").path("frameId").asLong();
        long watchId = rating(ratingId).watchRecordId();
        Instant watchedAt = Instant.parse("2026-09-01T12:30:00Z");
        jdbc.sql("UPDATE watch_records SET watched_at=:watchedAt WHERE id=:id")
                .param("watchedAt", watchedAt.atOffset(ZoneOffset.UTC))
                .param("id", watchId)
                .update();

        MvcResult deleted = remove(ratingId, userId).andExpect(status().isNoContent()).andReturn();
        assertThat(deleted.getResponse().getContentAsByteArray()).isEmpty();

        assertThat(count("ratings")).isZero();
        assertThat(count("frames")).isZero();
        assertThat(count("watch_records")).isEqualTo(1);
        assertThat(watch(watchId)).isEqualTo(new WatchRow("WATCHED_UNRATED", watchedAt, null));
        mockMvc.perform(
                        get("/api/v1/frames/{frameId}", frameId)
                                .header("Authorization", bearer(userId)))
                .andExpect(status().isNotFound());
        JsonNode records = watchRecords(userId);
        assertThat(records.path("items").path(0).path("status").asText())
                .isEqualTo("WATCHED_UNRATED");
        assertThat(records.path("items").path(0).path("ratingId").isNull()).isTrue();
    }

    @Test
    @DisplayName("평가 둘 중 하나 삭제 후 남은 점수로 평균을 재계산한다")
    void deleteRecalculatesAverageAndCount() throws Exception {
        long first = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();
        create(movieId, otherUserId, privateBody("2.5"));

        remove(first, userId).andExpect(status().isNoContent());

        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "2.50"));
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("마지막 평가 삭제 후 집계는 0과 0.00이다")
    void deleteLastRatingClearsAggregate() throws Exception {
        long ratingId = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();

        remove(ratingId, userId).andExpect(status().isNoContent());

        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("삭제는 점수가 없는 PENDING RATING DELETE를 기록한다")
    void deleteEmitsPendingEventWithoutScore() throws Exception {
        long ratingId = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();

        remove(ratingId, userId).andExpect(status().isNoContent());

        assertThat(eventCount("DELETE")).isEqualTo(1);
        JsonNode event = event("DELETE");
        assertThat(event.path("payload").path("movieId").asLong()).isEqualTo(movieId);
        assertThat(event.path("payload").has("score")).isFalse();
        assertThat(eventStatus("DELETE")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("중복 DELETE는 404지만 추가 도메인 쓰기와 이벤트가 없다")
    void repeatedDeleteKeepsDeletedState() throws Exception {
        long ratingId = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();
        remove(ratingId, userId).andExpect(status().isNoContent());

        remove(ratingId, userId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(count("ratings")).isZero();
        assertThat(count("frames")).isZero();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
        assertThat(eventCount("DELETE")).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제 후 API-036 재평가는 새 평가와 프레임을 만든다")
    void reRateAfterDeleteCreatesNewFrame() throws Exception {
        JsonNode created = create(movieId, userId, privateBody("4.5"));
        long oldRating = created.path("ratingId").asLong();
        long oldFrame = created.path("frame").path("frameId").asLong();
        long watchId = rating(oldRating).watchRecordId();
        remove(oldRating, userId).andExpect(status().isNoContent());

        JsonNode recreated = create(movieId, userId, privateBody("2.5"));

        assertThat(recreated.path("ratingId").asLong()).isNotEqualTo(oldRating);
        assertThat(recreated.path("frame").path("frameId").asLong()).isNotEqualTo(oldFrame);
        assertThat(rating(recreated.path("ratingId").asLong()).watchRecordId()).isEqualTo(watchId);
        assertThat(watch(watchId).status()).isEqualTo("RATED");
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "2.50"));
    }

    @Test
    @DisplayName("타인 평가 수정·삭제는 같은 404로 숨긴다")
    void rejectsOtherUsersRating() throws Exception {
        long ratingId = create(movieId, otherUserId, privateBody("4.5")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        long frames = count("frames");
        long events = count("outbox_events");

        change(ratingId, userId, privateBody("1.5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        remove(ratingId, userId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(count("frames")).isEqualTo(frames);
        assertThat(count("outbox_events")).isEqualTo(events);
    }

    @Test
    @DisplayName("없는 평가 수정·삭제는 404와 쓰기 0건이다")
    void rejectsUnknownRating() throws Exception {
        change(999_999, userId, privateBody("4.5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        remove(999_999, userId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(count("ratings")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("무토큰·비활성 사용자는 수정·삭제할 수 없다")
    void rejectsUnauthenticatedAndInactive() throws Exception {
        long ratingId = create(movieId, userId, privateBody("4.5")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        long events = count("outbox_events");
        mockMvc.perform(
                        put("/api/v1/ratings/{ratingId}", ratingId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(privateBody("1.5")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/ratings/{ratingId}", ratingId))
                .andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE users SET status='WITHDRAWN' WHERE id=:id").param("id", userId).update();
        change(ratingId, userId, privateBody("1.5")).andExpect(status().isUnauthorized());
        remove(ratingId, userId).andExpect(status().isUnauthorized());
        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(count("outbox_events")).isEqualTo(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9223372036854775808"})
    @DisplayName("0·음수·overflow 평가 ID는 400이다")
    void rejectsInvalidPathId(String id) throws Exception {
        mockMvc.perform(
                        put("/api/v1/ratings/" + id)
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(privateBody("4.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(delete("/api/v1/ratings/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("outbox_events")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.4", "5.5", "4.49", "4.50000000000000001", "\"4.5\"", "[]"})
    @DisplayName("범위·단위·타입 위반 점수는 원값 그대로 거절한다")
    void rejectsInvalidScores(String score) throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);

        assertValidation(change(ratingId, userId, "{\"score\":" + score + "}"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "3.00"));
        assertThat(eventCount("UPDATE")).isZero();
    }

    @Test
    @DisplayName("필수 점수 누락은 400이며 평가가 유지된다")
    void rejectsMissingScore() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        assertValidation(change(ratingId, userId, "{\"comment\":null}"));
        assertThat(rating(ratingId).score()).isEqualByComparingTo("3.0");
        assertThat(eventCount("UPDATE")).isZero();
    }

    @Test
    @DisplayName("한줄평 길이·공개 범위 오류는 저장 전에 거절한다")
    void rejectsInvalidCommentAndVisibility() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        assertValidation(
                change(
                        ratingId,
                        userId,
                        "{\"score\":4.5,\"comment\":\""
                                + "x".repeat(201)
                                + "\",\"visibility\":\"PUBLIC\"}"));
        assertValidation(change(ratingId, userId, "{\"score\":4.5,\"comment\":\"문구\"}"));
        assertValidation(
                change(
                        ratingId,
                        userId,
                        "{\"score\":4.5,\"comment\":\"문구\",\"visibility\":\"EVERYONE\"}"));
        assertValidation(
                change(
                        ratingId,
                        userId,
                        "{\"score\":4.5,\"comment\":123,\"visibility\":\"PUBLIC\"}"));
        assertValidation(
                change(
                        ratingId,
                        userId,
                        "{\"score\":4.5,\"comment\":[],\"visibility\":\"PUBLIC\"}"));
        assertValidation(
                change(
                        ratingId,
                        userId,
                        "{\"score\":4.5,\"comment\":true,\"visibility\":\"PUBLIC\"}"));
        assertValidation(
                change(ratingId, userId, "{\"score\":4.5,\"comment\":\"문구\",\"visibility\":0}"));
        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(eventCount("UPDATE")).isZero();
    }

    @Test
    @DisplayName("깨진 JSON은 400이고 비지원 Content-Type은 415이다")
    void rejectsMalformedJsonAndMediaType() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        assertValidation(change(ratingId, userId, "{\"score\":"));
        mockMvc.perform(
                        put("/api/v1/ratings/{ratingId}", ratingId)
                                .header("Authorization", bearer(userId))
                                .contentType(MediaType.TEXT_PLAIN)
                                .content(privateBody("4.5")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(
                        put("/api/v1/ratings/{ratingId}", ratingId)
                                .header("Authorization", bearer(userId))
                                .content(privateBody("4.5")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(eventCount("UPDATE")).isZero();
    }

    @Test
    @DisplayName("수정 중 아웃박스 실패는 평가·프레임·감상·집계를 롤백한다")
    void outboxFailureRollsBackUpdate() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        WatchRow watchBefore = watch(before.watchRecordId());
        long frameBefore = frameId(ratingId);
        long eventsBefore = count("outbox_events");
        failingTrigger("outbox_events", "INSERT", "test_fail_mutation_outbox");

        change(ratingId, userId, privateBody("4.5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(watch(before.watchRecordId())).isEqualTo(watchBefore);
        assertThat(frameId(ratingId)).isEqualTo(frameBefore);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "3.00"));
        assertThat(count("outbox_events")).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("삭제 중 아웃박스 실패는 평가·프레임·감상·집계를 롤백한다")
    void outboxFailureRollsBackDelete() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        WatchRow watchBefore = watch(before.watchRecordId());
        long frameBefore = frameId(ratingId);
        long eventsBefore = count("outbox_events");
        failingTrigger("outbox_events", "INSERT", "test_fail_mutation_outbox");

        remove(ratingId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(watch(before.watchRecordId())).isEqualTo(watchBefore);
        assertThat(frameId(ratingId)).isEqualTo(frameBefore);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "3.00"));
        assertThat(count("outbox_events")).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("집계 갱신 실패는 수정과 삭제의 모든 쓰기를 롤백한다")
    void aggregateFailureRollsBackMutations() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        RatingRow before = rating(ratingId);
        WatchRow watchBefore = watch(before.watchRecordId());
        long frameBefore = frameId(ratingId);
        long eventsBefore = count("outbox_events");
        failingTrigger("movies", "UPDATE", "test_fail_mutation_movie");

        change(ratingId, userId, privateBody("4.5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        remove(ratingId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(watch(before.watchRecordId())).isEqualTo(watchBefore);
        assertThat(frameId(ratingId)).isEqualTo(frameBefore);
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(1, "3.00"));
        assertThat(count("outbox_events")).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("같은 평가의 동시 수정은 직렬화되고 마지막 점수와 집계가 일치한다")
    void concurrentUpdatesSerialize() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
                Connection blocker = lockRow("users", userId)) {
            Future<Integer> first =
                    executor.submit(
                            () -> concurrentUpdate(start, ratingId, userId, privateBody("1.5")));
            Future<Integer> second =
                    executor.submit(
                            () -> concurrentUpdate(start, ratingId, userId, privateBody("4.5")));
            start.countDown();
            waitForBlocked(blocker, 2);
            blocker.commit();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 200);
        }
        assertThat(eventCount("UPDATE")).isEqualTo(2);
        assertThat(count("frames")).isEqualTo(1);
        assertAggregateMatchesRatings(movieId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("같은 평가의 수정→삭제와 삭제→수정 경쟁을 실제 행 잠금 순서대로 검증한다")
    void concurrentDeleteAndUpdateAreConsistent(boolean updateFirst) throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        long watchId = rating(ratingId).watchRecordId();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
                Connection blocker = lockRow("users", userId)) {
            Future<Integer> first =
                    executor.submit(
                            () ->
                                    updateFirst
                                            ? concurrentUpdate(
                                                    start, ratingId, userId, privateBody("4.5"))
                                            : concurrentDelete(start, ratingId, userId));
            start.countDown();
            waitForBlocked(blocker, 1);
            Future<Integer> second =
                    executor.submit(
                            () ->
                                    updateFirst
                                            ? concurrentDelete(start, ratingId, userId)
                                            : concurrentUpdate(
                                                    start, ratingId, userId, privateBody("4.5")));
            waitForBlocked(blocker, 2);
            blocker.commit();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(updateFirst ? 200 : 204);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(updateFirst ? 204 : 404);
            assertThat(eventCount("UPDATE")).isEqualTo(updateFirst ? 1 : 0);
        }
        assertThat(eventCount("DELETE")).isEqualTo(1);
        assertThat(count("ratings")).isZero();
        assertThat(count("frames")).isZero();
        assertThat(watch(watchId).status()).isEqualTo("WATCHED_UNRATED");
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("다른 사용자가 같은 영화를 동시에 수정해도 집계가 유실되지 않는다")
    void concurrentDifferentUsersSameMovie() throws Exception {
        long firstRating = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        long secondRating =
                create(movieId, otherUserId, privateBody("4.0")).path("ratingId").asLong();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
                Connection blocker = lockRow("movies", movieId)) {
            Future<Integer> first =
                    executor.submit(
                            () -> concurrentUpdate(start, firstRating, userId, privateBody("1.5")));
            Future<Integer> second =
                    executor.submit(
                            () ->
                                    concurrentUpdate(
                                            start, secondRating, otherUserId, privateBody("5.0")));
            start.countDown();
            waitForBlocked(blocker, 2);
            blocker.commit();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 200);
        }
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(2, "3.25"));
        assertThat(eventCount("UPDATE")).isEqualTo(2);
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("감상 연결이 없는 기존 평가도 삭제하며 새 감상을 만들지 않는다")
    void deleteLegacyRatingWithoutWatch() throws Exception {
        long ratingId =
                jdbc.sql(
                                """
                                INSERT INTO ratings(user_id,movie_id,score,visibility,is_spoiler)
                                VALUES (:userId,:movieId,3.5,'PRIVATE',false) RETURNING id
                                """)
                        .param("userId", userId)
                        .param("movieId", movieId)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "INSERT INTO frames(rating_id,user_id,movie_id) VALUES (:ratingId,:userId,:movieId)")
                .param("ratingId", ratingId)
                .param("userId", userId)
                .param("movieId", movieId)
                .update();
        jdbc.sql("UPDATE movies SET rating_count=1, average_rating=3.50 WHERE id=:movieId")
                .param("movieId", movieId)
                .update();

        remove(ratingId, userId).andExpect(status().isNoContent());

        assertThat(count("ratings")).isZero();
        assertThat(count("frames")).isZero();
        assertThat(count("watch_records")).isZero();
        assertThat(movieAggregate(movieId)).isEqualTo(new MovieAggregate(0, "0.00"));
        assertThat(eventCount("DELETE")).isEqualTo(1);
    }

    @Test
    @DisplayName("훼손된 감상 연결은 타인 기록을 건드리지 않고 실패한다")
    void brokenWatchLinkRollsBack() throws Exception {
        long ownerRating = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        long otherRating =
                create(movieId, otherUserId, privateBody("4.0")).path("ratingId").asLong();
        long otherWatchId = rating(otherRating).watchRecordId();
        WatchRow otherWatch = watch(otherWatchId);
        jdbc.sql("UPDATE ratings SET watch_record_id=:watchId WHERE id=:ratingId")
                .param("watchId", otherWatchId)
                .param("ratingId", ownerRating)
                .update();
        RatingRow before = rating(ownerRating);
        long events = count("outbox_events");

        remove(ownerRating, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        change(ownerRating, userId, privateBody("1.5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(rating(ownerRating)).isEqualTo(before);
        assertThat(watch(otherWatchId)).isEqualTo(otherWatch);
        assertThat(count("frames")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(events);
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("다른 영화 감상에 잘못 연결된 평가는 수정·삭제하지 않는다")
    void brokenWatchMovieLinkRollsBack() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        long secondRating =
                create(secondMovieId, userId, privateBody("4.0")).path("ratingId").asLong();
        long secondWatchId = rating(secondRating).watchRecordId();
        jdbc.sql("UPDATE ratings SET watch_record_id=:watchId WHERE id=:ratingId")
                .param("watchId", secondWatchId)
                .param("ratingId", ratingId)
                .update();
        RatingRow before = rating(ratingId);
        WatchRow secondWatch = watch(secondWatchId);
        long events = count("outbox_events");

        change(ratingId, userId, privateBody("1.5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        remove(ratingId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(watch(secondWatchId)).isEqualTo(secondWatch);
        assertThat(count("outbox_events")).isEqualTo(events);
        assertAggregateMatchesRatings(movieId);
        assertAggregateMatchesRatings(secondMovieId);
    }

    @Test
    @DisplayName("RATED가 아닌 감상에 연결된 평가는 수정·삭제하지 않는다")
    void brokenWatchStatusRollsBack() throws Exception {
        long ratingId = create(movieId, userId, privateBody("3.0")).path("ratingId").asLong();
        long watchId = rating(ratingId).watchRecordId();
        jdbc.sql("UPDATE watch_records SET status='WATCHED_UNRATED', rated_at=NULL WHERE id=:id")
                .param("id", watchId)
                .update();
        RatingRow before = rating(ratingId);
        WatchRow watchBefore = watch(watchId);
        long events = count("outbox_events");

        change(ratingId, userId, privateBody("1.5"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        remove(ratingId, userId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(watch(watchId)).isEqualTo(watchBefore);
        assertThat(count("outbox_events")).isEqualTo(events);
        assertAggregateMatchesRatings(movieId);
    }

    @Test
    @DisplayName("스포일러 수정은 저장·응답·입력 오류에서 생성 계약을 따른다")
    void spoilerUpdateContract() throws Exception {
        long ratingId = create(movieId, userId, publicBody("3.0", "기존")).path("ratingId").asLong();
        JsonNode response =
                update(
                        ratingId,
                        userId,
                        "{\"score\":3.5,\"comment\":\"반전\",\"visibility\":\"PUBLIC\",\"spoiler\":true}");
        assertThat(response.path("spoiler").isBoolean()).isTrue();
        assertThat(response.path("spoiler").asBoolean()).isTrue();
        assertThat(rating(ratingId).spoiler()).isTrue();
        assertThat(rating(ratingId).comment()).isEqualTo("반전");
        assertThat(reviews(otherUserId).path("items").path(0).path("spoiler").asBoolean()).isTrue();
        assertThat(reviews(otherUserId).path("items").path(0).path("comment").asText())
                .isEqualTo("반전");

        update(ratingId, userId, "{\"score\":3.5,\"comment\":\"원문\",\"visibility\":\"PUBLIC\"}");
        assertThat(rating(ratingId).spoiler()).isFalse();
        assertThat(reviews(otherUserId).path("items").path(0).path("spoiler").asBoolean())
                .isFalse();
        update(ratingId, userId, "{\"score\":3.5,\"comment\":null,\"spoiler\":false}");
        assertThat(rating(ratingId).comment()).isNull();
        assertThat(rating(ratingId).spoiler()).isFalse();
        RatingRow before = rating(ratingId);
        long updates = eventCount("UPDATE");

        for (String body :
                List.of(
                        "{\"score\":4.5,\"spoiler\":null}",
                        "{\"score\":4.5,\"spoiler\":\"true\"}",
                        "{\"score\":4.5,\"spoiler\":1}",
                        "{\"score\":4.5,\"spoiler\":[]}",
                        "{\"score\":4.5,\"spoiler\":{}}",
                        "{\"score\":4.5,\"comment\":null,\"spoiler\":true}",
                        "{\"score\":4.5,\"comment\":\"  \",\"visibility\":\"PUBLIC\",\"spoiler\":true}",
                        "{\"score\":4.5,\"comment\":\"\\u200B\",\"visibility\":\"PUBLIC\",\"spoiler\":true}",
                        "{\"score\":4.5,\"comment\":\"\\u0001\",\"visibility\":\"PUBLIC\",\"spoiler\":true}")) {
            assertValidation(change(ratingId, userId, body));
        }
        assertThat(rating(ratingId)).isEqualTo(before);
        assertThat(eventCount("UPDATE")).isEqualTo(updates);
    }

    private Connection lockRow(String table, long id) throws SQLException {
        if (!table.equals("users") && !table.equals("movies")) {
            throw new IllegalArgumentException("잠금 대상은 사용자 또는 영화여야 합니다.");
        }
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
                statement.setLong(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            connection.close();
            throw exception;
        }
    }

    private void waitForBlocked(Connection blocker, int expected) throws Exception {
        int blockerPid;
        try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_backend_pid()");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            blockerPid = rows.getInt(1);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long blocked = 0;
        // 잠금 연결을 재사용해 폴링이 Hikari 풀의 네 번째 연결을 차지하지 않게 한다.
        try (PreparedStatement clearSnapshot =
                        blocker.prepareStatement("SELECT pg_stat_clear_snapshot()");
                PreparedStatement statement =
                        blocker.prepareStatement(
                                """
                        WITH RECURSIVE sessions AS (
                            SELECT pid, pg_blocking_pids(pid) AS blockers
                            FROM pg_stat_activity WHERE datname = current_database()
                        ), waiting(pid) AS (
                            SELECT pid FROM sessions
                            WHERE ? = ANY(blockers)
                            UNION
                            SELECT session.pid FROM sessions session
                            JOIN waiting parent ON parent.pid = ANY(session.blockers)
                        )
                        SELECT count(*) FROM waiting
                        """)) {
            statement.setInt(1, blockerPid);
            while (System.nanoTime() < deadline) {
                // 같은 트랜잭션의 통계 캐시를 지워 새 잠금 대기를 관측한다.
                clearSnapshot.execute();
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    blocked = rows.getLong(1);
                }
                if (blocked >= expected) {
                    return;
                }
                Thread.sleep(25);
            }
        }
        assertThat(blocked).as("실제 PostgreSQL 행 잠금 대기 세션 수").isGreaterThanOrEqualTo(expected);
    }

    private int concurrentUpdate(CountDownLatch start, long ratingId, long user, String body)
            throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return change(ratingId, user, body).andReturn().getResponse().getStatus();
    }

    private int concurrentDelete(CountDownLatch start, long ratingId, long user) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return remove(ratingId, user).andReturn().getResponse().getStatus();
    }

    private JsonNode create(long movie, long user, String body) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/movies/{movieId}/ratings", movie)
                                        .header("Authorization", bearer(user))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return json(result);
    }

    private JsonNode update(long ratingId, long user, String body) throws Exception {
        return json(change(ratingId, user, body).andExpect(status().isOk()).andReturn());
    }

    private ResultActions change(long ratingId, long user, String body) throws Exception {
        return mockMvc.perform(
                put("/api/v1/ratings/{ratingId}", ratingId)
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions remove(long ratingId, long user) throws Exception {
        return mockMvc.perform(
                delete("/api/v1/ratings/{ratingId}", ratingId)
                        .header("Authorization", bearer(user)));
    }

    private JsonNode reviews(long user) throws Exception {
        return json(
                mockMvc.perform(
                                get("/api/v1/movies/{movieId}/reviews", movieId)
                                        .header("Authorization", bearer(user)))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private void assertDetailAggregate(long movie, String average, int count) throws Exception {
        mockMvc.perform(get("/api/v1/movies/{movieId}", movie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(Double.parseDouble(average)))
                .andExpect(jsonPath("$.ratingCount").value(count));
    }

    private JsonNode watchRecords(long user) throws Exception {
        return json(
                mockMvc.perform(get("/api/v1/watch-records").header("Authorization", bearer(user)))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertValidation(ResultActions result) throws Exception {
        String body =
                result.andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("Exception", "SQL", "java.", "org.postgresql");
    }

    private void assertAggregateMatchesRatings(long movie) {
        MovieAggregate actual = movieAggregate(movie);
        MovieAggregate expected =
                jdbc.sql(
                                """
                                SELECT count(*)::int AS rating_count,
                                       coalesce(round(avg(score),2),0)::numeric(3,2)::text AS average_rating
                                FROM ratings WHERE movie_id=:movieId
                                """)
                        .param("movieId", movie)
                        .query(
                                (row, number) ->
                                        new MovieAggregate(
                                                row.getInt("rating_count"),
                                                row.getString("average_rating")))
                        .single();
        assertThat(actual).isEqualTo(expected);
    }

    private RatingRow rating(long ratingId) {
        return jdbc.sql(
                        """
                        SELECT watch_record_id, score, comment, visibility, is_spoiler,
                               created_at, updated_at
                        FROM ratings WHERE id=:ratingId
                        """)
                .param("ratingId", ratingId)
                .query(
                        (row, number) ->
                                new RatingRow(
                                        row.getObject("watch_record_id", Long.class),
                                        row.getBigDecimal("score"),
                                        row.getString("comment"),
                                        row.getString("visibility"),
                                        row.getBoolean("is_spoiler"),
                                        row.getObject("created_at", OffsetDateTime.class)
                                                .toInstant(),
                                        row.getObject("updated_at", OffsetDateTime.class)
                                                .toInstant()))
                .single();
    }

    private WatchRow watch(long watchId) {
        return jdbc.sql("SELECT status, watched_at, rated_at FROM watch_records WHERE id=:watchId")
                .param("watchId", watchId)
                .query(
                        (row, number) ->
                                new WatchRow(
                                        row.getString("status"),
                                        instant(row.getObject("watched_at", OffsetDateTime.class)),
                                        instant(row.getObject("rated_at", OffsetDateTime.class))))
                .single();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private long frameId(long ratingId) {
        return jdbc.sql("SELECT id FROM frames WHERE rating_id=:ratingId")
                .param("ratingId", ratingId)
                .query(Long.class)
                .single();
    }

    private MovieAggregate movieAggregate(long movie) {
        return jdbc.sql("SELECT rating_count, average_rating::text FROM movies WHERE id=:movieId")
                .param("movieId", movie)
                .query((row, number) -> new MovieAggregate(row.getInt(1), row.getString(2)))
                .single();
    }

    private JsonNode event(String action) throws Exception {
        String message =
                jdbc.sql(
                                "SELECT message::text FROM outbox_events"
                                        + " WHERE message->'payload'->>'action'=:action")
                        .param("action", action)
                        .query(String.class)
                        .single();
        return objectMapper.readTree(message);
    }

    private String eventStatus(String action) {
        return jdbc.sql(
                        "SELECT status FROM outbox_events"
                                + " WHERE message->'payload'->>'action'=:action")
                .param("action", action)
                .query(String.class)
                .single();
    }

    private long eventCount(String action) {
        return jdbc.sql(
                        "SELECT count(*) FROM outbox_events"
                                + " WHERE message->'payload'->>'action'=:action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void failingTrigger(String table, String operation, String name) {
        jdbc.sql(
                        "CREATE FUNCTION "
                                + name
                                + "() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'forced mutation failure'; END; $$ LANGUAGE plpgsql")
                .update();
        jdbc.sql(
                        "CREATE TRIGGER "
                                + name
                                + " BEFORE "
                                + operation
                                + " ON "
                                + table
                                + " FOR EACH ROW EXECUTE FUNCTION "
                                + name
                                + "()")
                .update();
    }

    private long insertFlavor() {
        return jdbc.sql(
                        """
                        INSERT INTO popcorn_flavors(code,display_name,color,sort_order,cluster_id,representative_keywords)
                        VALUES ('FLAVOR_MUTATION','수정 맛','#123456',1,0,'[]'::jsonb) RETURNING id
                        """)
                .query(Long.class)
                .single();
    }

    private long insertUser(String email, String nickname, String state) {
        return jdbc.sql(
                        """
                        INSERT INTO users(email,nickname,birth_date,gender,status)
                        VALUES (:email,:nickname,DATE '2000-01-01','OTHER',:status) RETURNING id
                        """)
                .param("email", email)
                .param("nickname", nickname)
                .param("status", state)
                .query(Long.class)
                .single();
    }

    private long insertMovie(int tmdbId, long flavor) {
        return jdbc.sql(
                        "INSERT INTO movies(tmdb_id,title,representative_flavor_id)"
                                + " VALUES (:tmdbId,'평가 수정 영화',:flavorId) RETURNING id")
                .param("tmdbId", tmdbId)
                .param("flavorId", flavor)
                .query(Long.class)
                .single();
    }

    private void truncate() {
        jdbc.sql(
                        "TRUNCATE TABLE outbox_events, frames, ratings, watch_records,"
                                + " movies, popcorn_flavors, users RESTART IDENTITY CASCADE")
                .update();
    }

    private String bearer(long user) {
        return "Bearer " + tokenProvider.createAccessToken(user);
    }

    private static String privateBody(String score) {
        return "{\"score\":" + score + ",\"visibility\":\"PRIVATE\"}";
    }

    private static String publicBody(String score, String comment) {
        return "{\"score\":"
                + score
                + ",\"comment\":\""
                + comment
                + "\",\"visibility\":\"PUBLIC\"}";
    }

    private record RatingRow(
            Long watchRecordId,
            java.math.BigDecimal score,
            String comment,
            String visibility,
            boolean spoiler,
            Instant createdAt,
            Instant updatedAt) {}

    private record WatchRow(String status, Instant watchedAt, Instant ratedAt) {}

    private record MovieAggregate(int count, String average) {}
}
