package com.feelm.backend.domain.trend.service;

import com.feelm.backend.domain.trend.TrendingInitialMovies;
import com.feelm.backend.domain.trend.dto.response.TrendingMoviesResponse;
import com.feelm.backend.domain.trend.dto.response.TrendingMoviesResponse.TrendingMovieItemResponse;
import com.feelm.backend.domain.trend.dto.response.TrendingMoviesResponse.TrendingMovieResponse;
import com.feelm.backend.domain.trend.dto.response.TrendingMoviesResponse.TrendingPeriodResponse;
import com.feelm.backend.domain.trend.model.TrendingSnapshot;
import com.feelm.backend.domain.trend.model.TrendingSnapshot.RankedMovie;
import com.feelm.backend.domain.trend.model.TrendingSource;
import com.feelm.backend.domain.trend.repository.TrendingMovieRepository;
import com.feelm.backend.domain.trend.repository.TrendingMovieRepository.TrendingMovieMetadata;
import com.feelm.backend.domain.trend.repository.TrendingSnapshotReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * API-064 급상승 영화 조회.
 *
 * <p>게시된 집계 B가 있으면 그것을, 없으면 초기 고정 목록 A를 표시 정보와 함께 돌려준다. 순위·증감·점수는 게시 시점의 값이며 여기서 다시 계산하지
 * 않는다(ADR-0017).
 *
 * <p>{@code size}는 계약대로 <b>게시된 목록의 정렬 순서에서 앞 {@code size}편</b>에 적용한다. 그 뒤 적재되지 않은 영화를 빼므로 응답이
 * {@code size}보다 짧을 수 있고, 남은 항목의 순위는 다시 매기지 않는다.
 *
 * <p>Redis 조회를 DB 트랜잭션으로 감싸지 않는다. 선례인 API-068도 후보를 트랜잭션 밖에서 읽는다.
 */
@Service
@RequiredArgsConstructor
public class TrendingMovieService {

    private final TrendingSnapshotReader snapshotReader;
    private final TrendingMovieRepository trendingMovieRepository;

    public TrendingMoviesResponse getTrending(int size) {
        Optional<TrendingSnapshot> published = snapshotReader.read();
        if (published.isEmpty()) {
            return initialList(size);
        }
        TrendingSnapshot snapshot = published.get();
        List<RankedMovie> ranked =
                snapshot.items().stream()
                        .sorted(Comparator.comparingInt(RankedMovie::rank))
                        .limit(size)
                        .toList();
        Map<Long, TrendingMovieMetadata> movies =
                trendingMovieRepository.findMovies(
                        ranked.stream().map(RankedMovie::movieId).toList());

        List<TrendingMovieItemResponse> items = new ArrayList<>();
        for (RankedMovie entry : ranked) {
            TrendingMovieMetadata movie = movies.get(entry.movieId());
            if (movie != null) {
                items.add(item(entry.rank(), entry.rankDelta(), entry.trendScore(), movie));
            }
        }
        return new TrendingMoviesResponse(
                TrendingSource.VIEW,
                new TrendingPeriodResponse(snapshot.periodFrom(), snapshot.periodTo()),
                snapshot.updatedAt(),
                snapshot.stale(),
                List.copyOf(items));
    }

    private TrendingMoviesResponse initialList(int size) {
        List<Long> movieIds = TrendingInitialMovies.movieIds().stream().limit(size).toList();
        Map<Long, TrendingMovieMetadata> movies = trendingMovieRepository.findMovies(movieIds);

        List<TrendingMovieItemResponse> items = new ArrayList<>();
        for (int index = 0; index < movieIds.size(); index++) {
            TrendingMovieMetadata movie = movies.get(movieIds.get(index));
            if (movie != null) {
                // 고정 목록의 위치가 곧 rank다. 빠진 영화가 있어도 남은 항목의 순위를 다시 매기지 않는다.
                items.add(item(index + 1, null, null, movie));
            }
        }
        return new TrendingMoviesResponse(
                TrendingSource.DEFAULT, null, null, false, List.copyOf(items));
    }

    private TrendingMovieItemResponse item(
            int rank, Integer rankDelta, BigDecimal trendScore, TrendingMovieMetadata movie) {
        return new TrendingMovieItemResponse(
                rank,
                rankDelta,
                new TrendingMovieResponse(movie.movieId(), movie.title(), movie.posterUrl()),
                movie.ratingCount(),
                movie.averageRating(),
                trendScore);
    }
}
