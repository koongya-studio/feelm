package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisContainer;
import com.feelm.backend.support.SharedRedisState;
import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** URL 디코딩·Tomcat·실제 JWT 필터·DB까지 거치는 검색 입력 경계 회귀 테스트. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "feelm.auth.mock.enabled=false"
        })
@DisplayName("API-028 영화 검색 — 실제 HTTP 입력 경계")
class MovieSearchInputHttpContractTest extends SharedPostgresContractTest {

    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    @LocalServerPort private int port;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void seedCommittedFixture() {
        SharedRedisState.clear(redisTemplate);
        // 서버 요청은 다른 스레드에서 실행된다. 테스트 트랜잭션 없이 한 번 적재하고 읽기만 한다.
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE id = ?",
                        Long.class,
                        MovieSearchFixture.MEMBER_ID)
                == 0) {
            MovieSearchFixture.seed(jdbc);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "RELEVANCE,1e-16384",
        "RELEVANCE,1e-1000000",
        "RELEVANCE,1e131072",
        "RELEVANCE,0e-1000000",
        "RATING,1e-16384",
        "RATING,1e-1000000"
    })
    @DisplayName("발급 cursor 숫자의 DB 표현 범위 초과를 400으로 거부한다")
    void rejectsUnrepresentableCursorNumbers(String sort, String number) throws Exception {
        ObjectNode payload = issuedCursor(sort);
        payload.put("sortValue", new BigDecimal(number));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.getPrepareStatementCount()).isPositive();
        assertRejectedBeforeSearch("q=matrix&sort=" + sort + "&cursor=" + encode(payload));
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("unicodeHttpSearches")
    @DisplayName("실제 HTTP와 PostgreSQL C.UTF-8에서 비ASCII 대소문자 변경 후 모든 페이지가 같다")
    void preservesUnicodeCursorPagesOverHttp(
            String target, String sort, String uppercase, String lowercase) throws Exception {
        assertUnicodePages(target, sort, uppercase, lowercase, uppercase, lowercase);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("greekSearchTargets")
    @DisplayName("Greek 단어 중간 시그마도 부분 검색과 같은 cursor 페이지를 유지한다")
    void preservesGreekPartialSearchPagesOverHttp(String target, String sort) throws Exception {
        assertUnicodePages(target, sort, "ΟΣΚΑΡΑ", "οσκαρα", "ΟΣ", "ος");
    }

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({
        "TITLE,RELEVANCE", "TITLE,RATING", "TITLE,RELEASE_DATE",
        "ORIGINAL_TITLE,RELEVANCE", "ORIGINAL_TITLE,RATING", "ORIGINAL_TITLE,RELEASE_DATE"
    })
    @DisplayName("부분·오타 검색으로 찾지 못하는 Greek 다중 단어도 full-text로 모두 찾는다")
    void preservesGreekFullTextOnlyPagesOverHttp(String target, String sort) throws Exception {
        String filler = " b8cf29c781c34d7dab531ea28ed94056".repeat(20);
        String upperTitle = "ΟΣ" + filler + " ALPHA";
        String lowerTitle = "ος" + filler + " alpha";
        assertThat(
                        jdbc.queryForObject(
                                "SELECT similarity(lower(? COLLATE \"und-x-icu\"), lower(? COLLATE \"und-x-icu\"))",
                                Double.class,
                                lowerTitle,
                                "ος alpha"))
                .isLessThan(0.3);
        assertUnicodePages(target, sort, upperTitle, lowerTitle, "ΟΣ ALPHA", "ος alpha");
    }

    static Stream<Arguments> greekSearchTargets() {
        return Stream.of("TITLE", "ORIGINAL_TITLE", "ACTOR", "DIRECTOR")
                .flatMap(
                        target ->
                                Stream.of("RELEVANCE", "RATING", "RELEASE_DATE")
                                        .map(sort -> Arguments.of(target, sort)));
    }

    private void assertUnicodePages(
            String target,
            String sort,
            String upperTitle,
            String lowerTitle,
            String uppercase,
            String lowercase)
            throws Exception {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT datctype FROM pg_database WHERE datname = current_database()",
                                String.class))
                .isEqualTo("C.UTF-8");
        try {
            for (long id = 901; id <= 903; id++) {
                String name = id >= 902 ? upperTitle : lowerTitle;
                MovieSearchFixture.movie(
                        jdbc,
                        id,
                        target.equals("TITLE") ? name : "경계 영화 " + id,
                        target.equals("ORIGINAL_TITLE") ? name : null,
                        "2020-01-01",
                        4.0);
                if (target.equals("ACTOR") || target.equals("DIRECTOR")) {
                    jdbc.update(
                            "INSERT INTO people (id, tmdb_person_id, name) VALUES (?, ?, ?)",
                            id,
                            990000 + id,
                            name);
                    jdbc.update(
                            "INSERT INTO movie_people (movie_id, person_id, role) VALUES (?, ?, ?)",
                            id,
                            id,
                            target);
                }
            }
            String upperQuery =
                    "q="
                            + URLEncoder.encode(uppercase, StandardCharsets.UTF_8)
                            + "&sort="
                            + sort
                            + "&size=1";
            String lowerQuery =
                    "q="
                            + URLEncoder.encode(lowercase, StandardCharsets.UTF_8)
                            + "&sort="
                            + sort
                            + "&size=1";
            JsonNode page = readSuccess(upperQuery);
            assertThat(movieIds(page)).containsExactly(903L);
            assertThat(page.path("hasNext").asBoolean()).isTrue();
            List<Long> actual = new ArrayList<>(movieIds(page));
            for (int pageNumber = 0; page.path("hasNext").asBoolean(); pageNumber++) {
                assertThat(pageNumber).isLessThan(3);
                String cursor = page.path("nextCursor").asText();
                JsonNode unchanged = readSuccess(upperQuery + "&cursor=" + cursor);
                page = readSuccess(lowerQuery + "&cursor=" + cursor);
                assertThat(movieIds(page))
                        .as("같은 cursor의 대소문자 변경 전후 영화 ID")
                        .containsExactlyElementsOf(movieIds(unchanged));
                assertThat(page).isEqualTo(unchanged);
                actual.addAll(movieIds(page));
            }
            assertThat(actual).containsExactly(903L, 902L, 901L).doesNotHaveDuplicates();
            assertThat(page.path("nextCursor").isNull()).isTrue();
            assertThat(readSuccess(lowerQuery)).isEqualTo(readSuccess(upperQuery));
        } finally {
            // 이 클래스의 격리 DB에 이번 사례가 넣은 행만 정리한다.
            jdbc.update("DELETE FROM movie_people WHERE movie_id BETWEEN 901 AND 903");
            jdbc.update("DELETE FROM people WHERE id BETWEEN 901 AND 903");
            jdbc.update("DELETE FROM movies WHERE id BETWEEN 901 AND 903");
        }
    }

    static Stream<Arguments> unicodeHttpSearches() {
        return Stream.of("TITLE", "ORIGINAL_TITLE", "ACTOR", "DIRECTOR")
                .flatMap(
                        target ->
                                Stream.of("RELEVANCE", "RATING", "RELEASE_DATE")
                                        .flatMap(
                                                sort ->
                                                        Stream.of(
                                                                Arguments.of(
                                                                        target,
                                                                        sort,
                                                                        "\u039f\u03a3",
                                                                        "\u03bf\u03c2"),
                                                                Arguments.of(
                                                                        target, sort, "\u0130",
                                                                        "i\u0307"))));
    }

    private static List<Long> movieIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("movieId").asLong()));
        return ids;
    }

    @ParameterizedTest
    @ValueSource(strings = {"+6000000-01-01", "-5000-01-01", "+5874898-01-01", "-4713-11-23"})
    @DisplayName("발급 cursor 날짜의 DB 표현 범위 초과를 400으로 거부한다")
    void rejectsUnrepresentableCursorDates(String date) throws Exception {
        ObjectNode payload = issuedCursor("RELEASE_DATE");
        payload.put("releaseDate", date);
        assertRejectedBeforeSearch("q=matrix&sort=RELEASE_DATE&cursor=" + encode(payload));
    }

    @ParameterizedTest
    @CsvSource({
        "RELEVANCE,sortValue,1e-16383,0",
        "RELEVANCE,sortValue,1e131071,2",
        "RELEASE_DATE,releaseDate,-4713-11-24,0",
        "RELEASE_DATE,releaseDate,+5874897-12-31,2"
    })
    @DisplayName("DB 표현 범위의 양쪽 경계 값은 실제 검색 비교에 그대로 사용한다")
    void acceptsRepresentableCursorBoundaries(String sort, String field, String value, int count)
            throws Exception {
        ObjectNode payload = issuedCursor(sort);
        if (field.equals("sortValue")) {
            payload.put(field, new BigDecimal(value));
        } else {
            payload.put(field, value);
        }
        JsonNode page = readSuccess("q=matrix&sort=" + sort + "&cursor=" + encode(payload));
        assertThat(page.path("items").size()).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "q=a%00b",
                "genre=a%00b",
                "country=a%00b",
                "genre=ACTION&genre=a%00b",
                "genre=a%00b&genre=ACTION",
                "country=US&country=a%00b",
                "country=a%00b&country=US"
            })
    @DisplayName("URL에 인코딩된 NUL은 400이며 검색 결과를 반환하지 않는다")
    void rejectsNulInDecodedParameters(String query) throws Exception {
        assertRejectedBeforeSearch(query);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "genre%5B256%5D=DRAMA",
                "country%5B256%5D=KR",
                "ott%5B256%5D=21",
                "genre%5B-1%5D=DRAMA",
                "genre%5B2147483648%5D=DRAMA",
                "genre%5B0%5D.class=DRAMA",
                "genre%5B%5D=DRAMA",
                "genre.name=DRAMA"
            })
    @DisplayName("인덱스·중첩 이름은 모델 바인딩 전에 400으로 거부한다")
    void rejectsIndexedAndNestedParameters(String parameter) throws Exception {
        assertRejectedBeforeSearch("q=matrix&" + parameter);
    }

    @Test
    @DisplayName("정상 반복 배열 파라미터는 실제 HTTP에서 OR/AND 조건을 유지한다")
    void acceptsRepeatedArrayParameters() throws Exception {
        JsonNode page =
                readSuccess("q=matrix&genre=ACTION&genre=SF&country=AU&country=US&ott=21&ott=22");
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("movieId").asLong()).isEqualTo(101L);
        assertThat(page.path("items").get(1).path("movieId").asLong()).isEqualTo(102L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"q=matrix", "q=a%00b", "q=matrix&genre%5B256%5D=DRAMA"})
    @DisplayName("인증되지 않은 요청은 잘못된 입력 검증보다 먼저 401로 거부한다")
    void rejectsAnonymousRequestsBeforeBinding(String query) throws Exception {
        assertUnauthorized(request(query, false), "UNAUTHORIZED");
    }

    @ParameterizedTest
    @CsvSource({
        "TAMPERED,UNAUTHORIZED",
        "REFRESH,UNAUTHORIZED",
        "SIGNUP,UNAUTHORIZED",
        "EXPIRED,TOKEN_EXPIRED"
    })
    @DisplayName("실제 HTTP에서 변조·다른 용도·만료 토큰은 검색 전에 거부한다")
    void rejectsNonAccessOrInvalidTokens(String kind, String expectedCode) throws Exception {
        String token =
                switch (kind) {
                    case "TAMPERED" -> {
                        String access =
                                tokenProvider.createAccessToken(MovieSearchFixture.MEMBER_ID);
                        int signatureStart = access.lastIndexOf('.') + 1;
                        char changed = access.charAt(signatureStart) == 'A' ? 'B' : 'A';
                        yield access.substring(0, signatureStart)
                                + changed
                                + access.substring(signatureStart + 1);
                    }
                    case "REFRESH" ->
                            tokenProvider.createRefreshToken(
                                    MovieSearchFixture.MEMBER_ID, "search-boundary-test");
                    case "SIGNUP" ->
                            tokenProvider.createSignupToken(
                                    new TokenProvider.SignupTokenClaims(
                                            "KAKAO", "search-boundary-test", null, null));
                    case "EXPIRED" ->
                            new JjwtTokenProvider(
                                            new JwtProperties(
                                                    jwtProperties.secret(),
                                                    Duration.ofMinutes(-1),
                                                    jwtProperties.refreshTokenTtl(),
                                                    jwtProperties.signupTokenTtl(),
                                                    jwtProperties.refreshCookie()))
                                    .createAccessToken(MovieSearchFixture.MEMBER_ID);
                    default -> throw new IllegalArgumentException(kind);
                };
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        statistics.clear();
        assertUnauthorized(request("q=matrix", token), expectedCode);
        assertThat(statistics.getPrepareStatementCount()).as("거부한 토큰의 검색 SQL").isZero();
    }

    private ObjectNode issuedCursor(String sort) throws Exception {
        JsonNode first = readSuccess("q=matrix&sort=" + sort + "&size=1");
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        // 변경하지 않은 cursor의 성공도 확인해 인증이나 조건 key 오류로 잘못 통과하지 않게 한다.
        readSuccess("q=matrix&sort=" + sort + "&cursor=" + cursor);
        return (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(cursor));
    }

    private String encode(ObjectNode payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(payload));
    }

    private JsonNode readSuccess(String query) throws Exception {
        HttpResponse<String> response = request(query, true);
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private void assertInvalid(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.has("items")).isFalse();
    }

    private void assertUnauthorized(HttpResponse<String> response, String expectedCode) {
        assertThat(response.statusCode()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(expectedCode);
        assertThat(body.has("items")).isFalse();
    }

    private void assertRejectedBeforeSearch(String query) throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        statistics.clear();
        assertInvalid(request(query, true));
        assertThat(statistics.getPrepareStatementCount()).as("회원의 잘못된 입력 검색 SQL").isZero();

        statistics.clear();
        assertUnauthorized(request(query, false), "UNAUTHORIZED");
        assertThat(statistics.getPrepareStatementCount()).as("같은 입력의 비회원 검색 SQL").isZero();
    }

    private HttpResponse<String> request(String query, boolean authenticated) throws Exception {
        return request(
                query,
                authenticated
                        ? tokenProvider.createAccessToken(MovieSearchFixture.MEMBER_ID)
                        : null);
    }

    private HttpResponse<String> request(String query, String token) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/v1/movies/search?"
                                                + query))
                        .timeout(Duration.ofSeconds(15));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
