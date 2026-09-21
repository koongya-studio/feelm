package com.feelm.backend.domain.movie;

import static com.feelm.backend.domain.movie.MovieDetailFixture.COUPANG_PLAY;
import static com.feelm.backend.domain.movie.MovieDetailFixture.DISNEY;
import static com.feelm.backend.domain.movie.MovieDetailFixture.EMPTY_MOVIE;
import static com.feelm.backend.domain.movie.MovieDetailFixture.EXTRA_OTT;
import static com.feelm.backend.domain.movie.MovieDetailFixture.MOVIE;
import static com.feelm.backend.domain.movie.MovieDetailFixture.NETFLIX;
import static com.feelm.backend.domain.movie.MovieDetailFixture.OTHER_MOVIE;
import static com.feelm.backend.domain.movie.MovieDetailFixture.OTHER_USER;
import static com.feelm.backend.domain.movie.MovieDetailFixture.OUTSIDER;
import static com.feelm.backend.domain.movie.MovieDetailFixture.RATING;
import static com.feelm.backend.domain.movie.MovieDetailFixture.REASON;
import static com.feelm.backend.domain.movie.MovieDetailFixture.TVING;
import static com.feelm.backend.domain.movie.MovieDetailFixture.USER;
import static com.feelm.backend.domain.movie.MovieDetailFixture.WATCHA;
import static com.feelm.backend.domain.movie.MovieDetailFixture.WAVVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisContainer;
import com.feelm.backend.support.SharedRedisState;
import com.redis.testcontainers.RedisContainer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 보안 필터·Flyway·PostgreSQL로 API-030을 검증한다. 대역 Controller/Repository를 등록하지 않는다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MovieDetailQueryRecorder.Configuration.class)
@Transactional
@DisplayName("API-030 영화 상세 — PostgreSQL 계약")
class MovieDetailContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}";

    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private MovieDetailQueryRecorder recorder;
    @Autowired private StringRedisTemplate redisTemplate;
    private MovieDetailFixture fixture;

    @BeforeEach
    void seed() {
        SharedRedisState.clear(redisTemplate);
        fixture = new MovieDetailFixture(jdbc);
        fixture.seed();
    }

    @Test
    @DisplayName("비회원도 서비스 movieId로 모든 기본 필드와 정렬된 컬렉션을 조회한다")
    void anonymousReceivesCompleteDetail() throws Exception {
        fixture.personalize();
        JsonNode response = ok(get(PATH, MOVIE));
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
        assertThat(response.path("title").asText()).isEqualTo("가상 영화");
        assertThat(response.path("originalTitle").asText()).isEqualTo("Fixture Movie");
        assertThat(response.path("synopsis").asText()).isEqualTo("가상 영화 줄거리");
        assertThat(response.path("releaseDate").asText()).isEqualTo("1999-10-15");
        assertThat(response.path("runtimeMinutes").asInt()).isEqualTo(139);
        assertThat(response.path("posterUrl").asText()).isEqualTo("https://example.com/poster.jpg");
        assertThat(response.path("backdropUrl").asText())
                .isEqualTo("https://example.com/backdrop.jpg");
        assertThat(response.path("flavorCode").asText()).isEqualTo("FLAVOR_A");
        assertThat(response.path("averageRating").decimalValue()).isEqualByComparingTo("4.35");
        assertThat(response.path("ratingCount").asInt()).isEqualTo(1280);
        assertJson(
                response.path("genres"),
                """
                [{"code":"DRAMA","name":"드라마"},{"code":"THRILLER","name":"스릴러"}]
                """);
        assertJson(
                response.path("countries"),
                """
                [{"code":"KR","name":"한국"},{"code":"US","name":"미국"}]
                """);
        assertJson(
                response.path("directors"),
                """
                [{"personId":14,"name":"감독 가","profileUrl":null},
                 {"personId":13,"name":"감독 나","profileUrl":null}]
                """);
        assertJson(
                response.path("cast"),
                """
                [{"personId":12,"name":"배우 가","character":"배역 가","profileUrl":null},
                 {"personId":11,"name":"배우 나","character":"배역 나","profileUrl":null}]
                """);
        assertThat(texts(response, "ottProviders", "code"))
                .containsExactly("WAVVE", "DISNEY_PLUS", "TVING", "NETFLIX");
        for (JsonNode ott : response.path("ottProviders")) {
            assertThat(ott.path("name").asText())
                    .isEqualTo(
                            Map.of(
                                            "DISNEY_PLUS",
                                            "디즈니플러스",
                                            "NETFLIX",
                                            "넷플릭스",
                                            "TVING",
                                            "티빙",
                                            "WAVVE",
                                            "웨이브")
                                    .get(ott.path("code").asText()));
            assertThat(ott.path("watchUrl").asText())
                    .isEqualTo("https://example.com/watch/" + ott.path("ottId").asLong());
        }
        assertAnonymous(response);
    }

    @Test
    @DisplayName("영화 선택값은 명시적인 null, 관계가 없으면 빈 배열, 집계는 0이다")
    void nullableMetadataKeepsContractFields() throws Exception {
        JsonNode response = ok(get(PATH, EMPTY_MOVIE));
        for (String field :
                List.of(
                        "originalTitle",
                        "synopsis",
                        "releaseDate",
                        "runtimeMinutes",
                        "posterUrl",
                        "backdropUrl",
                        "flavorCode",
                        "myRating",
                        "myWatchStatus",
                        "recommendation")) {
            assertNull(response, field);
        }
        for (String field : List.of("genres", "countries", "directors", "cast", "ottProviders")) {
            assertThat(response.path(field).isArray()).as(field).isTrue();
            assertThat(response.path(field).size()).as(field).isZero();
        }
        assertThat(response.path("averageRating").decimalValue()).isEqualByComparingTo("0");
        assertThat(response.path("ratingCount").asInt()).isZero();
    }

    @Test
    @DisplayName("배역과 OTT URL의 null도 필드를 생략하지 않는다")
    void nullableRelationFieldsRemainPresent() throws Exception {
        jdbc.update("UPDATE movie_people SET character_name = NULL WHERE movie_id = ?", MOVIE);
        jdbc.update("UPDATE movie_ott_providers SET watch_url = NULL WHERE movie_id = ?", MOVIE);
        JsonNode response = ok(member(MOVIE, USER));
        response.path("cast").forEach(actor -> assertNull(actor, "character"));
        response.path("cast").forEach(actor -> assertNull(actor, "profileUrl"));
        response.path("ottProviders").forEach(ott -> assertNull(ott, "watchUrl"));
    }

    @Test
    @DisplayName("TMDb 인물 원본 이미지는 상세 화면용 작은 크기로 반환한다")
    void profileImageUsesThumbnailSize() throws Exception {
        jdbc.update(
                "UPDATE people SET profile_url = ? WHERE id = ?",
                "https://image.tmdb.org/t/p/original/profile.jpg",
                12L);
        jdbc.update(
                "UPDATE people SET profile_url = ? WHERE id = ?",
                "https://profiles.example.test/person.jpg",
                11L);

        JsonNode response = ok(get(PATH, MOVIE));

        assertThat(response.path("cast").path(0).path("profileUrl").asText())
                .isEqualTo("https://image.tmdb.org/t/p/w185/profile.jpg");
        assertThat(response.path("cast").path(1).path("profileUrl").asText())
                .isEqualTo("https://profiles.example.test/person.jpg");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 10, 11, 25})
    @DisplayName("배우는 입력 순서·인물 ID가 아닌 TMDb 순서에서 최대 10명만 반환한다")
    void castIsLimitedAfterSorting(int count) throws Exception {
        fixture.clearCredits();
        List<Long> expected = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            long id = 1000 + count - i;
            fixture.person(id, "배우" + i, "ACTOR", "배역" + i, i * 3);
        }
        for (int i = 0; i < Math.min(10, count); i++) {
            expected.add(1000L + count - i);
        }
        assertThat(ids(ok(member(MOVIE, USER)), "cast", "personId"))
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("배우 순서 동률은 personId, NULL은 마지막이며 10번째 경계에도 적용한다")
    void castTiesAndNullsAreDeterministic() throws Exception {
        fixture.clearCredits();
        fixture.person(100, "순서 없음", "ACTOR", null, null);
        for (int i = 7; i >= 0; i--) {
            fixture.person(200 + i, "정렬 배우" + i, "ACTOR", null, i * 2);
        }
        fixture.person(502, "동률 다", "ACTOR", null, 40);
        fixture.person(500, "동률 가", "ACTOR", null, 40);
        fixture.person(501, "동률 나", "ACTOR", null, 40);
        List<Long> expected = List.of(200L, 201L, 202L, 203L, 204L, 205L, 206L, 207L, 500L, 501L);
        assertThat(ids(ok(member(MOVIE, USER)), "cast", "personId")).isEqualTo(expected);
        assertThat(ids(ok(member(MOVIE, USER)), "cast", "personId")).isEqualTo(expected);
    }

    @Test
    @DisplayName("배우가 10명 미만이면 순서 없는 배우도 personId 순으로 채운다")
    void castIncludesNullOrderWithinLimit() throws Exception {
        fixture.clearCredits();
        fixture.person(101, "없음 나", "ACTOR", null, null);
        fixture.person(100, "없음 가", "ACTOR", null, null);
        fixture.person(500, "순서 있음", "ACTOR", null, 90);
        assertThat(ids(ok(member(MOVIE, USER)), "cast", "personId"))
                .containsExactly(500L, 100L, 101L);
    }

    @Test
    @DisplayName("감독은 10명을 넘어도 전원 반환하며 배우를 겸한 인물도 양쪽에 표시한다")
    void allDirectorsAreReturnedSeparatelyFromCast() throws Exception {
        fixture.clearCredits();
        for (int i = 11; i >= 0; i--) {
            fixture.person(200 + i, "감독" + i, "DIRECTOR", null, i / 2);
        }
        fixture.person(100, "순서 없는 감독", "DIRECTOR", null, null);
        fixture.credit(200, "ACTOR", "감독 겸 배우", 0);
        JsonNode response = ok(member(MOVIE, USER));
        assertThat(ids(response, "directors", "personId"))
                .containsExactly(
                        200L, 201L, 202L, 203L, 204L, 205L, 206L, 207L, 208L, 209L, 210L, 211L,
                        100L);
        assertThat(ids(response, "cast", "personId")).containsExactly(200L);
    }

    @Test
    @DisplayName("회원은 자기 평가·활성 감상·구독만 받고 구독 그룹을 먼저 정렬한다")
    void memberGetsOnlyOwnPersonalization() throws Exception {
        fixture.personalize();
        jdbc.update("UPDATE ratings SET is_spoiler = true WHERE id = ?", RATING);
        fixture.ott(50, "UNRELATED", "다른 제공처");
        fixture.subscribe(USER, 50);
        fixture.provider(OTHER_MOVIE, 50);
        JsonNode response = ok(member(MOVIE, USER));
        assertThat(response.path("myRating").path("ratingId").asLong()).isEqualTo(RATING);
        assertThat(response.path("myRating").path("score").decimalValue())
                .isEqualByComparingTo("0.5");
        assertThat(response.path("myRating").path("comment").asText()).isEqualTo("내 비공개 한줄평");
        assertThat(response.path("myRating").path("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(response.path("myRating").path("spoiler").booleanValue()).isTrue();
        assertThat(response.path("myWatchStatus").asText()).isEqualTo("RATED");
        assertThat(ids(response, "ottProviders", "ottId"))
                .containsExactly(WAVVE, TVING, DISNEY, NETFLIX);
        assertThat(texts(response, "ottProviders", "code"))
                .containsExactly("WAVVE", "TVING", "DISNEY_PLUS", "NETFLIX");
        assertThat(response.path("ottProviders").get(0).path("subscribed").booleanValue()).isTrue();
        assertThat(response.path("ottProviders").get(1).path("subscribed").booleanValue()).isTrue();
        assertThat(response.path("ottProviders").get(2).path("subscribed").booleanValue())
                .isFalse();
        assertThat(response.path("ottProviders").get(3).path("subscribed").booleanValue())
                .isFalse();
        assertNull(response, "recommendation");
        assertAnonymous(ok(member(MOVIE, OUTSIDER)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "1.0", "1.5", "2.0", "2.5", "3.0", "3.5", "4.0", "4.5", "5.0"})
    @DisplayName("내 평점의 모든 0.5점 단위를 반올림 없이 반환한다")
    void preservesEveryHalfStepRating(String score) throws Exception {
        fixture.rating(RATING, USER, MOVIE, score, null, null);
        JsonNode response = ok(member(MOVIE, USER));
        assertThat(response.path("myRating").path("score").isNumber()).isTrue();
        assertThat(response.path("myRating").path("score").decimalValue())
                .isEqualByComparingTo(new BigDecimal(score));
        assertNull(response.path("myRating"), "comment");
        assertNull(response.path("myRating"), "visibility");
        assertThat(response.path("myRating").path("spoiler").booleanValue()).isFalse();
        assertNull(response, "myWatchStatus");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LINK_CLICKED", "WATCHED_UNRATED", "RATED"})
    @DisplayName("여러 만료 이력 중 현재 활성 감상 기록 하나만 선택한다")
    void usesActiveWatchStatus(String watchStatus) throws Exception {
        fixture.watch(USER, MOVIE, "EXPIRED");
        fixture.watch(USER, MOVIE, watchStatus);
        fixture.watch(USER, MOVIE, "EXPIRED");
        fixture.watch(OTHER_USER, MOVIE, "RATED");
        fixture.watch(USER, OTHER_MOVIE, "LINK_CLICKED");
        JsonNode response = ok(member(MOVIE, USER));
        assertThat(response.path("myWatchStatus").asText()).isEqualTo(watchStatus);
        assertThat(response.path("genres").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("만료 기록만 있으면 현재 감상 상태는 null이다")
    void expiredOnlyHasNoCurrentWatchStatus() throws Exception {
        fixture.watch(USER, MOVIE, "EXPIRED");
        fixture.watch(USER, MOVIE, "EXPIRED");
        assertNull(ok(member(MOVIE, USER)), "myWatchStatus");
    }

    @Test
    @DisplayName("차단 관계가 있어도 평균 평점·평가 수는 영화의 전체 집계값을 유지한다")
    void ratingAggregateIgnoresBlockRelationships() throws Exception {
        fixture.personalize();
        jdbc.update(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                USER,
                OTHER_USER,
                OUTSIDER,
                USER);
        JsonNode response = ok(member(MOVIE, USER));
        assertThat(response.path("averageRating").decimalValue()).isEqualByComparingTo("4.35");
        assertThat(response.path("ratingCount").asInt()).isEqualTo(1280);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TASTE", "DISCOVERY"})
    @DisplayName("본인 개인 추천은 ID·유형과 사용자용 이유만 반환한다")
    void ownRecommendationPreservesReasonObject(String type) throws Exception {
        long item = fixture.personalRecommendation(USER, MOVIE, type);
        assertRecommendation(ok(recommended(MOVIE, USER, item)), item, type);
    }

    @Test
    @DisplayName("추천 ID가 없으면 본인 추천이 저장되어 있어도 recommendation은 null이다")
    void noRecommendationParameterDoesNotPickAnItem() throws Exception {
        fixture.personalRecommendation(USER, MOVIE, "TASTE");
        assertNull(ok(member(MOVIE, USER)), "recommendation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER_USER", "OTHER_MOVIE", "MISSING"})
    @DisplayName("타인·다른 영화·존재하지 않는 추천 ID는 null과 200을 반환한다")
    void inaccessiblePersonalRecommendationIsOmitted(String scenario) throws Exception {
        long item =
                switch (scenario) {
                    case "OTHER_USER" -> fixture.personalRecommendation(OTHER_USER, MOVIE, "TASTE");
                    case "OTHER_MOVIE" ->
                            fixture.personalRecommendation(USER, OTHER_MOVIE, "DISCOVERY");
                    default -> Long.MAX_VALUE;
                };
        JsonNode response = ok(recommended(MOVIE, USER, item));
        assertThat(response.path("movieId").asLong()).isEqualTo(MOVIE);
        assertNull(response, "recommendation");
    }

    @Test
    @DisplayName("비회원에게 추천 ID를 전달해도 개인화 정보는 모두 숨긴다")
    void anonymousCannotReadRecommendationReason() throws Exception {
        fixture.personalize();
        long item = fixture.personalRecommendation(USER, MOVIE, "TASTE");
        assertAnonymous(ok(get(PATH, MOVIE).param("recommendationItemId", Long.toString(item))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MEMBER"})
    @DisplayName("파티 추천은 현재 OWNER와 MEMBER 모두 조회할 수 있다")
    void currentPartyMembersMayRead(String role) throws Exception {
        long party = fixture.party(OTHER_USER);
        fixture.member(party, USER, "MEMBER");
        long item = fixture.partyRecommendation(party, MOVIE);
        long viewer = role.equals("OWNER") ? OTHER_USER : USER;
        assertRecommendation(ok(recommended(MOVIE, viewer, item)), item, "PARTY");
    }

    @Test
    @DisplayName("추천 생성 이후 가입한 현재 멤버도 파티 추천을 조회한다")
    void memberWhoJoinedAfterGenerationMayRead() throws Exception {
        long party = fixture.party(OTHER_USER);
        long item = fixture.partyRecommendation(party, MOVIE);
        assertNull(ok(recommended(MOVIE, USER, item)), "recommendation");
        fixture.member(party, USER, "MEMBER");
        assertRecommendation(ok(recommended(MOVIE, USER, item)), item, "PARTY");
    }

    @Test
    @DisplayName("현재 멤버가 탈퇴하면 같은 추천 ID의 이유를 더 이상 반환하지 않는다")
    void departedMemberLosesRecommendationAccess() throws Exception {
        long party = fixture.party(OTHER_USER);
        fixture.member(party, USER, "MEMBER");
        long item = fixture.partyRecommendation(party, MOVIE);
        assertRecommendation(ok(recommended(MOVIE, USER, item)), item, "PARTY");
        jdbc.update("DELETE FROM party_members WHERE party_id = ? AND user_id = ?", party, USER);
        assertNull(ok(recommended(MOVIE, USER, item)), "recommendation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STRANGER", "OTHER_PARTY", "PENDING_INVITATION", "ACCEPTED_INVITATION"})
    @DisplayName("비멤버·다른 파티 멤버·초대 이력만 있는 회원은 파티 추천을 볼 수 없다")
    void nonMembersCannotReadPartyRecommendation(String scenario) throws Exception {
        long party = fixture.party(OTHER_USER);
        long item = fixture.partyRecommendation(party, MOVIE);
        if (scenario.equals("OTHER_PARTY")) {
            fixture.party(USER);
        } else if (scenario.endsWith("INVITATION")) {
            boolean accepted = scenario.startsWith("ACCEPTED");
            jdbc.update(
                    """
                    INSERT INTO party_invitations
                        (party_id, inviter_id, invitee_id, status, responded_at)
                    VALUES (?, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
                    """,
                    party,
                    OTHER_USER,
                    USER,
                    accepted ? "ACCEPTED" : "PENDING",
                    accepted);
        }
        assertNull(ok(recommended(MOVIE, USER, item)), "recommendation");
    }

    @Test
    @DisplayName("파티 멤버여도 다른 영화의 추천 이유는 반환하지 않는다")
    void partyRecommendationMustMatchMovie() throws Exception {
        long party = fixture.party(USER);
        long item = fixture.partyRecommendation(party, OTHER_MOVIE);
        assertNull(ok(recommended(MOVIE, USER, item)), "recommendation");
    }

    @Test
    @DisplayName("파티가 종료되어도 멤버 행이 유지된 현재 멤버에게는 추천 이유를 허용한다")
    void closedPartyUsesCurrentMembership() throws Exception {
        long party = fixture.party(USER);
        long item = fixture.partyRecommendation(party, MOVIE);
        jdbc.update(
                """
                UPDATE parties SET status = 'CLOSED', closed_reason = 'OWNER_CLOSED', closed_at = now()
                WHERE id = ?
                """,
                party);
        assertRecommendation(ok(recommended(MOVIE, USER, item)), item, "PARTY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer invalid-token", "Bearer ", "Basic invalid"})
    @DisplayName("선택 인증의 무효 토큰·빈 토큰·다른 스킴은 기존 정책대로 비회원으로 처리한다")
    void invalidAuthenticationNeverPersonalizes(String authorization) throws Exception {
        fixture.personalize();
        long item = fixture.personalRecommendation(USER, MOVIE, "TASTE");
        assertAnonymous(
                ok(
                        get(PATH, MOVIE)
                                .header("Authorization", authorization)
                                .param("recommendationItemId", Long.toString(item))));
    }

    @Test
    @DisplayName("Refresh Token은 Access Token으로 사용할 수 없고 비회원 정보만 반환한다")
    void refreshTokenCannotPersonalize() throws Exception {
        fixture.personalize();
        String token = tokenProvider.createRefreshToken(USER, "test-refresh-token-id");
        assertAnonymous(ok(get(PATH, MOVIE).header("Authorization", "Bearer " + token)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("비회원·회원 모두 미존재 영화는 공통 404 JSON을 받는다")
    void missingMovieReturnsCommon404(boolean authenticated) throws Exception {
        MockHttpServletRequestBuilder request =
                authenticated ? member(Long.MAX_VALUE, USER) : get(PATH, Long.MAX_VALUE);
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    @DisplayName("영화 ID는 양의 64비트 정수만 허용한다")
    void invalidMovieIdReturns400(String movieId) throws Exception {
        invalid(get(PATH, movieId).header("Authorization", bearer(USER)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    @DisplayName("추천 ID가 제공되면 양의 64비트 정수만 허용한다")
    void invalidRecommendationIdReturns400(String itemId) throws Exception {
        invalid(member(MOVIE, USER).param("recommendationItemId", itemId));
        invalid(get(PATH, MOVIE).param("recommendationItemId", itemId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void suppliedEmptyRecommendationIdIsInvalid(String itemId) throws Exception {
        invalid(member(MOVIE, USER).param("recommendationItemId", itemId));
        invalid(get(PATH, MOVIE).param("recommendationItemId", itemId));
    }

    @Test
    void fiveSupportedOttsFollowSubscribedThenIdOrder() throws Exception {
        fixture.ott(25, "WATCHA", "왓챠");
        fixture.provider(MOVIE, 25);
        assertThat(ids(ok(get(PATH, MOVIE)), "ottProviders", "ottId"))
                .containsExactly(WAVVE, DISNEY, 25L, TVING, NETFLIX);
        fixture.subscribe(USER, NETFLIX);
        fixture.subscribe(USER, DISNEY);
        assertThat(texts(ok(member(MOVIE, USER)), "ottProviders", "code"))
                .containsExactly("DISNEY_PLUS", "NETFLIX", "WAVVE", "WATCHA", "TVING");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("승인 5종만 노출하며 미승인 OTT는 구독해도 제외하고 기존 정렬을 유지한다")
    void onlySupportedOttsAreReturnedInContractOrder(boolean authenticated) throws Exception {
        fixture.ott(WATCHA, "WATCHA", "왓챠");
        fixture.provider(MOVIE, WATCHA);
        fixture.unsupportedProviders(MOVIE);
        fixture.subscribe(USER, NETFLIX);
        fixture.subscribe(USER, DISNEY);
        fixture.subscribe(USER, COUPANG_PLAY);
        fixture.subscribe(USER, EXTRA_OTT);

        JsonNode response = ok(authenticated ? member(MOVIE, USER) : get(PATH, MOVIE));

        assertThat(ids(response, "ottProviders", "ottId"))
                .containsExactlyElementsOf(
                        authenticated
                                ? List.of(DISNEY, NETFLIX, WAVVE, WATCHA, TVING)
                                : List.of(WAVVE, DISNEY, WATCHA, TVING, NETFLIX));
        assertThat(texts(response, "ottProviders", "code"))
                .containsExactlyInAnyOrder("NETFLIX", "DISNEY_PLUS", "WATCHA", "WAVVE", "TVING");
        for (JsonNode ott : response.path("ottProviders")) {
            assertThat(ott.path("subscribed").booleanValue())
                    .isEqualTo(
                            authenticated
                                    && List.of(DISNEY, NETFLIX)
                                            .contains(ott.path("ottId").asLong()));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM movie_ott_providers WHERE movie_id = ?",
                                Integer.class,
                                MOVIE))
                .as("조회는 미승인 관계를 삭제하지 않는다")
                .isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("미승인 OTT만 연결된 영화는 구독 여부와 관계없이 빈 배열을 반환한다")
    void unsupportedOnlyMovieReturnsEmptyOtts(boolean authenticated) throws Exception {
        fixture.unsupportedProviders(EMPTY_MOVIE);
        fixture.subscribe(USER, COUPANG_PLAY);
        fixture.subscribe(USER, EXTRA_OTT);

        JsonNode response = ok(authenticated ? member(EMPTY_MOVIE, USER) : get(PATH, EMPTY_MOVIE));

        assertJson(response.path("ottProviders"), "[]");
    }

    @Test
    void unknownCountryUsesCommonErrorWithoutInternalDetails() throws Exception {
        jdbc.update("INSERT INTO movie_countries VALUES (?, 'ZZ')", MOVIE);
        String body =
                mockMvc.perform(get(PATH, MOVIE))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain("ZZ", "CountryNames", "IllegalStateException", "매핑", "SELECT");
    }

    @Test
    @DisplayName("영화 상세 GET 공개 허용이 인접 회원용 평가 POST를 공개하지 않는다")
    void publicDetailDoesNotOpenRatingWrites() throws Exception {
        mockMvc.perform(
                        post("/api/v1/movies/{movieId}/ratings", MOVIE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"score\":4.5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @CsvSource({
        "KR,한국",
        "US,미국",
        "JP,일본",
        "CN,중국",
        "GB,영국",
        "FR,프랑스",
        "DE,독일",
        "AN,네덜란드령 안틸레스",
        "CS,세르비아 몬테네그로",
        "SU,소련",
        "XC,체코슬로바키아",
        "XG,동독",
        "XI,북아일랜드",
        "XK,코소보",
        "YU,유고슬라비아",
        "ZR,자이르"
    })
    @DisplayName("국가 코드를 한국어 표시명과 구분해 반환한다")
    void countryNamesUseKoreanContract(String code, String name) throws Exception {
        jdbc.update("DELETE FROM movie_countries WHERE movie_id = ?", MOVIE);
        jdbc.update("INSERT INTO movie_countries VALUES (?, ?)", MOVIE, code);
        JsonNode response = ok(member(MOVIE, USER));
        assertThat(texts(response, "countries", "code")).containsExactly(code);
        assertThat(texts(response, "countries", "name")).containsExactly(name);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("수집된 IT·SU 공동 제작 영화는 회원·비회원에게 200과 이탈리아·소련을 반환한다")
    void collectedSovietMovieKeepsDetailAvailable(boolean authenticated) throws Exception {
        // 실제 TMDb 1394(노스텔지아)의 production_countries: IT / Italy, SU / Soviet Union.
        jdbc.update(
                "INSERT INTO movie_countries VALUES (?, 'SU'), (?, 'IT')",
                EMPTY_MOVIE,
                EMPTY_MOVIE);

        JsonNode response = ok(authenticated ? member(EMPTY_MOVIE, USER) : get(PATH, EMPTY_MOVIE));

        assertThat(response.path("movieId").asLong()).isEqualTo(EMPTY_MOVIE);
        assertJson(
                response.path("countries"),
                """
                [{"code":"IT","name":"이탈리아"},{"code":"SU","name":"소련"}]
                """);
    }

    @Test
    @DisplayName("모든 ISO 국가 코드의 표시명이 존재하며 시스템 Locale에 따라 달라지지 않는다")
    void allIsoCountriesHaveStableKoreanNames() throws Exception {
        jdbc.update("DELETE FROM movie_countries WHERE movie_id = ?", MOVIE);
        List<String> codes = Arrays.stream(Locale.getISOCountries()).sorted().toList();
        for (String code : codes) {
            jdbc.update("INSERT INTO movie_countries VALUES (?, ?)", MOVIE, code);
        }
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            JsonNode response = ok(member(MOVIE, USER));
            assertThat(texts(response, "countries", "code")).containsExactlyElementsOf(codes);
            for (JsonNode country : response.path("countries")) {
                assertThat(country.path("name").asText())
                        .as(country.path("code").asText())
                        .isNotBlank()
                        .isNotEqualTo(country.path("code").asText())
                        .containsPattern("[가-힣]");
            }
            Locale.setDefault(Locale.JAPAN);
            assertThat(ok(member(MOVIE, USER)).path("countries"))
                    .isEqualTo(response.path("countries"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("비회원 조회는 평가·감상·구독·추천의 사용자별 테이블을 조회하지 않는다")
    void anonymousSkipsPersonalizedQueries() throws Exception {
        fixture.personalize();
        long item = fixture.personalRecommendation(USER, MOVIE, "TASTE");
        recorder.start();
        List<String> queries;
        try {
            assertAnonymous(
                    ok(get(PATH, MOVIE).param("recommendationItemId", Long.toString(item))));
        } finally {
            queries = recorder.stop();
        }
        assertThat(queries)
                .isNotEmpty()
                .noneSatisfy(
                        sql ->
                                assertThat(sql.toLowerCase(Locale.ROOT))
                                        .containsPattern(
                                                "\\b(ratings|watch_records|user_otts|recommendation_items|recommendation_sets|party_members)\\b"));
    }

    @Test
    @DisplayName("연관 데이터가 늘어도 실제 JDBC 조회 수가 일정하고 상세 조회는 쓰기를 하지 않는다")
    void queryCountDoesNotGrowWithAssociations() throws Exception {
        fixture.personalize();
        long item = fixture.personalRecommendation(USER, MOVIE, "TASTE");
        List<String> small = measure(recommended(MOVIE, USER, item));
        for (int i = 0; i < 20; i++) {
            fixture.person(1000 + i, "추가 감독" + i, "DIRECTOR", null, i);
            fixture.person(2000 + i, "추가 배우" + i, "ACTOR", null, i);
            fixture.ott(1000 + i, "EXTRA_" + i, "추가 OTT" + i);
            fixture.provider(MOVIE, 1000 + i);
            fixture.watch(USER, MOVIE, "EXPIRED");
            jdbc.update(
                    "INSERT INTO genres (id, code, name, tmdb_genre_id) VALUES (?, ?, ?, ?)",
                    1000 + i,
                    "EXTRA_" + i,
                    "추가 장르" + i,
                    1000 + i);
            jdbc.update("INSERT INTO movie_genres VALUES (?, ?)", MOVIE, 1000 + i);
        }
        List<String> large = measure(recommended(MOVIE, USER, item));
        assertThat(small).as("실제 SQL을 계측해야 0=0으로 잘못 통과하지 않는다").isNotEmpty();
        assertThat(large).hasSameSizeAs(small);
        assertThat(large)
                .noneSatisfy(
                        sql ->
                                assertThat(sql.toLowerCase(Locale.ROOT))
                                        .containsPattern("\\b(insert|update|delete|merge)\\b"));
        JsonNode response = ok(recommended(MOVIE, USER, item));
        assertThat(response.path("directors").size()).isEqualTo(22);
        assertThat(response.path("cast").size()).isEqualTo(10);
        assertThat(response.path("genres").size()).isEqualTo(22);
        assertThat(texts(response, "ottProviders", "code"))
                .containsExactly("WAVVE", "TVING", "DISNEY_PLUS", "NETFLIX");
        assertThat(ids(response, "directors", "personId")).doesNotHaveDuplicates();
        assertThat(ids(response, "ottProviders", "ottId")).doesNotHaveDuplicates();
        assertRecommendation(response, item, "TASTE");
    }

    @Test
    @DisplayName("앞선 계약 테스트가 기준정보를 갈아치우고 가도 상세 fixture는 스스로 선다")
    void seedDoesNotDependOnLeftoverReferenceData() throws Exception {
        leaveForeignReferenceData();

        fixture.seed();

        JsonNode response = ok(get(PATH, MOVIE));
        assertThat(response.path("flavorCode").asText()).isEqualTo("FLAVOR_A");
        assertThat(texts(response, "genres", "code")).containsExactly("DRAMA", "THRILLER");
        assertThat(texts(response, "ottProviders", "code"))
                .containsExactly("WAVVE", "DISNEY_PLUS", "TVING", "NETFLIX");
    }

    /**
     * 공유 PostgreSQL을 쓰는 다른 계약 테스트가 커밋한 채 끝내는 상태를 그대로 만든다. Flyway seed의 {@code FLAVOR_A}가 사라지고, 이
     * fixture가 쓰는 장르 식별자와 OTT 코드를 남의 행이 선점하며, 그 행들은 참조까지 달고 있어 지우려 들면 FK가 막는다. 이 테스트도
     * {@code @Transactional}이라 모두 롤백된다.
     */
    private void leaveForeignReferenceData() {
        long leftover = 1_580_901L;
        jdbc.execute("TRUNCATE TABLE popcorn_flavors, genres, otts, people, users CASCADE");
        for (int order = 1; order <= 8; order++) {
            jdbc.update(
                    """
                    INSERT INTO popcorn_flavors (id, code, display_name, color, sort_order,
                        cluster_id, representative_keywords)
                    VALUES (?, ?, ?, '#000000', ?, ?, '[]'::jsonb)
                    """,
                    order,
                    "FLAVOR_" + order,
                    "FLAVOR_" + order,
                    order,
                    order - 1);
        }
        jdbc.update(
                """
                INSERT INTO genres (id, code, name, tmdb_genre_id) VALUES
                    (1, 'DRAMA', '드라마', 18), (2, 'THRILLER', '스릴러', 53)
                """);
        jdbc.update(
                "INSERT INTO otts (id, code, name, tmdb_provider_id)"
                        + " VALUES (1, 'NETFLIX', '넷플릭스', 8)");
        jdbc.update(
                """
                INSERT INTO movies (id, tmdb_id, title, representative_flavor_id)
                VALUES (?, 900, '앞선 테스트가 남긴 영화', 1)
                """,
                leftover);
        jdbc.update("INSERT INTO movie_genres VALUES (?, 1)", leftover);
        jdbc.update("INSERT INTO movie_ott_providers (movie_id, ott_id) VALUES (?, 1)", leftover);
    }

    private List<String> measure(MockHttpServletRequestBuilder request) throws Exception {
        recorder.start();
        List<String> queries;
        try {
            ok(request);
        } finally {
            // 성공·실패 모두 계측 범위를 닫아 다음 테스트의 fixture SQL이 섞이지 않게 한다.
            queries = recorder.stop();
        }
        return queries;
    }

    private String bearer(long userId) {
        return "Bearer " + tokenProvider.createAccessToken(userId);
    }

    private MockHttpServletRequestBuilder member(long movieId, long userId) {
        return get(PATH, movieId).header("Authorization", bearer(userId));
    }

    private MockHttpServletRequestBuilder recommended(long movieId, long userId, long itemId) {
        return member(movieId, userId).param("recommendationItemId", Long.toString(itemId));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        String body =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private void invalid(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    private void assertRecommendation(JsonNode response, long item, String type) {
        JsonNode recommendation = response.path("recommendation");
        assertThat(recommendation.path("recommendationItemId").asLong()).isEqualTo(item);
        assertThat(recommendation.path("type").asText()).isEqualTo(type);
        assertThat(recommendation.path("reason").isObject()).isTrue();
        assertJson(recommendation.path("reason"), REASON);
        assertThat(recommendation.path("reason").has("reasons")).isFalse();
    }

    private void assertJson(JsonNode actual, String expected) {
        assertThat(actual).isEqualTo(objectMapper.readTree(expected));
    }

    private static void assertNull(JsonNode node, String field) {
        assertThat(node.has(field)).as("%s 필드가 존재한다", field).isTrue();
        assertThat(node.path(field).isNull()).as("%s는 null이다", field).isTrue();
    }

    private static void assertAnonymous(JsonNode response) {
        assertNull(response, "myRating");
        assertNull(response, "myWatchStatus");
        assertNull(response, "recommendation");
        for (JsonNode ott : response.path("ottProviders")) {
            assertThat(ott.path("subscribed").isBoolean()).isTrue();
            assertThat(ott.path("subscribed").booleanValue()).isFalse();
        }
    }

    private static List<Long> ids(JsonNode response, String field, String idField) {
        List<Long> ids = new ArrayList<>();
        response.path(field).forEach(item -> ids.add(item.path(idField).asLong()));
        return ids;
    }

    private static List<String> texts(JsonNode response, String field, String textField) {
        List<String> values = new ArrayList<>();
        response.path(field).forEach(item -> values.add(item.path(textField).asText()));
        return values;
    }
}
