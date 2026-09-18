package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationItemResponse;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationMovieResponse;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationResponse;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository.RecommendationSet;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository.ServingItem;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationServingService {

    private static final int TARGET_SIZE = 3;

    private final RecommendationServingRepository repository;
    private final RecommendationReplenisher replenisher;
    private final RecommendationReasonPresenter reasonPresenter;
    private final Clock clock;

    @Transactional
    public RecommendationResponse getRecommendations(long userId) {
        lockActiveUser(userId);
        RecommendationSet set = findCurrentSet(userId);
        List<ServingItem> items = repository.findInitialVisibleItems(userId, set.id());
        if (items.isEmpty() && !repository.hasExposedItems(set.id())) {
            items = exposeInitial(userId, set.id());
        } else if (items.size() < TARGET_SIZE) {
            replenisher.replenishVacancies(userId, set.id(), items);
            items = repository.findInitialVisibleItems(userId, set.id());
        }
        return new RecommendationResponse(set.id(), set.generatedAt(), map(items));
    }

    private RecommendationSet findCurrentSet(long userId) {
        return repository
                .findLatestSet(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));
    }

    private void lockActiveUser(long userId) {
        if (!repository.lockActiveUserIfExists(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private List<ServingItem> exposeInitial(long userId, long setId) {
        List<ServingItem> items = repository.findAndLockInitialItems(userId, setId);
        if (!items.isEmpty()) {
            if (!repository.expose(
                    items, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))) {
                throw new IllegalStateException("추천 후보 노출 상태가 변경되었습니다.");
            }
        }
        return items;
    }

    private List<RecommendationItemResponse> map(List<ServingItem> items) {
        List<Long> movieIds = items.stream().map(ServingItem::movieId).distinct().toList();
        Map<Long, List<String>> genres = repository.findGenreNames(movieIds);
        Map<Long, List<String>> otts = repository.findOttCodes(movieIds);
        return items.stream().map(item -> map(item, genres, otts)).toList();
    }

    private RecommendationItemResponse map(
            ServingItem item, Map<Long, List<String>> genres, Map<Long, List<String>> otts) {
        RecommendationMovieResponse movie =
                new RecommendationMovieResponse(
                        item.movieId(),
                        item.title(),
                        item.posterUrl(),
                        item.releaseYear(),
                        genres.getOrDefault(item.movieId(), List.of()),
                        item.averageRating(),
                        otts.getOrDefault(item.movieId(), List.of()));
        return new RecommendationItemResponse(
                item.id(),
                item.type(),
                movie,
                item.flavorCode(),
                reasonPresenter.present(item.reason()));
    }
}
