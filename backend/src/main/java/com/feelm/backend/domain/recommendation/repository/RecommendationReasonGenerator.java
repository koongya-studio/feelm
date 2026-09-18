package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.model.RecommendationReasonPrompt;
import java.util.List;
import java.util.Map;

/** 확인된 근거별 허용 문구 유형을 고른다. 응답은 신뢰하지 않고 서비스에서 재검증한다. */
public interface RecommendationReasonGenerator {

    Map<Long, String> selectVariants(List<RecommendationReasonPrompt> prompts);
}
