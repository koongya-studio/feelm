package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.Optional;
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

    private final RecommendationCandidateReader candidateReader;
    private final RecommendationBufferWriter writer;
    private final RecommendationReasonService reasonService;

    /** 사용자의 최신 Redis 후보로 추천 버퍼를 생성하거나 동일 버전의 기존 버퍼를 반환한다. */
    public RecommendationBufferResult createBuffer(long userId) {
        RecommendationCandidateBatch candidates = candidateReader.read(userId);
        RecommendationBufferResult result = writer.write(userId, candidates);
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

    /** Kafka 갱신 알림 연결 전, 조회 요청에서 Redis 후보가 있을 때만 임시로 버퍼를 최신화한다. */
    public Optional<RecommendationBufferResult> createBufferIfCandidatesAvailable(long userId) {
        RecommendationCandidateBatch candidates;
        try {
            candidates = candidateReader.read(userId);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.RECOMMENDATION_UNAVAILABLE) {
                throw exception;
            }
            return Optional.empty();
        }
        return writer.writeIfEligible(userId, candidates);
    }
}
