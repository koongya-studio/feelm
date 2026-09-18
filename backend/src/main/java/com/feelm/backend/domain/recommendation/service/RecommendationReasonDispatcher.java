package com.feelm.backend.domain.recommendation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** 알림 offset 처리와 분리해 미노출 추천 이유를 보강한다. */
@Slf4j
@Component
class RecommendationReasonDispatcher {
    private final ThreadPoolTaskExecutor recommendationReasonExecutor;
    private final RecommendationReasonService reasonService;

    RecommendationReasonDispatcher(
            @Qualifier("recommendationReasonExecutor")
                    ThreadPoolTaskExecutor recommendationReasonExecutor,
            RecommendationReasonService reasonService) {
        this.recommendationReasonExecutor = recommendationReasonExecutor;
        this.reasonService = reasonService;
    }

    void submit(long setId) {
        try {
            recommendationReasonExecutor.execute(() -> complete(setId));
        } catch (TaskRejectedException exception) {
            log.warn("Recommendation reason completion queue full for set {}", setId);
        }
    }

    private void complete(long setId) {
        try {
            reasonService.completeUnexposed(setId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Recommendation reason completion failed for set {}: {}",
                    setId,
                    exception.getClass().getSimpleName());
        }
    }
}
