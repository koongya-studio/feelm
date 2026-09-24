package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonResult;
import java.util.List;
import java.util.Map;

/** 확인된 GBT evidence로 사용자용 이유를 생성한다. 응답은 신뢰하지 않고 서비스에서 재검증한다. */
public interface RecommendationReasonGenerator {

    /** 검증된 GBT evidence만 표현한 summary와 실제 사용한 evidence ID를 생성한다. */
    Map<Long, GbtRecommendationReasonResult> generateGbtReasons(
            List<GbtRecommendationReasonPrompt> prompts);
}
