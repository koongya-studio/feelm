package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationResponse;
import com.feelm.backend.domain.recommendation.service.RecommendationServingReader.ServingSnapshot;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * API-045 조회를 조립한다.
 *
 * <p>현재 세트를 읽고, 보여줄 유효 카드가 한 편도 없으면(소진) 같은 generation의 다음 회차를 만든 뒤 다시 읽는다. 재생성은 조회 트랜잭션 밖에서 실행해 사용자
 * 행 잠금을 쥔 채 Redis를 기다리지 않는다.
 *
 * <p>남은 후보가 임계값 이하로 내려가면 소진되기 전에 다음 회차를 미리 만들어 둔다(S15P21E106-708). 응답을 만든 뒤 별도 실행기에 제출하므로 프리페치의
 * 지연이나 실패가 이 응답에 영향을 주지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RecommendationServingService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationServingService.class);

    private final RecommendationServingReader reader;
    private final RecommendationBufferService bufferService;
    private final RecommendationPrefetchDispatcher prefetchDispatcher;
    private final RecommendationPrefetchProperties prefetchProperties;

    public RecommendationResponse getRecommendations(long userId) {
        return getRecommendations(userId, 3);
    }

    public RecommendationResponse getRecommendations(long userId, int size) {
        RecommendationServingPolicy policy = RecommendationServingPolicy.forSize(size);
        ServingSnapshot snapshot = reader.read(userId, policy);
        if (!snapshot.exhausted()) {
            if (prefetchProperties.needsPrefetch(snapshot.unexposedValidCount())) {
                prefetchDispatcher.submit(userId, snapshot.setId(), snapshot.generationId());
            }
            return snapshot.response();
        }

        try {
            bufferService.createNextBatch(userId, snapshot.setId());
        } catch (BusinessException exception) {
            // 후보를 쓸 수 없는 경우만 준비 중으로 바꾼다. 인증 실패 등 다른 의미는 그대로 전파해야
            // 새 오류 코드가 장애·준비 중 구분이라는 목적을 유지한다.
            if (exception.getErrorCode() != ErrorCode.RECOMMENDATION_UNAVAILABLE) {
                throw exception;
            }
            // 재생성 실패는 기존 세트·이력을 바꾸지 않는다. 다음 조회에서 다시 시도한다.
            log.info("Recommendation candidates are unavailable for user {}", userId);
            throw new BusinessException(ErrorCode.RECOMMENDATION_PREPARING);
        } catch (DuplicateKeyException exception) {
            // (generation_id, batch_no) 고유 인덱스가 막은 동시 생성이다. 먼저 만든 회차를 다시 읽는다.
            log.info("Recommendation batch already created concurrently for user {}", userId);
        }

        // 방금 만든 회차는 여기서 프리페치를 판단하지 않는다. 한 요청에서 회차를 만들고 그다음 회차까지
        // 준비하면 쓰기가 두 번 겹친다. 버퍼가 얕으면 다음 조회가 바로 판단한다.
        ServingSnapshot refreshed = reader.read(userId, policy);
        if (refreshed.response().items().isEmpty()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_PREPARING);
        }
        return refreshed.response();
    }
}
