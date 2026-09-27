package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.dto.response.PartyRecommendationHistoryItemResponse;
import com.feelm.backend.domain.party.dto.response.PartyRecommendationHistorySetResponse;
import com.feelm.backend.domain.party.repository.PartyRecommendationHistoryRepository;
import com.feelm.backend.domain.party.repository.PartyRecommendationHistoryRepository.HistoryItem;
import com.feelm.backend.domain.party.repository.PartyRecommendationHistoryRepository.HistorySet;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationMovieResponse;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-083 파티 추천 이력 조회(S15P21E106-815).
 *
 * <p>파티가 화면에 노출한 추천 항목을 세트 최신순으로 돌려준다. 파티 상태는 보지 않고 멤버십 행만 본다 — 파티장이 종료한 파티는 멤버십이 이력으로 남아 종료 당시의
 * 멤버가 그때 추천받은 영화를 돌아볼 수 있고, 내 파티 목록(API-050)이 종료 파티를 보여 주는 이유가 이것이다. 마지막 멤버가 나가서 종료된 파티는 멤버십이 없어 읽을
 * 사람도 없다(API-055, 리뷰 B-3). 읽기만 하며 {@code exposed_at}이나 API-058의 현재 묶음에는 손대지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PartyRecommendationHistoryService {

    private final PartyRepository partyRepository;
    private final PartyRecommendationHistoryRepository historyRepository;
    private final PartyRecommendationRepository recommendationRepository;
    private final PartyPopcornSnapshots popcornSnapshots;

    /**
     * 세트 페이지와 항목을 한 스냅샷에서 읽는다.
     *
     * <p>두 질의 사이에 다시 받기(API-058)가 어떤 영화의 최신 노출을 다른 세트로 옮기면({@code party_movie_exposures} upsert), 첫
     * 질의가 고른 세트에 남는 항목이 없어 빈 {@code items[]}가 내려갈 수 있다. REPEATABLE READ가 첫 문장의 스냅샷을 고정해 두 질의가 같은
     * 상태를 보게 한다(API-048과 같은 처리).
     *
     * <p>접근 판정은 멤버십만 본다({@link PartyRepository#findAccessibleById}). 멤버가 아니면 파티 존재를 노출하지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CursorPageResponse<PartyRecommendationHistorySetResponse> getHistory(
            long userId, long partyId, String encodedCursor, Integer requestedSize) {
        int size = requestedSize == null ? 20 : requestedSize;
        if (size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PartyRecommendationHistoryCursor cursor =
                PartyRecommendationHistoryCursor.decode(encodedCursor);
        partyRepository
                .findAccessibleById(partyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        List<HistorySet> sets =
                historyRepository.findSets(
                        partyId, cursor == null ? 0 : cursor.recommendationSetId(), size + 1);
        boolean hasNext = sets.size() > size;
        List<HistorySet> page = hasNext ? sets.subList(0, size) : sets;
        if (page.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }

        List<HistoryItem> items =
                historyRepository.findItems(partyId, page.stream().map(HistorySet::id).toList());
        List<Long> movieIds = items.stream().map(HistoryItem::movieId).distinct().toList();
        Map<Long, List<String>> genres = recommendationRepository.findGenreNames(movieIds);
        Map<Long, List<String>> otts = recommendationRepository.findOttCodes(movieIds);

        Map<Long, List<PartyRecommendationHistoryItemResponse>> itemsBySet = new LinkedHashMap<>();
        for (HistoryItem item : items) {
            itemsBySet
                    .computeIfAbsent(item.setId(), ignored -> new ArrayList<>())
                    .add(response(item, genres, otts));
        }
        var snapshotsBySet =
                popcornSnapshots.forSets(partyId, page.stream().map(HistorySet::id).toList());
        List<PartyRecommendationHistorySetResponse> responses =
                page.stream()
                        .map(
                                set ->
                                        new PartyRecommendationHistorySetResponse(
                                                set.id(),
                                                set.generatedAt(),
                                                itemsBySet.getOrDefault(set.id(), List.of()),
                                                snapshotsBySet.getOrDefault(set.id(), List.of())))
                        .toList();
        HistorySet last = page.getLast();
        String nextCursor =
                hasNext
                        ? new PartyRecommendationHistoryCursor(
                                        last.generatedAt().toInstant(), last.id())
                                .encode()
                        : null;
        return new CursorPageResponse<>(responses, nextCursor, hasNext);
    }

    /** API-058과 같은 영화 요약을 붙인다. */
    private PartyRecommendationHistoryItemResponse response(
            HistoryItem item, Map<Long, List<String>> genres, Map<Long, List<String>> otts) {
        RecommendationMovieResponse movie =
                new RecommendationMovieResponse(
                        item.movieId(),
                        item.title(),
                        item.posterUrl(),
                        item.releaseYear(),
                        genres.getOrDefault(item.movieId(), List.of()),
                        item.averageRating(),
                        otts.getOrDefault(item.movieId(), List.of()));
        return new PartyRecommendationHistoryItemResponse(
                item.id(), "PARTY", item.exposedAt(), movie);
    }
}
