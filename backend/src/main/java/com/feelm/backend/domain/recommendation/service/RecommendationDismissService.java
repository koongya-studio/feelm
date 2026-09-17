package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationDismissResponse;
import com.feelm.backend.domain.recommendation.repository.RecommendationDismissRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationDismissRepository.SourceItem;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.DismissEventPayload;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationDismissService {

    private final RecommendationDismissRepository recommendationDismissRepository;
    private final KafkaEventPublisher kafkaEventPublisher;

    /** 관심없음 저장과 아웃박스를 하나의 트랜잭션으로 처리한다. */
    @Transactional
    public RecommendationDismissResponse dismiss(
            long userId, long movieId, Long recommendationItemId) {
        validateMovieAndDuplication(userId, movieId);

        source(userId, movieId, recommendationItemId);

        try {
            recommendationDismissRepository.saveDismissal(userId, movieId, recommendationItemId);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.ALREADY_DISMISSED);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        kafkaEventPublisher.publish(
                KafkaEventType.DISMISS,
                userId,
                new DismissEventPayload(movieId, recommendationItemId));
        return new RecommendationDismissResponse(movieId);
    }

    /** 다음 추천 버퍼 생성기가 관심없음 영화를 동기적으로 제외할 때 사용하는 진입점이다. */
    @Transactional(readOnly = true)
    public List<Long> dismissedMovieIds(long userId) {
        return recommendationDismissRepository.findDismissedMovieIds(userId);
    }

    private void validateMovieAndDuplication(long userId, long movieId) {
        if (!recommendationDismissRepository.lockActiveUserIfExists(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!recommendationDismissRepository.lockMovieIfExists(movieId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (recommendationDismissRepository.dismissalExists(userId, movieId)) {
            throw new BusinessException(ErrorCode.ALREADY_DISMISSED);
        }
    }

    private SourceItem source(long userId, long movieId, Long recommendationItemId) {
        if (recommendationItemId == null) {
            return null;
        }
        return recommendationDismissRepository
                .findOwnedExposedSource(userId, movieId, recommendationItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
