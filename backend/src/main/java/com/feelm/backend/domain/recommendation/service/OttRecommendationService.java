package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.OttRecommendationResponse;
import com.feelm.backend.domain.recommendation.dto.response.OttRecommendationResponse.Item;
import com.feelm.backend.domain.recommendation.dto.response.OttRecommendationResponse.Movie;
import com.feelm.backend.domain.recommendation.dto.response.OttRecommendationResponse.Ott;
import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot.ScoredMovie;
import com.feelm.backend.domain.recommendation.repository.OttCandidateReader;
import com.feelm.backend.domain.recommendation.repository.OttRecommendationRepository;
import com.feelm.backend.domain.recommendation.repository.OttRecommendationRepository.OttMovie;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** API-068: 한 사용자의 TASTE 후보 전체를 OTT 제공처별로 집계한다. */
@Service
@RequiredArgsConstructor
public class OttRecommendationService {

    private static final int TOP_MOVIE_COUNT = 3;
    private static final Comparator<ScoredMovie> CANDIDATE_ORDER =
            Comparator.comparing(ScoredMovie::score)
                    .reversed()
                    .thenComparingLong(ScoredMovie::movieId);

    private final OttCandidateReader candidateReader;
    private final OttRecommendationRepository repository;
    private final Clock clock;

    public OttRecommendationResponse getRecommendations(long userId) {
        Instant now = Instant.now(clock);
        List<ScoredMovie> ranked =
                candidateReader.read(userId).taste().stream().sorted(CANDIDATE_ORDER).toList();
        List<Long> movieIds = ranked.stream().map(ScoredMovie::movieId).toList();
        List<OttMovie> available;
        try {
            available = repository.findAvailableMovies(userId, movieIds, now);
        } catch (DataAccessException exception) {
            BusinessException unavailable =
                    new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
            unavailable.initCause(exception);
            throw unavailable;
        }
        Map<Long, Integer> rankByMovie = new HashMap<>();
        for (int index = 0; index < ranked.size(); index++) {
            rankByMovie.put(ranked.get(index).movieId(), index);
        }

        Map<Long, List<OttMovie>> byOtt = new LinkedHashMap<>();
        for (OttMovie item : available) {
            byOtt.computeIfAbsent(item.ottId(), ignored -> new ArrayList<>()).add(item);
        }
        List<Item> items =
                byOtt.values().stream()
                        .map(movies -> toItem(movies, rankByMovie))
                        .sorted(
                                Comparator.comparingInt(Item::matchCount)
                                        .reversed()
                                        .thenComparingLong(item -> item.ott().ottId()))
                        .toList();
        return new OttRecommendationResponse(false, now, items);
    }

    private Item toItem(List<OttMovie> movies, Map<Long, Integer> rankByMovie) {
        OttMovie first = movies.getFirst();
        List<Movie> topMovies =
                movies.stream()
                        .sorted(Comparator.comparingInt(movie -> rankByMovie.get(movie.movieId())))
                        .limit(TOP_MOVIE_COUNT)
                        .map(
                                movie ->
                                        new Movie(
                                                movie.movieId(),
                                                movie.title(),
                                                movie.posterUrl(),
                                                movie.releaseYear()))
                        .toList();
        return new Item(
                new Ott(first.ottId(), first.code(), first.name(), first.logoUrl()),
                movies.size(),
                first.subscribed(),
                topMovies);
    }
}
