package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisContainer;
import com.feelm.backend.support.SharedRedisState;
import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.LongStream;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * API-028 계약을 실제 보안 필터·Controller·Service·PostgreSQL 경계에서 검증한다.
 *
 * <p>검색 구현의 타입을 가정하거나 테스트용 검색 Controller를 등록하지 않는다. 구현 전에도 컴파일되어 실제 HTTP 실패를 보여 주며, cursor는 서버가 발급한
 * 값을 다음 요청에 보내 검증한다.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@Transactional
@DisplayName("API-028 영화 검색 — PostgreSQL 계약")
class MovieSearchContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/movies/search";

    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void seed() {
        SharedRedisState.clear(redisTemplate);
        MovieSearchFixture.seed(jdbc);
    }

    @Test
    @DisplayName("비회원 검색은 실제 보안 필터에서 401로 거부하며 검색 결과를 반환하지 않는다")
    void rejectsAnonymousSearch() throws Exception {
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.MEMBER_ID, 103, "RATED");

        mockMvc.perform(get(PATH).param("q", "기생충"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("키워드 없이 필터만 사용해도 비회원은 검색할 수 없다")
    void rejectsAnonymousFilterOnlySearch() throws Exception {
        mockMvc.perform(get(PATH).param("genre", "DRAMA"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("회원에게 감상 기록이 없으면 다른 회원의 기록이 있어도 watched=false이다")
    void doesNotUseAnotherMembersWatchRecords() throws Exception {
        // 타인의 감상 완료 기록은 본인 watched에 쓰지 않는다.
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.OTHER_MEMBER_ID, 103, "RATED");
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.OTHER_MEMBER_ID, 104, "WATCHED_UNRATED");

        perform(memberRequest().param("country", "KR").param("sort", "RATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].movieId").value(103))
                .andExpect(jsonPath("$.items[0].watched").value(false))
                .andExpect(jsonPath("$.items[1].movieId").value(104))
                .andExpect(jsonPath("$.items[1].watched").value(false));
    }

    @ParameterizedTest
    @CsvSource({"WATCHED_UNRATED,true", "RATED,true", "LINK_CLICKED,false", "EXPIRED,false"})
    @DisplayName("본인의 WATCHED_UNRATED·RATED만 watched=true로 판정한다")
    void calculatesWatchedFromApprovedStatuses(String watchStatus, boolean expected)
            throws Exception {
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.MEMBER_ID, 103, watchStatus);

        perform(memberRequest().param("q", "기생충"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].movieId").value(103))
                .andExpect(jsonPath("$.items[0].watched").value(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WATCHED_UNRATED", "RATED"})
    @DisplayName("만료 이력이 함께 있어도 본인의 감상 완료 기록이 있으면 true이며 중복되지 않는다")
    void expiredHistoryDoesNotHideWatchedRecord(String watchStatus) throws Exception {
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.MEMBER_ID, 103, "EXPIRED");
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.MEMBER_ID, 103, watchStatus);

        perform(memberRequest().param("q", "기생충"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].movieId").value(103))
                .andExpect(jsonPath("$.items[0].watched").value(true));
    }

    @ParameterizedTest
    @CsvSource({"91001,true,false", "91002,false,true"})
    @DisplayName("같은 검색이라도 JWT의 사용자 ID에 따라 본인의 watched만 채워진다")
    void authenticatedUserDeterminesWatched(long userId, boolean parasite, boolean outlaws)
            throws Exception {
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.MEMBER_ID, 103, "RATED");
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.OTHER_MEMBER_ID, 104, "WATCHED_UNRATED");

        perform(
                        get(PATH)
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenProvider.createAccessToken(userId))
                                .param("country", "KR")
                                .param("sort", "RATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].movieId").value(103))
                .andExpect(jsonPath("$.items[0].watched").value(parasite))
                .andExpect(jsonPath("$.items[1].movieId").value(104))
                .andExpect(jsonPath("$.items[1].watched").value(outlaws));
    }

    @ParameterizedTest
    @MethodSource("filterOnlyRelevanceCases")
    @DisplayName("필터 단독 RELEVANCE는 정렬 생략·공백 검색어에서도 평점 다음 movieId 내림차순이다")
    void filterOnlyRelevanceUsesRatingThenMovieId(boolean explicitSort, String query)
            throws Exception {
        addFilterSortTies();
        MockHttpServletRequestBuilder request = memberRequest().param("genre", "ACTION", "DRAMA");
        if (explicitSort) request.param("sort", "RELEVANCE");
        if (query != null) request.param("q", query);

        assertThat(ids(read(request))).containsExactly(107L, 106L, 103L, 101L, 102L, 104L, 105L);
    }

    static Stream<Arguments> filterOnlyRelevanceCases() {
        return Stream.of(
                Arguments.of(false, null), Arguments.of(true, null),
                Arguments.of(false, "   "), Arguments.of(true, "   "));
    }

    @Test
    @DisplayName("필터 단독 RELEVANCE의 평점·ID 동률 경계를 cursor로 넘어도 중복·누락이 없다")
    void filterOnlyRelevanceCursorPreservesRatingOrder() throws Exception {
        addFilterSortTies();

        assertThat(collectPages("RELEVANCE", null, "ACTION", "DRAMA"))
                .containsExactly(107L, 106L, 103L, 101L, 102L, 104L, 105L);
    }

    private void addFilterSortTies() {
        // 더 오래된 영화도 평점이 같으면 movieId가 큰 순서여야 한다. 첫 페이지 중간에 동률이 걸린다.
        MovieSearchFixture.movie(jdbc, 106, "평점 동률 하나", null, "1990-01-01", 4.6);
        MovieSearchFixture.movie(jdbc, 107, "평점 동률 둘", null, "1991-01-01", 4.6);
        jdbc.update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (106, 13), (107, 13)");
    }

    @Test
    @DisplayName("정상 결과는 API-028 필드와 공통 cursor envelope를 그대로 반환한다")
    void returnsDocumentedJson() throws Exception {
        JsonNode body =
                read(memberRequest().param("q", "매트릭스").param("sort", "RATING").param("size", "1"));

        assertThat(body.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("totalCount", "items", "nextCursor", "hasNext");
        assertThat(body.path("totalCount").asLong()).isEqualTo(2L);
        JsonNode item = body.path("items").get(0);
        assertThat(item.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "movieId",
                        "title",
                        "originalTitle",
                        "posterUrl",
                        "releaseYear",
                        "genres",
                        "averageRating",
                        "ottProviders",
                        "watched");
        assertThat(item.path("movieId").isIntegralNumber()).isTrue();
        assertThat(item.path("movieId").asLong()).isEqualTo(101);
        assertThat(item.path("title").asText()).isEqualTo("매트릭스");
        assertThat(item.path("originalTitle").asText()).isEqualTo("The Matrix");
        assertThat(item.path("posterUrl").asText())
                .isEqualTo("https://example.test/posters/101.jpg");
        assertThat(item.path("releaseYear").asInt()).isEqualTo(1999);
        assertThat(item.path("averageRating").isNumber()).isTrue();
        assertThat(item.path("averageRating").asDouble()).isEqualTo(4.5);
        assertThat(strings(item.path("genres"))).containsExactlyInAnyOrder("액션", "SF");
        assertThat(strings(item.path("ottProviders")))
                .containsExactlyInAnyOrder("NETFLIX", "WATCHA");
        assertThat(item.path("watched").isBoolean()).isTrue();
        assertThat(item.path("watched").asBoolean()).isFalse();
        assertThat(body.path("hasNext").asBoolean()).isTrue();
        assertThat(body.path("nextCursor").asText()).isNotBlank();
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 items와 200을 반환한다")
    void noMatchesReturnsEmptyPage() throws Exception {
        perform(memberRequest().param("q", "존재할수없는검색어zxqv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalCount").value(0L))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("전체 결과 수는 정확하며 같은 조건의 cursor 다음 페이지에서도 유지된다")
    void totalCountIsExactAndStableAcrossCursorPages() throws Exception {
        JsonNode first =
                read(
                        memberRequest()
                                .param("genre", "SF")
                                .param("sort", "RATING")
                                .param("size", "1"));

        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("totalCount").asLong()).isEqualTo(2L);
        assertThat(first.path("hasNext").asBoolean()).isTrue();

        JsonNode second =
                read(
                        memberRequest()
                                .param("genre", "SF")
                                .param("sort", "RATING")
                                .param("size", "1")
                                .param("cursor", first.path("nextCursor").asText()));

        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("totalCount").asLong()).isEqualTo(2L);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
    }

    @ParameterizedTest(name = "q=[{0}]")
    @ValueSource(strings = {"", " ", "\t  "})
    @DisplayName("빈 검색어 또는 공백만 있고 필터가 없으면 400이다")
    void blankQueryWithoutFiltersIsInvalid(String query) throws Exception {
        invalid(memberRequest().param("q", query));
    }

    @Test
    @DisplayName("q와 모든 필터를 생략하면 400이다")
    void missingQueryAndFiltersIsInvalid() throws Exception {
        invalid(memberRequest());
    }

    @Test
    @DisplayName("빈 필터 값만 전달해도 실제 검색 조건이 없으면 400이다")
    void emptyFilterValuesAreNotSearchCriteria() throws Exception {
        invalid(memberRequest().param("genre", "").param("country", "").param("ott", ""));
    }

    @Test
    @DisplayName("yearFrom이 yearTo보다 크면 400이다")
    void reversedYearRangeIsInvalid() throws Exception {
        invalid(memberRequest().param("yearFrom", "2020").param("yearTo", "1990"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
        "size,0",
        "size,-1",
        "size,51",
        "size,abc",
        "sort,POPULARITY",
        "sort,relevance",
        "yearFrom,abc",
        "yearTo,20.5",
        "ott,NETFLIX"
    })
    @DisplayName("정렬·페이지 크기·숫자 파라미터 형식을 검증한다")
    void invalidParametersReturnValidationFailed(String name, String value) throws Exception {
        invalid(memberRequest().param("q", "기생충").param(name, value));
    }

    @ParameterizedTest(name = "키워드={0}")
    @MethodSource("keywordCases")
    @DisplayName("한글·영문 제목 부분 일치 및 배우·감독 이름으로 검색한다")
    void searchesTitlesAndPeople(String query, List<Long> expected) throws Exception {
        assertThat(ids(read(memberRequest().param("q", query))))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    static Stream<Arguments> keywordCases() {
        return Stream.of(
                Arguments.of("트릭", List.of(101L, 102L)),
                Arguments.of("mAtRiX", List.of(101L, 102L)),
                Arguments.of("키아누 리브스", List.of(101L, 102L)),
                Arguments.of("워쇼스키", List.of(101L, 102L)),
                Arguments.of("송강호", List.of(103L)),
                Arguments.of("봉준호", List.of(103L)));
    }

    @ParameterizedTest
    @CsvSource({"The Matrx,101", "매트릭스 리로디트,102"})
    @DisplayName("한글·영문 제목에 오타가 있어도 해당 영화를 찾는다")
    void toleratesTitleTypos(String query, long expectedMovieId) throws Exception {
        // 유사도 임계값·랭킹 공식은 계약에 없으므로 다른 유사 제목의 포함 여부를 고정하지 않는다.
        assertThat(ids(read(memberRequest().param("q", query)))).contains(expectedMovieId);
    }

    @Test
    @DisplayName("한 글자 검색어에 계약에 없는 최소 길이를 강제하지 않는다")
    void acceptsSingleCharacterQuery() throws Exception {
        assertThat(ids(read(memberRequest().param("q", "충")))).contains(103L);
    }

    @Test
    @DisplayName("검색어의 앞뒤 공백을 정규화한다")
    void trimsQuery() throws Exception {
        assertThat(ids(read(memberRequest().param("q", "  기생충  ")))).containsExactly(103L);
    }

    @Test
    @DisplayName("SQL처럼 생긴 검색어도 검색 값으로 취급한다")
    void treatsSqlSyntaxAsData() throws Exception {
        assertThat(ids(read(memberRequest().param("q", "' OR 1=1 --")))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movies", Long.class)).isEqualTo(5);
    }

    @ParameterizedTest(name = "필터={0}")
    @MethodSource("filterCases")
    @DisplayName("같은 필터의 여러 값은 OR이며 키워드 없이 사용할 수 있다")
    void combinesValuesOfSameFilterWithOr(String filter, String[] values, List<Long> expected)
            throws Exception {
        assertThat(ids(read(memberRequest().param(filter, values).param("sort", "RATING"))))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    static Stream<Arguments> filterCases() {
        return Stream.of(
                Arguments.of(
                        "genre", new String[] {"SF", "DRAMA"}, List.of(101L, 102L, 103L, 105L)),
                Arguments.of("country", new String[] {"AU", "KR"}, List.of(101L, 103L, 104L)),
                Arguments.of("ott", new String[] {"21", "22"}, List.of(101L, 102L, 103L, 104L)));
    }

    @Test
    @DisplayName("검색어와 서로 다른 모든 필터는 AND로 결합한다")
    void combinesDifferentFiltersAndQueryWithAnd() throws Exception {
        assertThat(
                        ids(
                                read(
                                        memberRequest()
                                                .param("q", "매트릭스")
                                                .param("genre", "ACTION", "DRAMA")
                                                .param("country", "US", "KR")
                                                .param("yearFrom", "1999")
                                                .param("yearTo", "1999")
                                                .param("ott", "21"))))
                .containsExactly(101L);
        assertThat(
                        ids(
                                read(
                                        memberRequest()
                                                .param("q", "매트릭스")
                                                .param("genre", "DRAMA")
                                                .param("country", "US")
                                                .param("yearFrom", "1999")
                                                .param("yearTo", "1999")
                                                .param("ott", "21"))))
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("yearCases")
    @DisplayName("연도 경계는 포함하며 한쪽 연도만으로도 조회한다")
    void supportsInclusiveAndOpenYearBounds(String from, String to, List<Long> expected)
            throws Exception {
        MockHttpServletRequestBuilder request = memberRequest();
        if (from != null) request.param("yearFrom", from);
        if (to != null) request.param("yearTo", to);
        assertThat(ids(read(request))).containsExactlyInAnyOrderElementsOf(expected);
    }

    static Stream<Arguments> yearCases() {
        return Stream.of(
                Arguments.of("1999", "1999", List.of(101L)),
                Arguments.of("2017", null, List.of(103L, 104L)),
                Arguments.of(null, "2003", List.of(101L, 102L)));
    }

    @Test
    @DisplayName("공백 검색어와 유효한 필터는 필터만으로 검색한다")
    void blankQueryWithFilterIsValid() throws Exception {
        assertThat(ids(read(memberRequest().param("q", "  ").param("genre", "SF"))))
                .containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    @DisplayName("여러 장르·국가·배우·OTT가 연결되어도 영화와 응답 배열이 중복되지 않는다")
    void joinsDoNotDuplicateMoviesOrCollections() throws Exception {
        jdbc.update(
                "INSERT INTO movie_people (movie_id, person_id, role) VALUES (101, 31, 'DIRECTOR')");
        JsonNode page =
                read(
                        memberRequest()
                                .param("q", "키아누")
                                .param("genre", "ACTION", "SF")
                                .param("country", "US", "AU")
                                .param("ott", "21", "22")
                                .param("sort", "RATING")
                                .param("size", "2"));
        assertThat(ids(page)).containsExactly(101L, 102L);
        assertThat(page.path("totalCount").asLong()).isEqualTo(2L);
        assertThat(page.path("hasNext").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(strings(page.path("items").get(0).path("genres"))).doesNotHaveDuplicates();
        assertThat(strings(page.path("items").get(0).path("ottProviders"))).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @CsvSource({"RATING,103,101,102,104,105", "RELEASE_DATE,103,104,102,101,105"})
    @DisplayName("평점·개봉일 내림차순이며 NULL 개봉일도 마지막 페이지까지 누락되지 않는다")
    void sortsAndPaginatesUnequalValues(String sort, long a, long b, long c, long d, long e)
            throws Exception {
        assertThat(collectPages(sort, null, "ACTION", "DRAMA", "SF", "THRILLER"))
                .containsExactly(a, b, c, d, e);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEVANCE", "RATING", "RELEASE_DATE"})
    @DisplayName("세 정렬 모두 동률에서 movieId 내림차순으로 cursor 왕복하며 중복·누락이 없다")
    void roundTripsOpaqueCursorForEverySort(String sort) throws Exception {
        MovieSearchFixture.pageMovies(jdbc, 7);
        assertThat(collectPages(sort, "은하의 기록"))
                .containsExactly(207L, 206L, 205L, 204L, 203L, 202L, 201L);
    }

    @Test
    @DisplayName("size 기본값은 20이며 마지막 페이지는 nextCursor=null이다")
    void defaultPageSizeIsTwenty() throws Exception {
        MovieSearchFixture.pageMovies(jdbc, 23);
        JsonNode first = read(memberRequest().param("q", "은하의 기록"));
        assertThat(ids(first)).hasSize(20);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        JsonNode last =
                read(
                        memberRequest()
                                .param("q", "은하의 기록")
                                .param("cursor", first.path("nextCursor").asText()));
        assertThat(ids(last)).hasSize(3).doesNotContainAnyElementsOf(ids(first));
        List<Long> all = new ArrayList<>(ids(first));
        all.addAll(ids(last));
        assertThat(all)
                .containsExactlyInAnyOrderElementsOf(
                        LongStream.rangeClosed(201, 223).boxed().toList());
        assertThat(last.path("hasNext").asBoolean()).isFalse();
        assertThat(last.path("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("size 최대 허용값 50을 사용할 수 있다")
    void acceptsMaximumPageSize() throws Exception {
        MovieSearchFixture.pageMovies(jdbc, 51);
        JsonNode page = read(memberRequest().param("q", "은하의 기록").param("size", "50"));
        assertThat(ids(page)).hasSize(50);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("sort 생략은 명시적 RELEVANCE와 동일하다")
    void defaultSortIsRelevance() throws Exception {
        JsonNode implicit = read(memberRequest().param("q", "매트릭스"));
        JsonNode explicit = read(memberRequest().param("q", "매트릭스").param("sort", "RELEVANCE"));
        assertThat(ids(implicit)).containsExactlyElementsOf(ids(explicit));
    }

    @ParameterizedTest
    @MethodSource("invalidCursors")
    @DisplayName("손상되거나 다른 구조의 cursor는 400 VALIDATION_FAILED이다")
    void rejectsMalformedCursors(String cursor) throws Exception {
        invalid(memberRequest().param("q", "매트릭스").param("cursor", cursor));
    }

    static Stream<String> invalidCursors() {
        return Stream.of("%%%", "a", "not-json", "{}", "[]", "{\"sort\":\"UNKNOWN\"}")
                .map(
                        value ->
                                value.startsWith("{") || value.startsWith("[")
                                        ? Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(
                                                        value.getBytes(StandardCharsets.UTF_8))
                                        : value);
    }

    @ParameterizedTest(name = "changed {0}={1}")
    @CsvSource({
        "q,기생충",
        "genre,DRAMA",
        "country,KR",
        "ott,999",
        "yearFrom,2000",
        "yearTo,2000",
        "sort,RATING",
        "sort,RELEASE_DATE"
    })
    @DisplayName("발급한 cursor는 검색조건 하나라도 변경되면 거부한다")
    void rejectsCursorForChangedSearchConditions(String name, String value) throws Exception {
        MultiValueMap<String, String> conditions = searchConditions();
        JsonNode first = read(memberRequest().params(conditions).param("size", "1"));
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        conditions.set(name, value);
        invalid(
                memberRequest()
                        .params(conditions)
                        .param("cursor", first.path("nextCursor").asText()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("RELEVANCE의 평점 정렬과 검색 점수 정렬 사이에서 cursor를 재사용하지 않는다")
    void rejectsCursorBetweenEffectiveSortModes(boolean keywordFirst) throws Exception {
        MockHttpServletRequestBuilder firstRequest =
                memberRequest().param("genre", "ACTION").param("size", "1");
        MockHttpServletRequestBuilder nextRequest = memberRequest().param("genre", "ACTION");
        if (keywordFirst) firstRequest.param("q", "매트릭스");
        else nextRequest.param("q", "매트릭스");
        JsonNode first = read(firstRequest);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        invalid(nextRequest.param("cursor", first.path("nextCursor").asText()));
    }

    @Test
    @DisplayName("조건의 공백·중복·나열 순서와 검색어 대소문자 및 size 변경은 같은 cursor를 유지한다")
    void normalizesEquivalentConditionsAcrossPages() throws Exception {
        JsonNode first =
                read(
                        memberRequest()
                                .param("q", "  MaTrIx  ")
                                .param("genre", " SF ", "ACTION", "SF")
                                .param("country", " US ", "AU", "US")
                                .param("ott", "22", "21", "22")
                                .param("yearFrom", "1990")
                                .param("yearTo", "2020")
                                .param("size", "1"));
        assertThat(ids(first)).containsExactly(101L);
        JsonNode next =
                read(
                        memberRequest()
                                .params(searchConditions())
                                .param("size", "50")
                                .param("cursor", first.path("nextCursor").asText()));
        assertThat(ids(next)).containsExactly(102L);
        assertThat(next.path("hasNext").asBoolean()).isFalse();
        assertThat(next.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void normalizesBlankQueryEmptyFiltersAndDefaultSortInCursor() throws Exception {
        JsonNode first =
                read(
                        memberRequest()
                                .param("q", "  ")
                                .param("genre", "ACTION")
                                .param("country", "")
                                .param("ott", "")
                                .param("size", "1"));
        assertThat(ids(first)).containsExactly(101L);
        JsonNode next =
                read(
                        memberRequest()
                                .param("genre", "ACTION", "ACTION")
                                .param("sort", "RELEVANCE")
                                .param("size", "50")
                                .param("cursor", first.path("nextCursor").asText()));
        assertThat(ids(next)).containsExactly(102L, 104L);
        assertThat(next.path("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("unicodeCaseSearches")
    @DisplayName("비ASCII 검색어의 대소문자만 바꿔도 같은 cursor의 다음 페이지가 유지된다")
    void preservesUnicodeCaseEquivalentCursorPages(
            String target, String sort, String uppercase, String lowercase) throws Exception {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT lower(? COLLATE \"und-x-icu\")", String.class, uppercase))
                .isEqualTo(lowercase);
        for (long id = 899; id <= 903; id++) {
            String name = id >= 902 ? uppercase : lowercase;
            MovieSearchFixture.movie(
                    jdbc,
                    id,
                    target.equals("TITLE") ? name : "검색 경계 영화 " + id,
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

        List<Long> expected =
                ids(
                        read(
                                memberRequest()
                                        .param("q", uppercase)
                                        .param("sort", sort)
                                        .param("size", "50")));
        // 같은 제목·원제·인물명을 정확히 입력한 모든 대소문자 표기가 검색되어야 한다.
        assertThat(expected).containsExactly(903L, 902L, 901L, 900L, 899L);
        JsonNode first =
                read(memberRequest().param("q", uppercase).param("sort", sort).param("size", "1"));
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        List<Long> actual = new ArrayList<>(ids(first));
        JsonNode page = first;
        for (int pageNumber = 0; page.path("hasNext").asBoolean(); pageNumber++) {
            assertThat(pageNumber).isLessThan(5);
            String cursor = page.path("nextCursor").asText();
            JsonNode unchanged =
                    read(
                            memberRequest()
                                    .param("q", uppercase)
                                    .param("sort", sort)
                                    .param("size", "1")
                                    .param("cursor", cursor));
            page =
                    read(
                            memberRequest()
                                    .param("q", lowercase)
                                    .param("sort", sort)
                                    .param("size", "1")
                                    .param("cursor", cursor));
            assertThat(page).isEqualTo(unchanged);
            actual.addAll(ids(page));
        }
        assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(
                        ids(
                                read(
                                        memberRequest()
                                                .param("q", lowercase)
                                                .param("sort", sort)
                                                .param("size", "50"))))
                .containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> unicodeCaseSearches() {
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

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"sort,1", "sortValue,\"4.50\"", "movieId,\"101\""})
    @DisplayName("실제 Spring codec도 숫자 enum과 문자열 숫자 cursor를 400으로 거부한다")
    void rejectsCursorScalarCoercionAtHttpBoundary(String field, String json) throws Exception {
        JsonNode first =
                read(
                        memberRequest()
                                .param("genre", "ACTION")
                                .param("sort", "RATING")
                                .param("size", "1"));
        ObjectNode payload =
                (ObjectNode)
                        objectMapper.readTree(
                                Base64.getUrlDecoder().decode(first.path("nextCursor").asText()));
        payload.set(field, objectMapper.readTree(json));
        String cursor =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(objectMapper.writeValueAsBytes(payload));
        invalid(
                memberRequest()
                        .param("genre", "ACTION")
                        .param("sort", "RATING")
                        .param("cursor", cursor));
    }

    @Test
    @DisplayName("서로 다른 RELEVANCE 점수의 순서와 size=1 경계를 검증한다")
    void paginatesUnequalRelevanceScores() throws Exception {
        MovieSearchFixture.movie(jdbc, 601, "RelevanceProbe", null, null, 0);
        MovieSearchFixture.movie(jdbc, 602, "RelevanceProbe very long extension", null, null, 5);
        MovieSearchFixture.movie(jdbc, 603, "RelevanceProbe extended", null, null, 4);
        assertThat(ids(read(memberRequest().param("q", "RelevanceProbe").param("size", "50"))))
                .containsExactly(601L, 603L, 602L);
        assertThat(collectPages(1, "RELEVANCE", "RelevanceProbe"))
                .containsExactly(601L, 603L, 602L);
    }

    @Test
    @DisplayName("개봉일 NULL 구간이 여러 페이지에 걸쳐도 ID 경계로 끝까지 조회한다")
    void paginatesMultipleNullReleaseDates() throws Exception {
        MovieSearchFixture.movie(jdbc, 701, "NullDateProbe", null, "2020-01-01", 0);
        for (int i = 702; i <= 705; i++) {
            MovieSearchFixture.movie(jdbc, i, "NullDateProbe", null, null, 0);
        }
        assertThat(collectPages(1, "RELEASE_DATE", "NullDateProbe"))
                .containsExactly(701L, 705L, 704L, 703L, 702L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "original_title"})
    @DisplayName("LIKE·trigram으로 매칭되지 않는 제목과 원제도 full-text 분기로 검색한다")
    void searchesFullTextOnlyCandidates(String column) throws Exception {
        MovieSearchFixture.movie(jdbc, 801, "무관한 제목", null, null, 0);
        // column은 위 고정 테스트 상수다. 긴 중간 텍스트로 부분 일치와 trigram을 배제한다.
        jdbc.update(
                "UPDATE movies SET "
                        + column
                        + " = 'FulltextNeedle ' || "
                        + "(SELECT string_agg(md5(n::text), ' ') FROM generate_series(1,40)n) "
                        + "|| ' DistantToken' WHERE id = 801");
        String query = "FulltextNeedle DistantToken";
        var predicates =
                jdbc.queryForMap(
                        "SELECT lower("
                                + column
                                + ") LIKE lower(?) AS substring, "
                                + "lower("
                                + column
                                + ") % lower(?) AS trigram, to_tsvector('simple', coalesce("
                                + column
                                + ", '')) @@ plainto_tsquery('simple', ?) AS fulltext FROM movies WHERE id=801",
                        "%" + query + "%",
                        query,
                        query);
        assertThat(predicates)
                .containsEntry("substring", false)
                .containsEntry("trigram", false)
                .containsEntry("fulltext", true);
        assertThat(ids(read(memberRequest().param("q", query)))).containsExactly(801L);
    }

    @ParameterizedTest(name = "size={0}")
    @ValueSource(ints = {1, 50})
    @DisplayName("회원 검색의 조회 SQL 수는 페이지 크기와 무관하게 최대 3회다")
    void boundsQueryCount(int size) throws Exception {
        MovieSearchFixture.pageMovies(jdbc, 60);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode page =
                read(memberRequest().param("q", "은하의 기록").param("size", Integer.toString(size)));
        assertThat(ids(page)).hasSize(size);
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isBetween(1L, 3L);
    }

    @ParameterizedTest(name = "size={0}")
    @ValueSource(ints = {1, 50})
    @DisplayName("비회원은 페이지 크기와 무관하게 검색 SQL 실행 전에 거부한다")
    void rejectsAnonymousSearchWithoutQuerying(int size) throws Exception {
        MovieSearchFixture.pageMovies(jdbc, 60);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get(PATH).param("q", "은하의 기록").param("size", Integer.toString(size)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.items").doesNotExist());
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void emptyResultExecutesOnlyOneQuery() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(ids(read(memberRequest().param("q", "존재할수없는검색어zxqv")))).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @ParameterizedTest
    @CsvSource({"%,501", "_,502", "!,503"})
    void treatsLikeMetacharactersAsLiterals(String query, long movieId) throws Exception {
        MovieSearchFixture.movie(jdbc, 501, "100% honest", null, null, 0);
        MovieSearchFixture.movie(jdbc, 502, "A_B", null, null, 0);
        MovieSearchFixture.movie(jdbc, 503, "Wow! Film", null, null, 0);
        assertThat(ids(read(memberRequest().param("q", query)))).containsExactly(movieId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"genre", "country"})
    void treatsFilterSqlSyntaxAsData(String filter) throws Exception {
        assertThat(ids(read(memberRequest().param(filter, "' OR 1=1 --")))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movies", Long.class)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer definitely-invalid", "Bearer ", "Basic invalid"})
    @DisplayName("유효하지 않은 인증 헤더를 비회원 검색 성공으로 처리하지 않는다")
    void rejectsInvalidAuthorization(String authorization) throws Exception {
        MovieSearchFixture.watch(jdbc, MovieSearchFixture.MEMBER_ID, 103, "RATED");

        mockMvc.perform(get(PATH).header("Authorization", authorization).param("q", "기생충"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("Refresh Token을 Access Token 대신 보내면 검색을 거부한다")
    void rejectsRefreshTokenAsAccessToken() throws Exception {
        String refreshToken =
                tokenProvider.createRefreshToken(MovieSearchFixture.MEMBER_ID, "search-test");

        mockMvc.perform(
                        get(PATH)
                                .header("Authorization", "Bearer " + refreshToken)
                                .param("q", "기생충"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @DisplayName("만료된 Access Token으로 검색하면 TOKEN_EXPIRED를 반환한다")
    void rejectsExpiredAccessToken() throws Exception {
        TokenProvider expiredTokenProvider =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                Duration.ofMinutes(-1),
                                jwtProperties.refreshTokenTtl(),
                                jwtProperties.signupTokenTtl(),
                                jwtProperties.refreshCookie()));
        String expiredToken = expiredTokenProvider.createAccessToken(MovieSearchFixture.MEMBER_ID);

        mockMvc.perform(
                        get(PATH)
                                .header("Authorization", "Bearer " + expiredToken)
                                .param("q", "기생충"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    private static MultiValueMap<String, String> searchConditions() {
        MultiValueMap<String, String> conditions = new LinkedMultiValueMap<>();
        conditions.set("q", "matrix");
        conditions.addAll("genre", List.of("ACTION", "SF"));
        conditions.addAll("country", List.of("AU", "US"));
        conditions.addAll("ott", List.of("21", "22"));
        conditions.set("yearFrom", "1990");
        conditions.set("yearTo", "2020");
        conditions.set("sort", "RELEVANCE");
        return conditions;
    }

    private List<Long> collectPages(String sort, String query, String... genres) throws Exception {
        return collectPages(2, sort, query, genres);
    }

    private List<Long> collectPages(int size, String sort, String query, String... genres)
            throws Exception {
        List<Long> all = new ArrayList<>();
        List<String> cursors = new ArrayList<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 10; pageNumber++) {
            MockHttpServletRequestBuilder request =
                    memberRequest().param("sort", sort).param("size", Integer.toString(size));
            if (query != null) request.param("q", query);
            if (genres.length > 0) request.param("genre", genres);
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = read(request);
            List<Long> current = ids(page);
            assertThat(current).isNotEmpty().hasSizeLessThanOrEqualTo(size).doesNotHaveDuplicates();
            // 첫 페이지의 all은 비어 있다. AssertJ의 검색 대상에는 비어 있지 않은 current를 준다.
            assertThat(all).doesNotContainAnyElementsOf(current);
            all.addAll(current);
            if (!page.path("hasNext").asBoolean()) {
                assertThat(page.path("nextCursor").isNull()).isTrue();
                return all;
            }
            assertThat(current).hasSize(size);
            cursor = page.path("nextCursor").asText();
            assertThat(cursor).isNotBlank().matches("[A-Za-z0-9_-]+=*");
            assertThat(cursors).doesNotContain(cursor);
            cursors.add(cursor);
        }
        throw new AssertionError("cursor가 반복되거나 마지막 페이지에 도달하지 못했습니다");
    }

    private MockHttpServletRequestBuilder memberRequest() {
        return get(PATH)
                .header(
                        "Authorization",
                        "Bearer " + tokenProvider.createAccessToken(MovieSearchFixture.MEMBER_ID));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        String json =
                perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private void invalid(MockHttpServletRequestBuilder request) throws Exception {
        perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("movieId").asLong()));
        return result;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }
}
