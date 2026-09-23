package com.feelm.backend.domain.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisState;
import com.feelm.backend.support.SharedTestRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@SharedTestRuntime
@DisplayName("API-030 영화 상세 VIEW 이벤트 계약")
class MovieDetailViewEventContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}";
    private static final String VIEW_METRIC = "feelm.movie.view.outbox";
    private static final long MOVIE = 1_559_001L;
    private static final long USER = 1_559_101L;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        dropFailureTrigger();
        cleanFixtures();
        SharedRedisState.clear(redisTemplate);
        seedFixtures();
    }

    @AfterEach
    void cleanUp() {
        dropFailureTrigger();
        cleanFixtures();
    }

    @Test
    @DisplayName("회원의 정상 상세 조회는 계약 payload의 VIEW Outbox 한 건을 기록한다")
    void memberDetailPublishesOneViewEventWithContractPayload() throws Exception {
        Instant before = Instant.now();
        JsonNode response = response(memberRequest(MOVIE));
        Instant after = Instant.now();

        assertThat(response.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "movieId",
                        "title",
                        "originalTitle",
                        "synopsis",
                        "releaseDate",
                        "runtimeMinutes",
                        "genres",
                        "countries",
                        "directors",
                        "cast",
                        "posterUrl",
                        "backdropUrl",
                        "flavorCode",
                        "averageRating",
                        "ratingCount",
                        "ottProviders",
                        "myRating",
                        "myWatchStatus",
                        "recommendation");
        assertThat(response.path("movieId").asLong()).isEqualTo(MOVIE);
        assertThat(response.path("title").asText()).isEqualTo("VIEW 이벤트 영화");

        Map<String, Object> outbox =
                jdbc.queryForMap(
                        """
                        SELECT event_id::text AS event_id, event_type, topic, partition_key,
                               message::text AS message
                        FROM outbox_events
                        WHERE event_type = 'VIEW' AND partition_key = ?
                        """,
                        USER);
        JsonNode message = objectMapper.readTree((String) outbox.get("message"));

        assertThat(countViewEvents()).isEqualTo(1);
        assertThat(outbox.get("event_type")).isEqualTo("VIEW");
        assertThat(outbox.get("topic")).isEqualTo("feelm.view.v1");
        assertThat(((Number) outbox.get("partition_key")).longValue()).isEqualTo(USER);
        assertThat(UUID.fromString(message.path("eventId").asText()).toString())
                .isEqualTo(outbox.get("event_id"));
        assertThat(message.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "eventId", "eventType", "occurredAt", "userId", "payload");
        assertThat(message.path("eventType").asText()).isEqualTo("VIEW");
        assertThat(message.path("userId").asLong()).isEqualTo(USER);
        assertThat(Instant.parse(message.path("occurredAt").asText())).isBetween(before, after);
        assertThat(message.path("payload").properties())
                .extracting(Map.Entry::getKey)
                .containsExactly("movieId");
        assertThat(message.path("payload").path("movieId").asLong()).isEqualTo(MOVIE);
    }

    @Test
    @DisplayName("비회원 처리 인증과 탈퇴 회원의 정상 조회는 VIEW를 기록하지 않는다")
    void ineligibleRequestsDoNotPublish() throws Exception {
        assertThat(response(get(PATH, MOVIE)).path("myRating").isNull()).isTrue();
        assertThat(
                        response(get(PATH, MOVIE).header("Authorization", "Bearer invalid-token"))
                                .path("myRating")
                                .isNull())
                .isTrue();
        assertThat(
                        response(get(PATH, MOVIE).header("Authorization", "Basic invalid"))
                                .path("myRating")
                                .isNull())
                .isTrue();
        String refreshToken = tokenProvider.createRefreshToken(USER, "view-event-refresh");
        assertThat(
                        response(get(PATH, MOVIE).header("Authorization", "Bearer " + refreshToken))
                                .path("myRating")
                                .isNull())
                .isTrue();

        String withdrawnAccessToken = tokenProvider.createAccessToken(USER);
        jdbc.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = ?", USER);
        assertThat(
                        response(
                                        get(PATH, MOVIE)
                                                .header(
                                                        "Authorization",
                                                        "Bearer " + withdrawnAccessToken))
                                .path("movieId")
                                .asLong())
                .isEqualTo(MOVIE);

        assertThat(countViewEvents()).isZero();
    }

    @Test
    @DisplayName("회원의 존재하지 않는 영화와 입력 검증 실패는 VIEW를 기록하지 않는다")
    void failedDetailRequestsDoNotPublish() throws Exception {
        mockMvc.perform(memberRequest(9_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(memberRequest(0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(countViewEvents()).isZero();
    }

    @Test
    @DisplayName("VIEW Outbox 기록 실패는 정상 상세 응답을 바꾸지 않고 관측된다")
    void outboxFailureKeepsSuccessfulDetailResponse() throws Exception {
        failViewOutboxInsert();
        double failuresBefore = meterRegistry.counter(VIEW_METRIC, "outcome", "failed").count();

        JsonNode response = response(memberRequest(MOVIE));

        assertThat(response.path("movieId").asLong()).isEqualTo(MOVIE);
        assertThat(response.path("title").asText()).isEqualTo("VIEW 이벤트 영화");
        assertThat(countViewEvents()).isZero();
        assertThat(meterRegistry.counter(VIEW_METRIC, "outcome", "failed").count())
                .isEqualTo(failuresBefore + 1);
    }

    private MockHttpServletRequestBuilder memberRequest(long movieId) {
        return get(PATH, movieId)
                .header("Authorization", "Bearer " + tokenProvider.createAccessToken(USER));
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int countViewEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events"
                        + " WHERE event_type = 'VIEW' AND partition_key = ?",
                Integer.class,
                USER);
    }

    private void failViewOutboxInsert() {
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION test_fail_view_outbox()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'VIEW' THEN
                        RAISE EXCEPTION 'forced VIEW outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_view_outbox
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION test_fail_view_outbox()
                """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_view_outbox ON outbox_events");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_view_outbox()");
    }

    private void seedFixtures() {
        jdbc.update(
                """
                INSERT INTO users (id, email, nickname, birth_date, gender, status)
                VALUES (?, 'view-event@feelm.test', 'VIEW 테스트', DATE '1999-03-14',
                        'OTHER', 'ACTIVE')
                """,
                USER);
        jdbc.update(
                "INSERT INTO movies (id, tmdb_id, title) VALUES (?, 1559001, 'VIEW 이벤트 영화')",
                MOVIE);
    }

    private void cleanFixtures() {
        jdbc.update(
                "DELETE FROM outbox_events WHERE event_type = 'VIEW' AND partition_key = ?", USER);
        jdbc.update("DELETE FROM movies WHERE id = ?", MOVIE);
        jdbc.update("DELETE FROM users WHERE id = ?", USER);
    }
}
