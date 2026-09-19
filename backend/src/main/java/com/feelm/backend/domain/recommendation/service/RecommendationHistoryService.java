package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationHistoryItemResponse;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationHistoryMovieResponse;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationHistorySetResponse;
import com.feelm.backend.domain.recommendation.repository.RecommendationHistoryRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationHistoryRepository.HistoryItem;
import com.feelm.backend.domain.recommendation.repository.RecommendationHistoryRepository.HistorySet;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationHistoryService {

    private final RecommendationHistoryRepository repository;
    private final RecommendationReasonPresenter reasonPresenter;

    public CursorPageResponse<RecommendationHistorySetResponse> getHistory(
            long userId, String encodedCursor, Integer requestedSize) {
        int size = requestedSize == null ? 20 : requestedSize;
        if (size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        RecommendationHistoryCursor cursor = RecommendationHistoryCursor.decode(encodedCursor);
        List<HistorySet> sets =
                repository.findSets(
                        userId, cursor == null ? 0 : cursor.recommendationSetId(), size + 1);
        boolean hasNext = sets.size() > size;
        List<HistorySet> page = hasNext ? sets.subList(0, size) : sets;
        if (page.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }

        Map<Long, List<RecommendationHistoryItemResponse>> itemsBySet = new LinkedHashMap<>();
        repository.findItems(userId, page.stream().map(HistorySet::id).toList()).stream()
                .forEach(
                        item ->
                                itemsBySet
                                        .computeIfAbsent(
                                                item.setId(),
                                                ignored -> new java.util.ArrayList<>())
                                        .add(map(item)));
        List<RecommendationHistorySetResponse> items =
                page.stream()
                        .map(
                                set ->
                                        new RecommendationHistorySetResponse(
                                                set.id(),
                                                set.generatedAt(),
                                                itemsBySet.getOrDefault(set.id(), List.of())))
                        .toList();
        HistorySet last = page.getLast();
        String nextCursor =
                hasNext
                        ? new RecommendationHistoryCursor(last.generatedAt(), last.id()).encode()
                        : null;
        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    private RecommendationHistoryItemResponse map(HistoryItem item) {
        return new RecommendationHistoryItemResponse(
                item.id(),
                item.type(),
                new RecommendationHistoryMovieResponse(
                        item.movieId(), item.title(), item.posterUrl()),
                reasonPresenter.present(item.reason()),
                item.dismissed(),
                item.watchStatus());
    }
}
