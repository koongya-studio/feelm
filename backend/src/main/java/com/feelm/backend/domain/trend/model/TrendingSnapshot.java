package com.feelm.backend.domain.trend.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Spark가 게시한 VIEW 급상승 스냅샷(스트리밍 계약 5.2).
 *
 * <p>순위·증감·점수는 게시 시점의 값이며 API는 다시 계산하지 않는다(ADR-0017). {@code stale}은 최신 결과({@code
 * feelm:trending:current})가 아니라 마지막 유효 결과({@code feelm:trending:last-good})를 읽었음을 뜻하고, 그때도 {@code
 * period}·{@code updatedAt}은 게시 당시의 값을 유지한다.
 */
public record TrendingSnapshot(
        Instant periodFrom,
        Instant periodTo,
        Instant updatedAt,
        boolean stale,
        List<RankedMovie> items) {

    /** 스냅샷의 영화 한 편. {@code rankDelta}는 신규 진입이면 {@code null}이다. */
    public record RankedMovie(long movieId, int rank, Integer rankDelta, BigDecimal trendScore) {}
}
