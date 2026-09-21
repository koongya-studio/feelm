package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
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
    private final RecommendationBufferRepository bufferRepository;
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

    /**
     * 카드가 남아 있는 동안 같은 generation의 다음 회차를 미리 만든다(S15P21E106-708).
     *
     * <p>조회 응답과 분리된 실행기에서 호출한다. 후보는 여기서 잠금 밖에서 읽고, 회차를 만들지 판단하는 재확인은 쓰기 트랜잭션이 잠금 안에서 한다.
     *
     * <p>남은 후보가 없으면 만들지 않고 끝낸다. 아직 서빙할 카드가 있으므로 오류가 아니며, 실제로 소진되는 시점에 동기 경로가 다시 판단한다.
     */
    public void prefetchNextBatch(long userId, long currentSetId, String generationId) {
        // generation_id가 없는 레거시 세트는 이어 만들 회차가 없다.
        if (generationId == null) {
            return;
        }
        // 잠금 없는 사전 확인이다. 앞선 프리페치가 이미 다음 회차를 만들었거나 알림이 새 generation을
        // 만들었으면 여기서 끝낸다. 없으면 현재 회차가 소진될 때까지 요청마다 Redis를 읽게 된다.
        // 권위 있는 확인은 쓰기 트랜잭션이 잠금 안에서 다시 한다.
        if (!bufferRepository.findLatestSetId(userId).stream()
                .anyMatch(latestSetId -> latestSetId == currentSetId)) {
            return;
        }
        RecommendationCandidateBatch batch = candidateReader.read(userId);
        // Redis가 새 generation으로 넘어갔으면 쓰기 트랜잭션이 어차피 아무것도 만들지 않는다. 여기서
        // 끊어야 사용자 행 잠금을 잡았다 롤백하는 왕복을 조회마다 반복하지 않는다. 잠금 안에서도 같은
        // 조건을 다시 본다.
        if (!generationId.equals(batch.generationId())) {
            return;
        }
        try {
            writer.writePrefetchBatch(userId, currentSetId, generationId, batch)
                    .ifPresent(result -> reasonDispatcher.submit(result.recommendationSetId()));
        } catch (NoEligibleRecommendationCandidatesException exception) {
            log.info("No eligible recommendation candidates to prefetch for user {}", userId);
        }
    }

    /** 알림 경로는 버퍼 커밋 뒤 이유 보강을 별도로 제출해 Kafka ACK를 기다리게 하지 않는다. */
    public RecommendationBufferResult createBufferFromNotice(long userId) {
        RecommendationBufferResult result = writer.writeCurrentFromNotice(userId);
        reasonDispatcher.submit(result.recommendationSetId());
        return result;
    }
}
