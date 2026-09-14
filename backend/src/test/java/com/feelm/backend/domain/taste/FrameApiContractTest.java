package com.feelm.backend.domain.taste;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedTestRuntime;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
@DisplayName("프레임 조회 API — API-042·043 계약")
@SharedTestRuntime
class FrameApiContractTest {

    private static final String FRAMES_PATH = "/api/v1/users/me/frames";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUpSchema() {
        dropTables();
        createTables();
    }

    @Test
    @DisplayName("프레임 목록과 상세는 인증이 필요하다")
    void frameQueriesRequireAuthentication() throws Exception {
        mockMvc.perform(get(FRAMES_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/frames/{frameId}", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("내 프레임을 최신순으로 페이지 조회하고 다른 사용자의 프레임은 제외한다")
    void listsOwnedFramesWithStableCursorPagination() throws Exception {
        insertFlavor(1, "FLAVOR_A");
        insertMovie(101, "오래된 영화", 1L, "2020-01-01", "/old.jpg");
        insertMovie(102, "최신 영화 A", 1L, "2021-01-01", "/new-a.jpg");
        insertMovie(103, "최신 영화 B", 1L, "2022-01-01", "/new-b.jpg");
        insertMovie(104, "타인 영화", 1L, "2023-01-01", "/other.jpg");
        insertRating(201, 10, 101, null, "3.5", null, null, "2026-09-01T00:00:00Z");
        insertRating(202, 10, 102, null, "4.0", null, null, "2026-09-02T00:00:00Z");
        insertRating(203, 10, 103, null, "4.5", null, null, "2026-09-02T00:00:00Z");
        insertRating(204, 99, 104, null, "5.0", null, null, "2026-09-03T00:00:00Z");
        insertFrame(301, 201, 10, 101, "2026-09-01T00:00:00Z");
        insertFrame(302, 202, 10, 102, "2026-09-02T00:00:00Z");
        insertFrame(303, 203, 10, 103, "2026-09-02T00:00:00Z");
        insertFrame(304, 204, 99, 104, "2026-09-03T00:00:00Z");

        MvcResult firstPage =
                mockMvc.perform(
                                get(FRAMES_PATH)
                                        .param("size", "2")
                                        .header("Authorization", bearerFor(10)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalCount").value(3))
                        .andExpect(jsonPath("$.items", hasSize(2)))
                        .andExpect(jsonPath("$.items[0].frameId").value(303))
                        .andExpect(jsonPath("$.items[0].movie.movieId").value(103))
                        .andExpect(jsonPath("$.items[0].score").value(4.5))
                        .andExpect(jsonPath("$.items[0].flavorCode").value("FLAVOR_A"))
                        .andExpect(jsonPath("$.items[1].frameId").value(302))
                        .andExpect(jsonPath("$.hasNext").value(true))
                        .andExpect(jsonPath("$.nextCursor").isString())
                        .andReturn();

        JsonNode body = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        String cursor = body.get("nextCursor").asText();
        mockMvc.perform(
                        get(FRAMES_PATH)
                                .param("size", "2")
                                .param("cursor", cursor)
                                .header("Authorization", bearerFor(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].frameId").value(301))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("프레임 목록은 맛 필터를 적용하고 빈 결과도 페이지 계약으로 반환한다")
    void filtersFramesByFlavorAndReturnsEmptyPage() throws Exception {
        insertFlavor(1, "FLAVOR_A");
        insertFlavor(2, "FLAVOR_B");
        insertMovie(101, "A 영화", 1L, null, null);
        insertMovie(102, "B 영화", 2L, null, null);
        insertRating(201, 10, 101, null, "4.0", null, null, "2026-09-01T00:00:00Z");
        insertRating(202, 10, 102, null, "5.0", null, null, "2026-09-02T00:00:00Z");
        insertFrame(301, 201, 10, 101, "2026-09-01T00:00:00Z");
        insertFrame(302, 202, 10, 102, "2026-09-02T00:00:00Z");

        mockMvc.perform(
                        get(FRAMES_PATH)
                                .param("flavorCode", "FLAVOR_A")
                                .header("Authorization", bearerFor(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].frameId").value(301));

        mockMvc.perform(
                        get(FRAMES_PATH)
                                .param("flavorCode", "UNKNOWN")
                                .header("Authorization", bearerFor(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("잘못된 페이지 크기와 cursor는 검증 오류로 응답한다")
    void rejectsInvalidPaginationParameters() throws Exception {
        for (String size : new String[] {"0", "51"}) {
            mockMvc.perform(
                            get(FRAMES_PATH)
                                    .param("size", size)
                                    .header("Authorization", bearerFor(10)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(
                        get(FRAMES_PATH)
                                .param("cursor", "not-a-valid-cursor")
                                .header("Authorization", bearerFor(10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * 형식은 맞지만 커서가 담을 수 있는 범위를 벗어난 시각. 상한을 넘는 값은 질의로 내려가면 드라이버나 DB가 거절해 클라이언트 입력이 500이 되고, 하한 미만은
     * 저장된 생성 시각일 수 없다.
     */
    @Test
    @DisplayName("허용 범위를 벗어난 시각을 담은 커서는 검증 오류로 응답한다")
    void rejectsUnstorableCursorInstant() throws Exception {
        String[] cursors = {
            forgedCursor("+999999999-12-31T23:59:59Z:1"),
            forgedCursor("+0500000-01-01T00:00:00Z:1"),
            forgedCursor("1900-01-01T00:00:00Z:1")
        };

        for (String cursor : cursors) {
            mockMvc.perform(
                            get(FRAMES_PATH)
                                    .param("cursor", cursor)
                                    .header("Authorization", bearerFor(10)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    /** 서버가 만들지 않은 커서를 흉내낸다. 인코딩 형식만 맞추고 값은 임의로 넣는다. */
    private String forgedCursor(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("본인 프레임 상세에서 영화, 평가, 감상 기록을 함께 조회한다")
    void getsOwnedFrameDetail() throws Exception {
        insertFlavor(1, "FLAVOR_A");
        insertOtt(1, "NETFLIX", "넷플릭스");
        insertMovie(101, "파이트 클럽", 1L, "1999-10-15", "/fight-club.jpg");
        insertGenre(1, "드라마");
        insertGenre(2, "스릴러");
        insertMovieGenre(101, 1);
        insertMovieGenre(101, 2);
        insertPerson(1, "데이비드 핀처");
        insertMoviePerson(101, 1, "DIRECTOR", 1);
        insertWatchRecord(
                401,
                10,
                101,
                1,
                "2026-08-28T05:21:00Z",
                "2026-08-30T05:00:00Z",
                "2026-08-30T05:21:00Z");
        insertRating(201, 10, 101, 401L, "4.5", "밤에 보면 안 됨", "PUBLIC", "2026-08-30T05:21:00Z");
        insertFrame(301, 201, 10, 101, "2026-08-30T05:21:00Z");

        mockMvc.perform(get("/api/v1/frames/{frameId}", 301).header("Authorization", bearerFor(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frameId").value(301))
                .andExpect(jsonPath("$.movie.movieId").value(101))
                .andExpect(jsonPath("$.movie.title").value("파이트 클럽"))
                .andExpect(jsonPath("$.movie.posterUrl").value("/fight-club.jpg"))
                .andExpect(jsonPath("$.movie.releaseYear").value(1999))
                .andExpect(jsonPath("$.movie.genres[0]").value("드라마"))
                .andExpect(jsonPath("$.movie.genres[1]").value("스릴러"))
                .andExpect(jsonPath("$.movie.directors[0]").value("데이비드 핀처"))
                .andExpect(jsonPath("$.flavorCode").value("FLAVOR_A"))
                .andExpect(jsonPath("$.rating.ratingId").value(201))
                .andExpect(jsonPath("$.rating.score").value(4.5))
                .andExpect(jsonPath("$.rating.comment").value("밤에 보면 안 됨"))
                .andExpect(jsonPath("$.rating.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.watchRecord.ott.code").value("NETFLIX"))
                .andExpect(jsonPath("$.watchRecord.ott.name").value("넷플릭스"))
                .andExpect(jsonPath("$.watchRecord.clickedAt").value("2026-08-28T05:21:00Z"))
                .andExpect(jsonPath("$.watchRecord.watchedAt").value("2026-08-30T05:00:00Z"))
                .andExpect(jsonPath("$.watchRecord.ratedAt").value("2026-08-30T05:21:00Z"));
    }

    @Test
    @DisplayName("감상 기록 없이 평가한 프레임도 null 감상 정보와 함께 조회한다")
    void getsFrameWithoutWatchRecord() throws Exception {
        insertMovie(101, "기록 없는 영화", null, null, null);
        insertRating(201, 10, 101, null, "3.0", null, null, "2026-09-01T00:00:00Z");
        insertFrame(301, 201, 10, 101, "2026-09-01T00:00:00Z");

        mockMvc.perform(get("/api/v1/frames/{frameId}", 301).header("Authorization", bearerFor(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchRecord.ott").isEmpty())
                .andExpect(jsonPath("$.watchRecord.clickedAt").isEmpty())
                .andExpect(jsonPath("$.watchRecord.watchedAt").isEmpty())
                .andExpect(jsonPath("$.watchRecord.ratedAt").isEmpty());
    }

    @Test
    @DisplayName("없는 프레임과 타인 프레임은 같은 리소스 없음 오류로 응답한다")
    void hidesMissingAndOtherUsersFrames() throws Exception {
        insertMovie(101, "타인 영화", null, null, null);
        insertRating(201, 99, 101, null, "5.0", null, null, "2026-09-01T00:00:00Z");
        insertFrame(301, 201, 99, 101, "2026-09-01T00:00:00Z");

        for (long frameId : new long[] {301, 999}) {
            mockMvc.perform(
                            get("/api/v1/frames/{frameId}", frameId)
                                    .header("Authorization", bearerFor(10)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    private String bearerFor(long userId) {
        return "Bearer " + tokenProvider.createAccessToken(userId);
    }

    private void dropTables() {
        for (String table :
                new String[] {
                    "movie_people",
                    "people",
                    "movie_genres",
                    "genres",
                    "frames",
                    "ratings",
                    "watch_records",
                    "otts",
                    "movies",
                    "popcorn_flavors"
                }) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private void createTables() {
        jdbcTemplate.execute(
                "CREATE TABLE popcorn_flavors (id bigint PRIMARY KEY, code varchar(30) NOT NULL)");
        jdbcTemplate.execute(
                """
                CREATE TABLE movies (
                    id bigint PRIMARY KEY, title varchar(255) NOT NULL, poster_url varchar(500),
                    release_date date, representative_flavor_id bigint
                )
                """);
        jdbcTemplate.execute(
                "CREATE TABLE otts (id bigint PRIMARY KEY, code varchar(30), name varchar(100))");
        jdbcTemplate.execute(
                """
                CREATE TABLE watch_records (
                    id bigint PRIMARY KEY, user_id bigint NOT NULL, movie_id bigint NOT NULL, ott_id bigint,
                    clicked_at timestamp with time zone, watched_at timestamp with time zone,
                    rated_at timestamp with time zone
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE ratings (
                    id bigint PRIMARY KEY, user_id bigint NOT NULL, movie_id bigint NOT NULL,
                    watch_record_id bigint, score numeric(2, 1) NOT NULL, comment varchar(200),
                    visibility varchar(10), created_at timestamp with time zone NOT NULL
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE frames (
                    id bigint PRIMARY KEY, rating_id bigint NOT NULL, user_id bigint NOT NULL,
                    movie_id bigint NOT NULL, created_at timestamp with time zone NOT NULL
                )
                """);
        jdbcTemplate.execute(
                "CREATE TABLE genres (id bigint PRIMARY KEY, name varchar(100) NOT NULL)");
        jdbcTemplate.execute("CREATE TABLE movie_genres (movie_id bigint, genre_id bigint)");
        jdbcTemplate.execute(
                "CREATE TABLE people (id bigint PRIMARY KEY, name varchar(100) NOT NULL)");
        jdbcTemplate.execute(
                "CREATE TABLE movie_people (movie_id bigint, person_id bigint, role varchar(10), display_order int)");
    }

    private void insertFlavor(long id, String code) {
        jdbcTemplate.update("INSERT INTO popcorn_flavors (id, code) VALUES (?, ?)", id, code);
    }

    private void insertMovie(
            long id, String title, Long flavorId, String releaseDate, String posterUrl) {
        jdbcTemplate.update(
                "INSERT INTO movies (id, title, poster_url, release_date, representative_flavor_id) VALUES (?, ?, ?, ?, ?)",
                id,
                title,
                posterUrl,
                releaseDate == null ? null : Date.valueOf(releaseDate),
                flavorId);
    }

    private void insertRating(
            long id,
            long userId,
            long movieId,
            Long watchRecordId,
            String score,
            String comment,
            String visibility,
            String createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO ratings
                    (id, user_id, movie_id, watch_record_id, score, comment, visibility, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                userId,
                movieId,
                watchRecordId,
                new BigDecimal(score),
                comment,
                visibility,
                Timestamp.from(Instant.parse(createdAt)));
    }

    private void insertFrame(long id, long ratingId, long userId, long movieId, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO frames (id, rating_id, user_id, movie_id, created_at) VALUES (?, ?, ?, ?, ?)",
                id,
                ratingId,
                userId,
                movieId,
                Timestamp.from(Instant.parse(createdAt)));
    }

    private void insertOtt(long id, String code, String name) {
        jdbcTemplate.update("INSERT INTO otts (id, code, name) VALUES (?, ?, ?)", id, code, name);
    }

    private void insertWatchRecord(
            long id,
            long userId,
            long movieId,
            long ottId,
            String clickedAt,
            String watchedAt,
            String ratedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_records
                    (id, user_id, movie_id, ott_id, clicked_at, watched_at, rated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                userId,
                movieId,
                ottId,
                Timestamp.from(Instant.parse(clickedAt)),
                Timestamp.from(Instant.parse(watchedAt)),
                Timestamp.from(Instant.parse(ratedAt)));
    }

    private void insertGenre(long id, String name) {
        jdbcTemplate.update("INSERT INTO genres (id, name) VALUES (?, ?)", id, name);
    }

    private void insertMovieGenre(long movieId, long genreId) {
        jdbcTemplate.update(
                "INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);
    }

    private void insertPerson(long id, String name) {
        jdbcTemplate.update("INSERT INTO people (id, name) VALUES (?, ?)", id, name);
    }

    private void insertMoviePerson(long movieId, long personId, String role, int displayOrder) {
        jdbcTemplate.update(
                "INSERT INTO movie_people (movie_id, person_id, role, display_order) VALUES (?, ?, ?, ?)",
                movieId,
                personId,
                role,
                displayOrder);
    }
}
