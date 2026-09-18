package com.feelm.backend.domain.recommendation;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/** API-048: 실제 마이그레이션과 PostgreSQL을 사용하는 추천 이력 HTTP 계약 테스트. */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
@DisplayName("API-048 추천 이력 조회")
class RecommendationHistoryApiContractTest extends SharedPostgresContractTest {

    private static final long USER = 10;
    private static final long OTHER_USER = 20;
    private static final String PATH = "/api/v1/recommendations/history";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void fixtures() {
        jdbc.execute(
                "TRUNCATE TABLE user_movie_dismissals, recommendation_items,"
                        + " recommendation_sets, parties, watch_records, ratings, movies, users"
                        + " RESTART IDENTITY CASCADE");
        user(USER, "user");
        user(OTHER_USER, "other");
        movie(100, "첫 영화", "https://image.test/100.jpg");
        movie(101, "두 번째 영화", null);
        movie(102, "버퍼 영화", null);
        movie(103, "타인 영화", null);
        movie(104, "파티 영화", null);
    }

    @Test
    @DisplayName("T1 본인의 개인 추천 중 실제 노출된 영화만 반환한다")
    void returnsOnlyExposedItemsFromOwnedPersonalSets() throws Exception {
        personalSet(1000, USER, "input-1", "2026-09-15T03:00:00Z");
        item(3000, 1000, 100, "TASTE", 1, true, "취향 이유");
        item(3001, 1000, 102, "DISCOVERY", 2, false, "버퍼 이유");
        personalSet(2000, OTHER_USER, "input-2", "2026-09-15T04:00:00Z");
        item(4000, 2000, 103, "TASTE", 1, true, "타인 이유");
        partySet(3000, USER, "2026-09-15T05:00:00Z");
        item(5000, 3000, 104, "PARTY", 1, true, "파티 이유");

        history(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recommendationSetId").value(1000))
                .andExpect(jsonPath("$.items[0].items.length()").value(1))
                .andExpect(jsonPath("$.items[0].items[0].recommendationItemId").value(3000))
                .andExpect(jsonPath("$.items[0].items[0].movie.movieId").value(100))
                .andExpect(jsonPath("$.items[0].items[0].movie.title").value("첫 영화"))
                .andExpect(
                        jsonPath("$.items[0].items[0].movie.posterUrl")
                                .value("https://image.test/100.jpg"));
    }

    @Test
    @DisplayName("T2 세트는 최신순, 세트 안 영화는 원래 추천 순서로 반환한다")
    void ordersSetsNewestFirstAndItemsByPosition() throws Exception {
        personalSet(1000, USER, "input-1", "2026-09-15T03:00:00Z");
        personalSet(1001, USER, "input-2", "2026-09-15T03:00:00Z");
        item(3000, 1000, 100, "TASTE", 2, true, "둘째");
        item(3001, 1000, 101, "DISCOVERY", 1, true, "첫째");
        item(3002, 1001, 102, "TASTE", 1, true, "최신");

        history(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationSetId").value(1001))
                .andExpect(jsonPath("$.items[1].recommendationSetId").value(1000))
                .andExpect(jsonPath("$.items[1].items[0].recommendationItemId").value(3001))
                .andExpect(jsonPath("$.items[1].items[1].recommendationItemId").value(3000));
    }

    @Test
    @DisplayName("생성 시각이 역전되어도 저장 순서로 이력을 페이지 이동한다")
    void paginatesByStoredOrderAcrossClockSkew() throws Exception {
        personalSet(1000, USER, "input-1", "2026-09-17T03:00:00Z");
        personalSet(1001, USER, "input-2", "2026-09-16T03:00:00Z");
        item(3000, 1000, 100, "TASTE", 1, true, "첫째");
        item(3001, 1001, 101, "TASTE", 1, true, "둘째");

        MvcResult first =
                history(USER, null, 1)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].recommendationSetId").value(1001))
                        .andReturn();
        String cursor =
                objectMapper
                        .readTree(first.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asText();
        history(USER, cursor, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationSetId").value(1000));
    }

    @Test
    @DisplayName("T3 추천 이유, 관심없음과 현재 감상 상태를 함께 반환한다")
    void returnsReasonDismissedAndWatchStatus() throws Exception {
        personalSet(1000, USER, "input-1", "2026-09-15T03:00:00Z");
        item(3000, 1000, 100, "TASTE", 1, true, "취향과 잘 맞아요");
        item(3001, 1000, 101, "DISCOVERY", 2, true, "새로운 취향이에요");
        jdbc.update(
                "INSERT INTO user_movie_dismissals(user_id,movie_id,recommendation_item_id)"
                        + " VALUES (?,?,?)",
                USER,
                100,
                3000);
        jdbc.update(
                "INSERT INTO watch_records(user_id,movie_id,status) VALUES (?,?,?)",
                USER,
                100,
                "WATCHED_UNRATED");
        jdbc.update("INSERT INTO ratings(user_id,movie_id,score) VALUES (?,?,?)", USER, 101, 5);

        history(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].items[0].reason.summary").value("취향과 잘 맞아요"))
                .andExpect(jsonPath("$.items[0].items[0].dismissed").value(true))
                .andExpect(jsonPath("$.items[0].items[0].watchStatus").value("WATCHED_UNRATED"))
                .andExpect(jsonPath("$.items[0].items[1].dismissed").value(false))
                .andExpect(jsonPath("$.items[0].items[1].watchStatus").value("RATED"));
    }

    @Test
    @DisplayName("T4 추천 세트 단위 cursor로 중복 없이 다음 페이지를 조회한다")
    void paginatesByRecommendationSetWithStableCursor() throws Exception {
        for (int index = 0; index < 3; index++) {
            long setId = 1000 + index;
            personalSet(setId, USER, "input-" + index, "2026-09-1" + (3 + index) + "T03:00:00Z");
            item(3000 + index, setId, 100 + index, "TASTE", 1, true, "이유");
        }

        MvcResult first =
                history(USER, null, 2)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isString())
                        .andReturn();
        String cursor =
                objectMapper
                        .readTree(first.getResponse().getContentAsString())
                        .get("nextCursor")
                        .asText();

        history(USER, cursor, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recommendationSetId").value(1000))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @DisplayName("T5 노출된 개인 추천 이력이 없으면 빈 페이지를 반환한다")
    void returnsEmptyPageWhenNoExposedHistoryExists() throws Exception {
        personalSet(1000, USER, "input-1", "2026-09-15T03:00:00Z");
        item(3000, 1000, 100, "TASTE", 1, false, "버퍼 이유");

        history(USER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("T6 잘못된 cursor는 검증 오류로 거절한다")
    void rejectsMalformedCursor() throws Exception {
        history(USER, "not-a-cursor", 20)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T7 페이지 크기는 1 이상 50 이하여야 한다")
    void rejectsInvalidPageSize() throws Exception {
        history(USER, null, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        history(USER, null, 51)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T8 로그인하지 않으면 추천 이력을 조회할 수 없다")
    void requiresAuthentication() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private org.springframework.test.web.servlet.ResultActions history(long userId)
            throws Exception {
        return history(userId, null, null);
    }

    private org.springframework.test.web.servlet.ResultActions history(
            long userId, String cursor, Integer size) throws Exception {
        var request =
                get(PATH).header("Authorization", "Bearer " + tokens.createAccessToken(userId));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (size != null) {
            request.param("size", size.toString());
        }
        return mvc.perform(request);
    }

    private void user(long id, String nickname) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                nickname + "@example.test",
                nickname);
    }

    private void movie(long id, String title, String posterUrl) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title,poster_url) VALUES (?,?,?,?)",
                id,
                Math.toIntExact(id + 10000),
                title,
                posterUrl);
    }

    private void personalSet(long id, long userId, String inputVersion, String generatedAt) {
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,user_id,input_version,model_version,generated_at) VALUES (?,?,?,'model-v1',?)",
                id,
                userId,
                inputVersion,
                OffsetDateTime.parse(generatedAt));
    }

    private void partySet(long id, long ownerId, String generatedAt) {
        jdbc.update(
                "INSERT INTO parties(id,name,owner_id,status) VALUES (1,'파티',?,'ACTIVE')", ownerId);
        jdbc.update(
                "INSERT INTO recommendation_sets"
                        + "(id,party_id,input_version,model_version,generated_at) VALUES (?,1,'party-input','model-v1',?)",
                id,
                OffsetDateTime.parse(generatedAt));
    }

    private void item(
            long id,
            long setId,
            long movieId,
            String type,
            int position,
            boolean exposed,
            String reason) {
        jdbc.update(
                "INSERT INTO recommendation_items"
                        + "(id,set_id,movie_id,type,position,reason,exposed_at)"
                        + " VALUES (?,?,?,?,?,jsonb_build_object('summary',?::text),?)",
                id,
                setId,
                movieId,
                type,
                position,
                reason,
                exposed ? OffsetDateTime.parse("2026-09-15T03:00:00Z") : null);
    }
}
