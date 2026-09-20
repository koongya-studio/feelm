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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 세트의 노출·보충과 응답 조립을 한 트랜잭션에서 수행한다.
 *
 * <p>소진 후 재생성은 이 트랜잭션 밖에서 일어나야 하므로(사용자 행 잠금을 쥔 채 Redis를 기다리지 않는다) 읽기만 여기에 둔다.
 *
 * <p>소진은 "보여줄 유효 카드가 0편"으로 엄격하게 정의한다. 이것이 조회를 읽기·재생성·재읽기 세 트랜잭션으로 나눠도 노출된 카드가 유실되지 않는 근거다. 남은 카드가
 * 있는 채로 다음 회차를 만들면 {@code findLatestSet}이 새 회차를 집는 순간 그 카드가 화면에서 사라진다. 판정이 낡는 경우(예: 판정 뒤 평가 철회로 카드가
 * 되살아남)는 재생성 트랜잭션이 잠금 안에서 다시 확인해 흡수한다.
 */
@Component
@RequiredArgsConstructor
class RecommendationServingReader {

    private static final int TARGET_SIZE = 3;

    private final RecommendationServingRepository repository;
    private final RecommendationReplenisher replenisher;
    private final RecommendationReasonPresenter reasonPresenter;
    private final Clock clock;

    @Transactional
    public ServingSnapshot read(long userId) {
        lockActiveUser(userId);
        RecommendationSet set = findCurrentSet(userId);
        List<ServingItem> items = repository.findInitialVisibleItems(userId, set.id());
        if (items.isEmpty() && !repository.hasExposedItems(set.id())) {
            items = exposeInitial(userId, set.id());
        }
        // 최초 노출이 목표 구성을 못 채운 경우에도 같은 요청에서 보충을 시도한다(S15P21E106-219).
        if (items.size() < TARGET_SIZE) {
            replenisher.replenishVacancies(userId, set.id(), items);
            items = repository.findInitialVisibleItems(userId, set.id());
        }
        boolean exhausted = items.isEmpty() && !repository.hasValidItems(userId, set.id());
        return new ServingSnapshot(
                new RecommendationResponse(set.id(), set.generatedAt(), map(items)),
                set.id(),
                exhausted);
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

    /** 현재 세트의 응답과 소진 여부다. {@code exhausted}는 노출 여부와 무관하게 유효 카드가 한 편도 없는 상태다. */
    record ServingSnapshot(RecommendationResponse response, long setId, boolean exhausted) {}
}
