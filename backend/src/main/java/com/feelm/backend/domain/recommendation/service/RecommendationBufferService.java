package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Redis의 최신 추천 후보를 읽어 PostgreSQL 추천 버퍼 생성을 요청하는 진입점이다. */
@Service
@RequiredArgsConstructor
public class RecommendationBufferService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationBufferService.class);

    private final RecommendationBufferWriter writer;
    private final RecommendationReasonService reasonService;
    private final RecommendationReasonDispatcher reasonDispatcher;

    /** 사용자의 최신 Redis 후보로 추천 버퍼를 생성하거나 동일 버전의 기존 버퍼를 반환한다. */
    public RecommendationBufferResult createBuffer(long userId) {
        RecommendationBufferResult result;
        try {
            result = writer.writeCurrent(userId);
        } catch (NoEligibleRecommendationCandidatesException exception) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
        try {
            reasonService.completeUnexposed(result.recommendationSetId());
        } catch (DataAccessException exception) {
            log.warn(
                    "Recommendation reason completion failed for set {}: {}",
                    result.recommendationSetId(),
                    exception.getClass().getSimpleName());
        }
        return result;
    }

    /** 알림 경로는 버퍼 커밋 뒤 이유 보강을 별도로 제출해 Kafka ACK를 기다리게 하지 않는다. */
    public RecommendationBufferResult createBufferFromNotice(long userId) {
        RecommendationBufferResult result = writer.writeCurrentFromNotice(userId);
        reasonDispatcher.submit(result.recommendationSetId());
        return result;
    }
}
