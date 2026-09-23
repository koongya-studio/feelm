package com.feelm.backend.domain.trend;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.trend.model.TrendingSnapshot;
import com.feelm.backend.domain.trend.model.TrendingSnapshot.RankedMovie;
import com.feelm.backend.domain.trend.repository.TrendingSnapshotReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** API-064의 공개 경로·A→B 표시 정책·size 계약을 실제 보안 체인과 PostgreSQL에서 검증한다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@Transactional
class TrendingMovieApiContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/movies/trending";
    private static final Instant FROM = Instant.parse("2026-09-18T11:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-18T12:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TrendingSnapshotReader snapshotReader;

    @BeforeEach
    void fixtures() {
        reset(snapshotReader);
        jdbc.execute("TRUNCATE TABLE movies RESTART IDENTITY CASCADE");
        // 확정된 초기 고정 목록 A와 집계 결과 B에 쓸 영화를 함께 적재한다.
        for (long movieId : TrendingInitialMovies.movieIds()) {
            movie(movieId, "초기 " + movieId, 4.25, 120);
        }
        movie(550, "파이트 클럽", 4.30, 120);
        movie(551, "세븐", 4.10, 80);
    }

    @Test
    void returnsFixedInitialListWithoutAggregation() throws Exception {
        when(snapshotReader.read()).thenReturn(Optional.empty());

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("DEFAULT"))
                .andExpect(jsonPath("$.period").value((Object) null))
                .andExpect(jsonPath("$.updatedAt").value((Object) null))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(134626))
                .andExpect(jsonPath("$.items[0].movie.title").value("초기 134626"))
                .andExpect(
                        jsonPath("$.items[0].movie.posterUrl")
                                .value("https://example.test/p/134626.jpg"))
                .andExpect(jsonPath("$.items[0].ratingCount").value(120))
                .andExpect(jsonPath("$.items[0].averageScore").value(4.25))
                .andExpect(jsonPath("$.items[0].rankDelta").value((Object) null))
                .andExpect(jsonPath("$.items[0].trendScore").value((Object) null))
                .andExpect(jsonPath("$.items[1].movie.movieId").value(124813))
                .andExpect(jsonPath("$.items[2].movie.movieId").value(86995))
                .andExpect(jsonPath("$.items[3].movie.movieId").value(80719))
                .andExpect(jsonPath("$.items[4].movie.movieId").value(55230))
                .andExpect(jsonPath("$.items[5].movie.movieId").value(36815))
                .andExpect(jsonPath("$.items[6].movie.movieId").value(17057))
                .andExpect(jsonPath("$.items[7].movie.movieId").value(79065))
                .andExpect(jsonPath("$.items[8].movie.movieId").value(522))
                .andExpect(jsonPath("$.items[9].movie.movieId").value(68176))
                .andExpect(jsonPath("$.items[9].rank").value(10));
    }

    @Test
    void returnsPublishedAggregationWithSnapshotRanks() throws Exception {
        when(snapshotReader.read())
                .thenReturn(
                        Optional.of(
                                new TrendingSnapshot(
                                        FROM,
                                        TO,
                                        TO,
                                        false,
                                        List.of(
                                                ranked(551, 2, 0, "0.5"),
                                                ranked(550, 1, null, "1.0")))));

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("VIEW"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.period.from").value("2026-09-18T11:00:00Z"))
                .andExpect(jsonPath("$.period.to").value("2026-09-18T12:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-18T12:00:00Z"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(550))
                .andExpect(jsonPath("$.items[0].movie.title").value("파이트 클럽"))
                .andExpect(jsonPath("$.items[0].rankDelta").value((Object) null))
                .andExpect(jsonPath("$.items[0].trendScore").value(1.0))
                .andExpect(jsonPath("$.items[0].averageScore").value(4.30))
                .andExpect(jsonPath("$.items[1].rank").value(2))
                .andExpect(jsonPath("$.items[1].movie.movieId").value(551))
                .andExpect(jsonPath("$.items[1].rankDelta").value(0))
                .andExpect(jsonPath("$.items[1].trendScore").value(0.5));
    }

    @Test
    void marksRetainedAggregationAsStale() throws Exception {
        when(snapshotReader.read())
                .thenReturn(
                        Optional.of(
                                new TrendingSnapshot(
                                        FROM, TO, TO, true, List.of(ranked(550, 1, 4, "1.0")))));

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("VIEW"))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.updatedAt").value("2026-09-18T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].rankDelta").value(4));
    }

    @Test
    void appliesSizeToSelectedListWithoutRecomputingRank() throws Exception {
        when(snapshotReader.read())
                .thenReturn(
                        Optional.of(
                                new TrendingSnapshot(
                                        FROM,
                                        TO,
                                        TO,
                                        false,
                                        List.of(
                                                ranked(550, 1, null, "1.0"),
                                                ranked(551, 2, 0, "0.5")))));

        mvc.perform(get(PATH).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(550));

        mvc.perform(get(PATH).param("size", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].rank").value(2));
    }

    @Test
    void appliesDefaultSizeToInitialList() throws Exception {
        when(snapshotReader.read()).thenReturn(Optional.empty());

        mvc.perform(get(PATH).param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[2].movie.movieId").value(86995));
    }

    @Test
    void rejectsSizeOutOfContractRange() throws Exception {
        when(snapshotReader.read()).thenReturn(Optional.empty());

        for (String size : List.of("0", "31", "-1", "abc", "1.5")) {
            mvc.perform(get(PATH).param("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void bindsSizeEdgeCasesTheFrameworkWayWhenContractIsSilent() throws Exception {
        when(snapshotReader.read()).thenReturn(Optional.empty());

        // 계약은 빈 값과 같은 이름 반복을 규정하지 않는다. 오류로 만들려면 별도 바인딩 코드가 필요하므로
        // 프레임워크 기본 동작을 그대로 두고 그 동작을 여기서 고정한다(리뷰 B-F9).
        // `size=`는 미지정과 같게 기본 10, 반복된 값은 첫 값(5)을 쓴다.
        mvc.perform(get(PATH).param("size", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10));
        mvc.perform(get(PATH).param("size", "5", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void appliesSizeBeforeDroppingMoviesMissingFromCatalog() throws Exception {
        when(snapshotReader.read())
                .thenReturn(
                        Optional.of(
                                new TrendingSnapshot(
                                        FROM,
                                        TO,
                                        TO,
                                        false,
                                        List.of(
                                                ranked(999_999, 1, null, "1.0"),
                                                ranked(550, 2, 0, "0.8"),
                                                ranked(551, 3, 1, "0.5")))));

        // 계약: 게시 목록의 앞 size편(rank 1·2)을 먼저 고르고 그 뒤 적재되지 않은 rank 1을 뺀다.
        // 뒤 순위를 끌어올려 채우지 않으므로 rank 3은 응답에 없다.
        mvc.perform(get(PATH).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rank").value(2))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(550));
    }

    @Test
    void appliesSizeBeforeDroppingInitialMoviesMissingFromCatalog() throws Exception {
        when(snapshotReader.read()).thenReturn(Optional.empty());
        jdbc.update("DELETE FROM movies WHERE id = ?", 124813L);

        mvc.perform(get(PATH).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].movie.movieId").value(134626));
    }

    @Test
    void reportsUnavailableWhenPublishedResultCannotBeRead() throws Exception {
        when(snapshotReader.read()).thenThrow(new BusinessException(ErrorCode.TREND_UNAVAILABLE));

        mvc.perform(get(PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TREND_UNAVAILABLE"));
    }

    @Test
    void servesNonMemberRequests() throws Exception {
        when(snapshotReader.read())
                .thenReturn(
                        Optional.of(
                                new TrendingSnapshot(
                                        FROM,
                                        TO,
                                        TO,
                                        false,
                                        List.of(ranked(550, 1, null, "1.0")))));

        // 토큰을 보내지 않고, 유효하지 않은 토큰을 보내도 공개 API로 처리한다.
        mvc.perform(get(PATH)).andExpect(status().isOk());
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void dropsMoviesMissingFromCatalogWithoutRenumbering() throws Exception {
        when(snapshotReader.read())
                .thenReturn(
                        Optional.of(
                                new TrendingSnapshot(
                                        FROM,
                                        TO,
                                        TO,
                                        false,
                                        List.of(
                                                ranked(550, 1, null, "1.0"),
                                                ranked(999_999, 2, null, "0.7"),
                                                ranked(551, 3, 1, "0.5")))));

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[1].rank").value(3))
                .andExpect(jsonPath("$.items[1].movie.movieId").value(551));
    }

    @Test
    void doesNotTouchAggregationWhenSizeIsRejected() throws Exception {
        mvc.perform(get(PATH).param("size", "0")).andExpect(status().isBadRequest());

        verify(snapshotReader, never()).read();
    }

    private static RankedMovie ranked(
            long movieId, int rank, Integer rankDelta, String trendScore) {
        return new RankedMovie(movieId, rank, rankDelta, new BigDecimal(trendScore));
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
