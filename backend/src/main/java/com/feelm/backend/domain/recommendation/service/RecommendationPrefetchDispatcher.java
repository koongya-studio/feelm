package com.feelm.backend.domain.recommendation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 남은 후보가 임계값 이하일 때 다음 회차를 미리 만든다(S15P21E106-708).
 *
 * <p>조회 응답을 만든 뒤 제출하며 결과를 기다리지 않는다. 카드가 남아 있는 동안 준비하므로 실패해도 치명적이지 않고, 남은 유효 카드를 그대로 반환한 뒤 다음 조회가 다시
 * 시도한다. 그래서 모든 예외를 여기서 삼킨다. 프리페치가 끝내 돌지 못하면 소진 시점에 기존 동기 재생성(S15P21E106-219)이 이어받는다.
 */
@Slf4j
@Component
class RecommendationPrefetchDispatcher {

    private final ThreadPoolTaskExecutor recommendationPrefetchExecutor;
    private final RecommendationBufferService bufferService;

    RecommendationPrefetchDispatcher(
            @Qualifier("recommendationPrefetchExecutor")
                    ThreadPoolTaskExecutor recommendationPrefetchExecutor,
            RecommendationBufferService bufferService) {
        this.recommendationPrefetchExecutor = recommendationPrefetchExecutor;
        this.bufferService = bufferService;
    }

    void submit(long userId, long currentSetId, String generationId) {
        try {
            recommendationPrefetchExecutor.execute(
                    () -> prefetch(userId, currentSetId, generationId));
        } catch (TaskRejectedException exception) {
            log.warn("Recommendation prefetch queue full for user {}", userId);
        }
    }

    private void prefetch(long userId, long currentSetId, String generationId) {
        try {
            bufferService.prefetchNextBatch(userId, currentSetId, generationId);
        } catch (DuplicateKeyException exception) {
            // (generation_id, batch_no) 고유 인덱스가 막은 동시 생성이다. 먼저 만든 회차가 이미 있으므로 할 일이 없다.
            log.info("Recommendation batch already created concurrently for user {}", userId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Recommendation prefetch failed for user {} set {}: {}",
                    userId,
                    currentSetId,
                    exception.getClass().getSimpleName());
        }
    }
}
