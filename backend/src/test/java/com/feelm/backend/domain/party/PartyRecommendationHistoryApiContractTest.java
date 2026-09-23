package com.feelm.backend.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API-083 파티 추천 이력 조회의 HTTP 계약(S15P21E106-815).
 *
 * <p>세트·항목·노출 시각과 이력 표({@code party_movie_exposures}) 행을 SQL로 직접 넣는다. API-058 흐름(Redis 스냅샷·다시 받기)을
 * 거치지 않아도 이력 조회는 정본 표만 읽기 때문이며, 다시 받기 이전 세트(항목마다 노출 시각이 다름)도 같은 픽스처로 표현한다. 운영 경로가 실제로 표를 채우는지는
 * {@code PartyRecommendationRefreshApiContractTest}가, 백필은 {@code
 * PartyMovieExposuresBackfillMigrationTest}가 검증한다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("파티 추천 이력 조회 — API-083")
class PartyRecommendationHistoryApiContractTest extends SharedPostgresContractTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-17T05:21:00Z");
    private static final OffsetDateTime T1 = at("2026-09-18T10:00:00Z");
    private static final OffsetDateTime T2 = at("2026-09-18T11:00:00Z");
    private static final OffsetDateTime T3 = at("2026-09-19T10:00:00Z");
    private static final long OWNER = 10;
    private static final long MEMBER = 20;
    private static final long OUTSIDER = 30;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;

    @BeforeEach
    void fixtures() {
        jdbc.execute(
                "TRUNCATE TABLE party_movie_exposures, recommendation_items, recommendation_sets,"
                        + " party_members, parties, movie_genres, genres, movie_ott_providers,"
                        + " movies, otts, users RESTART IDENTITY CASCADE");
        for (long id : List.of(OWNER, MEMBER, OUTSIDER)) {
            user(id);
        }
        long ottId =
                jdbc.queryForObject(
                        "INSERT INTO otts(code,name,tmdb_provider_id) VALUES ('NETFLIX','NETFLIX',1001)"
                                + " RETURNING id",
                        Long.class);
        // id를 직접 넣으면 시퀀스가 진행되지 않아 뒤에 도는 다른 테스트의 시퀀스 삽입과 충돌한다. 시퀀스가 만들게 둔다.
        long genreId =
                jdbc.queryForObject(
                        "INSERT INTO genres(code,name,tmdb_genre_id) VALUES ('DRAMA','드라마',18)"
                                + " RETURNING id",
                        Long.class);
        for (long movieId = 101; movieId <= 110; movieId++) {
            movie(movieId);
        }
        jdbc.update("INSERT INTO movie_ott_providers(movie_id,ott_id) VALUES (101,?)", ottId);
        jdbc.update("INSERT INTO movie_genres(movie_id,genre_id) VALUES (101,?)", genreId);
    }

    @Test
    @DisplayName("T1 인증 없는 요청은 401이다")
    void requiresAuthentication() throws Exception {
        long party = party("CLOSED");

        mvc.perform(get(path(party))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("T2 멤버가 아니거나 파티가 없으면 404로 존재를 숨긴다")
    void hidesPartyFromNonMemberAndMissingParty() throws Exception {
        long party = party("ACTIVE");
        long set = set(party, "input-a");
        item(set, 101, 1, T1);

        history(party, OUTSIDER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        history(9999, OWNER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("T3 종료 파티의 노출 항목을 세트 최신순, 세트 안은 노출 시각·순위 순으로 돌려준다")
    void returnsExposedItemsOfClosedPartyNewestSetFirst() throws Exception {
        long party = party("CLOSED");
        long older = set(party, "input-a");
        long item1 = item(older, 101, 1, T1);
        long item2 = item(older, 102, 2, T1);
        long item3 = item(older, 103, 3, T2);
        item(older, 104, 4, null);
        long newer = set(party, "input-b");
        long item5 = item(newer, 105, 1, T3);
        long item6 = item(newer, 106, 2, T3);

        ResultActions result =
                history(party, OWNER)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items", hasSize(2)))
                        .andExpect(jsonPath("$.hasNext").value(false))
                        .andExpect(jsonPath("$.nextCursor").isEmpty())
                        .andExpect(jsonPath("$.items[0].recommendationSetId").value(newer))
                        .andExpect(jsonPath("$.items[0].items", hasSize(2)))
                        .andExpect(jsonPath("$.items[1].recommendationSetId").value(older))
                        .andExpect(jsonPath("$.items[1].items", hasSize(3)))
                        .andExpect(jsonPath("$.items[1].items[0].type").value("PARTY"))
                        .andExpect(jsonPath("$.items[1].items[0].movie.movieId").value(101))
                        .andExpect(jsonPath("$.items[1].items[0].movie.title").value("movie101"))
                        .andExpect(
                                jsonPath("$.items[1].items[0].movie.posterUrl")
                                        .value("/posters/101.jpg"))
                        .andExpect(jsonPath("$.items[1].items[0].movie.releaseYear").value(1999))
                        .andExpect(jsonPath("$.items[1].items[0].movie.genres[0]").value("드라마"))
                        .andExpect(jsonPath("$.items[1].items[0].movie.averageRating").value(4.3))
                        .andExpect(
                                jsonPath("$.items[1].items[0].movie.ottProviders[0]")
                                        .value("NETFLIX"))
                        .andExpect(jsonPath("$.items[1].items[1].movie.genres", hasSize(0)))
                        .andExpect(jsonPath("$.items[1].items[1].movie.ottProviders", hasSize(0)));

        assertThat(itemIds(result, 0)).containsExactly(item5, item6);
        assertThat(itemIds(result, 1)).containsExactly(item1, item2, item3);
        JsonNode olderSet = body(result).get("items").get(1).get("items");
        assertThat(Instant.parse(olderSet.get(0).get("exposedAt").asText()))
                .isEqualTo(T1.toInstant());
        assertThat(Instant.parse(olderSet.get(2).get("exposedAt").asText()))
                .isEqualTo(T2.toInstant());
    }

    @Test
    @DisplayName("T4 활성 파티도 같은 규칙으로 읽는다")
    void readsActivePartyHistoryToo() throws Exception {
        long party = party("ACTIVE");
        long set = set(party, "input-a");
        item(set, 101, 1, T1);
        item(set, 102, 2, T1);

        history(party, MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].items", hasSize(2)));
    }

    @Test
    @DisplayName("T5 같은 영화가 여러 세트에 노출되면 최신 세트의 항목만 남는다")
    void keepsOnlyLatestSetExposureOfRepeatedMovie() throws Exception {
        long party = party("CLOSED");
        long older = set(party, "input-a");
        item(older, 101, 1, T1); // X — 최신 세트에서 다시 노출되어 빠진다
        long y = item(older, 102, 2, T1);
        long newer = set(party, "input-b");
        long x = item(newer, 101, 1, T3);
        long z = item(newer, 103, 2, T3);

        ResultActions result =
                history(party, OWNER)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items", hasSize(2)));

        assertThat(itemIds(result, 0)).containsExactly(x, z);
        assertThat(itemIds(result, 1)).containsExactly(y);
    }

    @Test
    @DisplayName("T6 노출 항목이 전부 중복인 세트는 빠지고 size는 남는 세트 기준이다")
    void omitsSetWhoseExposedItemsAreAllRepeated() throws Exception {
        long party = party("CLOSED");
        long a = set(party, "input-a");
        item(a, 101, 1, T1);
        long b = set(party, "input-b");
        item(b, 101, 1, T2);
        long c = set(party, "input-c");
        item(c, 102, 1, T3);

        history(party, OWNER, null, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].recommendationSetId").value(c))
                .andExpect(jsonPath("$.items[1].recommendationSetId").value(b))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        assertThat(a).isLessThan(b);
    }

    @Test
    @DisplayName("T7 세트 단위 커서로 반복 없이 넘긴다")
    void paginatesBySetWithStableCursor() throws Exception {
        long party = party("CLOSED");
        List<Long> sets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long set = set(party, "input-" + i);
            item(set, 101 + i, 1, T1.plusHours(i));
            sets.add(set);
        }
        List<Long> expected = List.of(sets.get(2), sets.get(1), sets.get(0));
        String cursor = null;

        for (int page = 0; page < expected.size(); page++) {
            boolean hasNext = page < expected.size() - 1;
            ResultActions result =
                    history(party, OWNER, cursor, 1)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.items", hasSize(1)))
                            .andExpect(
                                    jsonPath("$.items[0].recommendationSetId")
                                            .value(expected.get(page)))
                            .andExpect(jsonPath("$.hasNext").value(hasNext));
            if (hasNext) {
                cursor = body(result).get("nextCursor").asText();
                assertThat(cursor).isNotBlank();
            } else {
                result.andExpect(jsonPath("$.nextCursor").isEmpty());
            }
        }
    }

    @Test
    @DisplayName("T8 노출된 항목이 없으면 빈 페이지다")
    void ignoresUnexposedCandidatesAndReturnsEmptyPage() throws Exception {
        long party = party("ACTIVE");
        long set = set(party, "input-a");
        item(set, 101, 1, null);
        item(set, 102, 2, null);

        history(party, OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @ParameterizedTest(name = "T9 size={0}")
    @ValueSource(strings = {"0", "51", "word"})
    void rejectsInvalidPageSize(String size) throws Exception {
        long party = party("ACTIVE");

        mvc.perform(get(path(party)).param("size", size).header("Authorization", bearer(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T9 깨진 커서는 400이다")
    void rejectsMalformedCursor() throws Exception {
        long party = party("ACTIVE");

        history(party, OWNER, "INVALID!", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("T10 다시 받기 이전 세트(항목마다 노출 시각이 다름)도 노출 시각 순으로 정렬한다")
    void keepsLegacyMixedExposureOrderWithinSet() throws Exception {
        long party = party("CLOSED");
        long set = set(party, "legacy");
        long third = item(set, 103, 3, T1); // 순위 3이 먼저 노출됨
        long first = item(set, 101, 1, T2);
        long second = item(set, 102, 2, T2);

        ResultActions result = history(party, OWNER).andExpect(status().isOk());

        assertThat(itemIds(result, 0)).containsExactly(third, first, second);
    }

    @Test
    @DisplayName("T11 종료 파티의 멤버십이 남은 일반 멤버도 읽는다")
    void formerMemberOfClosedPartyStillReads() throws Exception {
        long party = party("CLOSED");
        long set = set(party, "input-a");
        item(set, 101, 1, T1);

        history(party, MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    private ResultActions history(long party, long userId) throws Exception {
        return history(party, userId, null, null);
    }

    private ResultActions history(long party, long userId, String cursor, Integer size)
            throws Exception {
        var request = get(path(party)).header("Authorization", bearer(userId));
        if (cursor != null) request.param("cursor", cursor);
        if (size != null) request.param("size", String.valueOf(size));
        return mvc.perform(request);
    }

    private String path(long party) {
        return "/api/v1/parties/" + party + "/recommendations/history";
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private List<Long> itemIds(ResultActions result, int setIndex) throws Exception {
        List<Long> ids = new ArrayList<>();
        body(result)
                .get("items")
                .get(setIndex)
                .get("items")
                .forEach(item -> ids.add(item.get("recommendationItemId").asLong()));
        return ids;
    }

    private static OffsetDateTime at(String iso) {
        return Instant.parse(iso).atOffset(ZoneOffset.UTC);
    }

    /** 오너 10과 멤버 20이 속한 파티. 종료 파티도 멤버십 행을 이력으로 남긴다(API-013 D6). */
    private long party(String status) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO parties(name,owner_id,status,created_at,closed_reason,closed_at)"
                                + " VALUES ('party',?,?,?,?,?) RETURNING id",
                        Long.class,
                        OWNER,
                        status,
                        Timestamp.from(CREATED_AT),
                        status.equals("CLOSED") ? "OWNER_CLOSED" : null,
                        status.equals("CLOSED") ? Timestamp.from(CREATED_AT.plusSeconds(1)) : null);
        jdbc.update(
                "INSERT INTO party_members(party_id,user_id,role,joined_at)"
                        + " VALUES (?,?,'OWNER',?),(?,?,'MEMBER',?)",
                id,
                OWNER,
                Timestamp.from(CREATED_AT),
                id,
                MEMBER,
                Timestamp.from(CREATED_AT.plusSeconds(1)));
        return id;
    }

    private long set(long partyId, String inputId) {
        return jdbc.queryForObject(
                "INSERT INTO recommendation_sets"
                        + " (party_id,input_version,model_version,candidate_version,generated_at)"
                        + " VALUES (?,?,'v1',0,?) RETURNING id",
                Long.class,
                partyId,
                inputId,
                Timestamp.from(CREATED_AT));
    }

    /**
     * 노출 픽스처는 운영의 {@code PartyRecommendationRepository.expose()}와 같은 결과를 내야 한다(문장 모양은 다르다 — 운영은 파티
     * id를 인자로 받고 여기서는 세트에서 읽는다). {@code exposed_at}만 찍고 {@code party_movie_exposures}를 빼먹으면 이력에서
     * 항목이 조용히 사라진다. 승자 가드({@code set_id < EXCLUDED.set_id})는 운영과 같다.
     */
    private long item(long setId, long movieId, int position, OffsetDateTime exposedAt) {
        long id =
                jdbc.queryForObject(
                        "INSERT INTO recommendation_items(set_id,movie_id,type,position,reason,exposed_at)"
                                + " VALUES (?,?,'PARTY',?,CAST('{}' AS jsonb),?) RETURNING id",
                        Long.class,
                        setId,
                        movieId,
                        position,
                        exposedAt);
        if (exposedAt != null) {
            jdbc.update(
                    """
                    INSERT INTO party_movie_exposures
                        (party_id, movie_id, recommendation_item_id, set_id, exposed_at)
                    SELECT s.party_id, ?, ?, ?, ?
                    FROM recommendation_sets s
                    WHERE s.id = ? AND s.party_id IS NOT NULL
                    ON CONFLICT (party_id, movie_id) DO UPDATE SET
                        recommendation_item_id = EXCLUDED.recommendation_item_id,
                        set_id = EXCLUDED.set_id,
                        exposed_at = EXCLUDED.exposed_at
                    WHERE party_movie_exposures.set_id < EXCLUDED.set_id
                    """,
                    movieId,
                    id,
                    setId,
                    exposedAt,
                    setId);
        }
        return id;
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "user" + id + "@example.test",
                "user" + id);
    }

    private void movie(long id) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title,poster_url,release_date,average_rating)"
                        + " VALUES (?,?,?,?,DATE '1999-10-15',4.3)",
                id,
                id + 1000,
                "movie" + id,
                "/posters/" + id + ".jpg");
    }
}
