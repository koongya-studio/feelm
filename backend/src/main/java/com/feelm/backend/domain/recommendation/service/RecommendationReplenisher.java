package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository.ServingItem;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository.SourceItem;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 평가·관심없음 트랜잭션에서도 재사용할 수 있는 현재 추천 세트 한 자리 보충 로직이다. */
@Component
@RequiredArgsConstructor
public class RecommendationReplenisher {

    private static final int TARGET_SIZE = 3;

    private final RecommendationServingRepository repository;
    private final Clock clock;

    /** 호출자가 사용자 행 잠금과 트랜잭션을 소유해야 한다. */
    public ServingItem replenish(long userId, long setId, long recommendationItemId) {
        SourceItem source =
                repository
                        .findOwnedExposedSource(userId, setId, recommendationItemId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!repository.isRemoved(userId, source.movieId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        List<ServingItem> visible = repository.findInitialVisibleItems(userId, setId);
        if (visible.size() >= TARGET_SIZE
                || !hasSourceTypeVacancy(userId, setId, visible, source.type())) {
            return null;
        }

        ServingItem replacement = findReplacement(userId, setId, source.type());
        if (replacement != null
                && !repository.expose(
                        List.of(replacement),
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))) {
            throw new IllegalStateException("추천 후보 노출 상태가 변경되었습니다.");
        }
        return replacement;
    }

    private boolean hasSourceTypeVacancy(
            long userId, long setId, List<ServingItem> visible, String sourceType) {
        long taste = visible.stream().filter(item -> "TASTE".equals(item.type())).count();
        long discovery = visible.stream().filter(item -> "DISCOVERY".equals(item.type())).count();
        if ("DISCOVERY".equals(sourceType)) {
            return discovery == 0;
        }
        return taste < 2
                || (discovery == 0
                        && visible.size() < TARGET_SIZE
                        && !repository.hasUnexposedCandidate(userId, setId, "DISCOVERY"));
    }

    private ServingItem findReplacement(long userId, long setId, String sourceType) {
        if ("TASTE".equals(sourceType)) {
            return repository.findAndLockNextItem(userId, setId, "TASTE").orElse(null);
        }
        ServingItem discovery =
                repository.findAndLockNextItem(userId, setId, "DISCOVERY").orElse(null);
        if (discovery != null) {
            return discovery;
        }
        return repository.findAndLockNextItem(userId, setId, "TASTE").orElse(null);
    }
}
