package com.feelm.backend.domain.trend.dto.response;

import com.feelm.backend.domain.trend.model.TrendingSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * API-064 급상승 영화 응답.
 *
 * <p>초기 고정 목록 A는 {@code source=DEFAULT}이고 {@code period}·{@code updatedAt}과 항목별 {@code
 * rankDelta}·{@code trendScore}가 모두 {@code null}이다. 게시된 집계 B는 {@code source=VIEW}이며 최신 결과가 만료돼 마지막
 * 유효 결과를 읽으면 {@code stale=true}로 표시하고 게시 당시의 {@code period}·{@code updatedAt}을 유지한다.
 */
@Schema(description = "급상승 영화 목록")
public record TrendingMoviesResponse(
        @Schema(example = "VIEW") TrendingSource source,
        @Schema(nullable = true) TrendingPeriodResponse period,
        @Schema(nullable = true, example = "2026-09-18T12:00:00Z") Instant updatedAt,
        @Schema(description = "마지막 유효 집계를 제공하는 중이면 true", example = "false") boolean stale,
        List<TrendingMovieItemResponse> items) {

    @Schema(description = "집계 구간. 초기 고정 목록에서는 null")
    public record TrendingPeriodResponse(
            @Schema(example = "2026-09-18T11:00:00Z") Instant from,
            @Schema(example = "2026-09-18T12:00:00Z") Instant to) {}

    @Schema(description = "급상승 항목")
    public record TrendingMovieItemResponse(
            @Schema(example = "1") int rank,
            @Schema(description = "직전 게시 대비 순위 상승 폭. 신규 진입이면 null", nullable = true, example = "4")
                    Integer rankDelta,
            TrendingMovieResponse movie,
            @Schema(example = "120") int ratingCount,
            @Schema(example = "4.30") BigDecimal averageScore,
            @Schema(description = "급상승 점수 (0, 1]. 평점이 아니다. 초기 고정 목록에서는 null", nullable = true)
                    BigDecimal trendScore) {}

    @Schema(description = "영화 표시 정보")
    public record TrendingMovieResponse(
            @Schema(example = "550") long movieId,
            @Schema(example = "파이트 클럽") String title,
            @Schema(nullable = true) String posterUrl) {}
}
