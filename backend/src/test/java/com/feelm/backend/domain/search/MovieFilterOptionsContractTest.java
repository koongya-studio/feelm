package com.feelm.backend.domain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisContainer;
import com.feelm.backend.support.SharedRedisState;
import com.redis.testcontainers.RedisContainer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-029의 실제 보안 체인·서비스·바인딩 SQL을 격리 PostgreSQL에서 검증한다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@Transactional
class MovieFilterOptionsContractTest extends SharedPostgresContractTest {
    private static final String PATH = "/api/v1/movies/filter-options";

    @ServiceConnection static final RedisContainer REDIS = SharedRedisContainer.instance();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void seed() {
        SharedRedisState.clear(redisTemplate);
        // 157과 동일한 스키마·영화 생성 경로를 재사용하되 210 데이터는 독립 트랜잭션이다.
        jdbc.execute("TRUNCATE TABLE movies, genres, otts RESTART IDENTITY CASCADE");
        MovieSearchFixture.movie(jdbc, 21001, "최신", null, "2031-12-31", 0);
        MovieSearchFixture.movie(jdbc, 21002, "연도 없음", null, null, 0);
        MovieSearchFixture.movie(jdbc, 21003, "최초", null, "1920-01-01", 0);
        jdbc.update(
                """
                INSERT INTO genres (id, code, name, tmdb_genre_id) VALUES
                (21001, 'THRILLER', '스릴러', 53), (21002, 'ACTION', '액션', 28),
                (21003, 'UNLINKED', '연결 없는 장르', 99999)
                """);
        jdbc.update(
                """
                INSERT INTO movie_genres (movie_id, genre_id) VALUES
                (21001, 21001), (21002, 21002), (21003, 21001), (21001, 21002)
                """);
        jdbc.update(
                """
                INSERT INTO movie_countries (movie_id, country_code) VALUES
                (21001, 'US'), (21002, 'KR'), (21003, 'FR'), (21003, 'KR'), (21003, 'US')
                """);
        jdbc.update(
                """
                INSERT INTO otts (id, code, name, logo_url, tmdb_provider_id) VALUES
                (21001, 'TVING', '티빙', 'https://example.test/t.png', 21001),
                (21002, 'WAVVE', '웨이브', NULL, 21002),
                (21003, 'WATCHA', '왓챠', NULL, 21003),
                (21004, 'DISNEY_PLUS', '디즈니+', NULL, 21004),
                (21005, 'NETFLIX', '넷플릭스', NULL, 21005),
                (21006, 'COUPANG_PLAY', '쿠팡플레이', NULL, 21006),
                (21007, 'APPLE_TV_PLUS', '애플 TV+', NULL, 21007),
                (21008, 'UNSUPPORTED', '기타 OTT', NULL, 21008)
                """);
        jdbc.update(
                """
                INSERT INTO movie_ott_providers (movie_id, ott_id) VALUES
                (21001, 21001), (21001, 21002), (21001, 21003), (21001, 21004),
                (21001, 21005), (21003, 21005), (21003, 21001),
                (21001, 21006), (21001, 21007), (21001, 21008)
                """);
    }

    @Test
    void returnsExactPublicContractWithDistinctConnectedOptionsAndStableOrder() throws Exception {
        String expected =
                """
                {"genres":[{"code":"ACTION","name":"액션"},{"code":"THRILLER","name":"스릴러"}],
                 "countries":[{"code":"FR","name":"프랑스"},{"code":"KR","name":"한국"},{"code":"US","name":"미국"}],
                 "yearRange":{"min":1920,"max":2031},
                 "otts":[{"ottId":21005,"code":"NETFLIX","name":"넷플릭스"},
                         {"ottId":21004,"code":"DISNEY_PLUS","name":"디즈니+"},
                         {"ottId":21003,"code":"WATCHA","name":"왓챠"},
                         {"ottId":21002,"code":"WAVVE","name":"웨이브"},
                         {"ottId":21001,"code":"TVING","name":"티빙"}]}
                """;
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "public, max-age=86400"))
                    .andExpect(content().json(expected, JsonCompareMode.STRICT));
        }
    }

    @Test
    void unlinkedSupportedOttIsExcluded() throws Exception {
        jdbc.update("DELETE FROM movie_ott_providers WHERE ott_id = ?", 21003);
        List<String> codes = new ArrayList<>();
        for (JsonNode ott : options().get("otts")) {
            codes.add(ott.get("code").asString());
        }
        assertThat(codes).containsExactly("NETFLIX", "DISNEY_PLUS", "WAVVE", "TVING");
    }

    @Test
    void noMoviesProducesEmptyArraysAndNullableYears() throws Exception {
        jdbc.update("DELETE FROM movie_ott_providers");
        jdbc.update("DELETE FROM movie_countries");
        jdbc.update("DELETE FROM movie_genres");
        jdbc.update("DELETE FROM movies");
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        """
                        {"genres":[],"countries":[],"yearRange":{"min":null,"max":null},"otts":[]}
                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void onlyUndatedMoviesProduceNullableYears() throws Exception {
        jdbc.update("UPDATE movies SET release_date = NULL");
        assertThat(options().get("yearRange"))
                .isEqualTo(objectMapper.readTree("{\"min\":null,\"max\":null}"));
    }

    @Test
    void oneDatedMovieHasEqualMinAndMax() throws Exception {
        jdbc.update("UPDATE movies SET release_date = NULL WHERE id <> ?", 21001);
        assertThat(options().get("yearRange"))
                .isEqualTo(objectMapper.readTree("{\"min\":2031,\"max\":2031}"));
    }

    @Test
    void singleMovieHasEqualMinAndMax() throws Exception {
        jdbc.update("DELETE FROM movie_ott_providers WHERE movie_id <> ?", 21001);
        jdbc.update("DELETE FROM movie_genres WHERE movie_id <> ?", 21001);
        jdbc.update("DELETE FROM movie_countries WHERE movie_id <> ?", 21001);
        jdbc.update("DELETE FROM movies WHERE id <> ?", 21001);
        assertThat(options().get("yearRange"))
                .isEqualTo(objectMapper.readTree("{\"min\":2031,\"max\":2031}"));
    }

    @Test
    void storedLabelsAreNotReplacedWithSamples() throws Exception {
        jdbc.update("UPDATE genres SET name = ? WHERE id = ?", "DB's 장르", 21001);
        jdbc.update("UPDATE otts SET name = ? WHERE id = ?", "DB's OTT", 21005);
        JsonNode response = options();
        assertThat(response.get("genres").get(1).get("name").asString()).isEqualTo("DB's 장르");
        assertThat(response.get("otts").get(0).get("name").asString()).isEqualTo("DB's OTT");
    }

    @Test
    void unknownStoredCountryReturnsUncacheableInternalError() throws Exception {
        // 스키마의 두 자리 형식 제약은 통과하지만 한국어 표시명 매핑에는 없는 코드다.
        jdbc.update(
                "INSERT INTO movie_countries (movie_id, country_code) VALUES (?, ?)", 21001, "ZZ");

        mockMvc.perform(get(PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", not(containsString("public"))))
                .andExpect(header().string("Cache-Control", not(containsString("max-age=86400"))))
                .andExpect(
                        content()
                                .json(
                                        """
                                        {"code":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
                                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void returnedOptionsEachProduceResultsInExistingSearch() throws Exception {
        JsonNode response = options();
        for (String group : List.of("genres", "countries", "otts")) {
            String parameter =
                    switch (group) {
                        case "genres" -> "genre";
                        case "countries" -> "country";
                        default -> "ott";
                    };
            for (JsonNode option : response.get(group)) {
                String value = option.get(group.equals("otts") ? "ottId" : "code").asString();
                String body =
                        mockMvc.perform(
                                        get("/api/v1/movies/search")
                                                .param(parameter, value)
                                                .header(
                                                        "Authorization",
                                                        "Bearer "
                                                                + tokenProvider.createAccessToken(
                                                                        210L)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();
                assertThat(objectMapper.readTree(body).get("items").isEmpty()).isFalse();
            }
        }
    }

    private JsonNode options() throws Exception {
        return objectMapper.readTree(
                mockMvc.perform(get(PATH))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
