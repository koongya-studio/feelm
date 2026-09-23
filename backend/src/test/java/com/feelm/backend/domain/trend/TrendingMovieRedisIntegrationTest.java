package com.feelm.backend.domain.trend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedPostgresContractTest;
import com.feelm.backend.support.SharedRedisState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-064를 실제 Redis reader까지 지나 검증한다.
 *
 * <p>계약 테스트는 스냅샷 판정을 대역으로 바꿔 두므로, 게시된 실제 키 값이 응답까지 이어지는지는 이 테스트가 고정한다(리뷰 B-F12).
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@Transactional
class TrendingMovieRedisIntegrationTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/movies/trending";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void seed() {
        SharedRedisState.clear(redisTemplate);
        jdbc.execute("TRUNCATE TABLE movies RESTART IDENTITY CASCADE");
        movie(550, "파이트 클럽", 4.30, 120);
        movie(TrendingInitialMovies.movieIds().getFirst(), "오디세이", 4.10, 30);
    }

    @Test
    void servesPublishedSnapshotThroughRealReader() throws Exception {
        redisTemplate
                .opsForValue()
                .set(
                        "feelm:trending:current",
                        """
                        {"schemaVersion":1,\
                        "period":{"from":"2026-09-18T11:00:00Z","to":"2026-09-18T12:00:00Z"},\
                        "updatedAt":"2026-09-18T12:00:00Z",\
                        "items":[{"movieId":550,"rank":1,"rankDelta":4,"trendScore":1.0}]}\
                        """);

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("VIEW"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.period.from").value("2026-09-18T11:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-18T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].rankDelta").value(4))
                .andExpect(jsonPath("$.items[0].trendScore").value(1.0))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(550))
                .andExpect(jsonPath("$.items[0].movie.title").value("파이트 클럽"))
                .andExpect(jsonPath("$.items[0].ratingCount").value(120))
                .andExpect(jsonPath("$.items[0].averageScore").value(4.30));
    }

    @Test
    void servesInitialListWhenNothingWasPublished() throws Exception {
        mvc.perform(get(PATH).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("DEFAULT"))
                .andExpect(jsonPath("$.period").value((Object) null))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(134626))
                .andExpect(jsonPath("$.items[0].trendScore").value((Object) null));
    }

    private void movie(long movieId, String title, double averageRating, int ratingCount) {
        jdbc.update(
                """
                INSERT INTO movies (id, tmdb_id, title, poster_url, average_rating, rating_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                movieId,
                900_000 + movieId,
                title,
                "https://example.test/p/" + movieId + ".jpg",
                averageRating,
                ratingCount);
    }
}
