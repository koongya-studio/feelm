package com.feelm.backend.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot;
import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot.ScoredMovie;
import com.feelm.backend.domain.recommendation.repository.OttCandidateReader;
import com.feelm.backend.domain.recommendation.repository.OttRecommendationRepository;
import com.feelm.backend.domain.recommendation.repository.OttRecommendationRepository.OttMovie;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OttRecommendationServiceTest {

    private final OttCandidateReader reader = mock(OttCandidateReader.class);
    private final OttRecommendationRepository repository = mock(OttRecommendationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T01:00:00Z"), ZoneOffset.UTC);
    private final OttRecommendationService service =
            new OttRecommendationService(reader, repository, clock);

    @Test
    void countsAllFiveHundredCandidatesWithoutScoreThreshold() {
        List<ScoredMovie> candidates =
                LongStream.rangeClosed(1, 500)
                        .mapToObj(id -> new ScoredMovie(id, new BigDecimal(id).negate()))
                        .toList();
        when(reader.read(7)).thenReturn(new OttCandidateSnapshot(candidates));
        List<OttMovie> matches = new ArrayList<>();
        for (long id = 1; id <= 500; id++) {
            matches.add(movie(1, "NETFLIX", id, false));
        }
        List<Long> allIds = LongStream.rangeClosed(1, 500).boxed().toList();
        when(repository.findAvailableMovies(eq(7L), eq(allIds), eq(clock.instant())))
                .thenReturn(matches);

        var result = service.getRecommendations(7);

        assertThat(result.insufficientData()).isFalse();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().matchCount()).isEqualTo(500);
        assertThat(result.items().getFirst().topMovies())
                .extracting(movie -> movie.movieId())
                .containsExactly(1L, 2L, 3L);
        assertThat(result.aggregatedAt()).isEqualTo(clock.instant());
        verify(repository).findAvailableMovies(7L, allIds, clock.instant());
    }

    @Test
    void countsZeroScoreFallbackAndSortsProviderTies() {
        when(reader.read(7))
                .thenReturn(
                        new OttCandidateSnapshot(
                                List.of(
                                        new ScoredMovie(3, BigDecimal.ZERO),
                                        new ScoredMovie(1, BigDecimal.ZERO),
                                        new ScoredMovie(2, BigDecimal.ZERO))));
        when(repository.findAvailableMovies(eq(7L), eq(List.of(1L, 2L, 3L)), eq(clock.instant())))
                .thenReturn(
                        List.of(
                                movie(2, "DISNEY_PLUS", 3, true),
                                movie(1, "NETFLIX", 2, false),
                                movie(2, "DISNEY_PLUS", 1, true),
                                movie(1, "NETFLIX", 1, false)));

        var result = service.getRecommendations(7);

        assertThat(result.items()).extracting(item -> item.ott().ottId()).containsExactly(1L, 2L);
        assertThat(result.items()).extracting(item -> item.matchCount()).containsExactly(2, 2);
        assertThat(result.items().get(1).subscribed()).isTrue();
        assertThat(result.items().get(1).topMovies())
                .extracting(movie -> movie.movieId())
                .containsExactly(1L, 3L);
    }

    @Test
    void mapsDatabaseFailureToRecommendationUnavailable() {
        when(reader.read(7))
                .thenReturn(new OttCandidateSnapshot(List.of(new ScoredMovie(1, BigDecimal.ONE))));
        when(repository.findAvailableMovies(7L, List.of(1L), clock.instant()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> service.getRecommendations(7))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exception ->
                                assertThat(((BusinessException) exception).getErrorCode())
                                        .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE));
    }

    private OttMovie movie(long ottId, String code, long movieId, boolean subscribed) {
        return new OttMovie(
                ottId, code, code, null, subscribed, movieId, "movie-" + movieId, null, 2020);
    }
}
