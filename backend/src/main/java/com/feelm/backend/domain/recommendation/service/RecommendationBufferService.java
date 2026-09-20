package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Redis의 최신 추천 후보를 읽어 PostgreSQL 추천 버퍼 생성을 요청하는 진입점이다. */
@Service
@RequiredArgsConstructor
public class RecommendationBufferService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationBufferService.class);

    private final RecommendationBufferWriter writer;
    private final RecommendationCandidateReader candidateReader;
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
        } catch (RuntimeException exception) {
            log.warn(
                    "Recommendation reason completion failed for set {}: {}",
                    result.recommendationSetId(),
                    exception.getClass().getSimpleName());
        }
        return result;
    }

    /**
     * 소진된 세트 뒤에 같은 generation의 다음 회차를 만든다(API-045 소진 흐름).
     *
     * <p>조회 트랜잭션 밖에서 호출한다. Redis 후보도 쓰기 트랜잭션 밖에서 읽어 사용자 행 잠금을 쥔 채 Redis를 기다리지 않는다.
     *
     * <p>이유 보강은 알림 경로와 같은 모양으로 별도 실행기에 제출한다. 조회 요청 스레드에서 LLM을 기다리지 않게 하고, 첫 노출 문구는 저장 전에 확정하므로 보강이
     * 늦어도 근거가 사라지지 않는다.
     */
    public RecommendationBufferResult createNextBatch(long userId, long exhaustedSetId) {
        RecommendationCandidateBatch batch = candidateReader.read(userId);
        RecommendationBufferResult result;
        try {
            result = writer.writeNextBatch(userId, exhaustedSetId, batch);
        } catch (NoEligibleRecommendationCandidatesException exception) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
        if (result.created()) {
            reasonDispatcher.submit(result.recommendationSetId());
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
