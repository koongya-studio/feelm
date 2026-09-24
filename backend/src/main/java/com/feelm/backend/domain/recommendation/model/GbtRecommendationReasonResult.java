package com.feelm.backend.domain.recommendation.model;

import java.util.List;

/** Gemini가 반환하고 Backend의 근거 검증을 통과해야 하는 GBT 추천 이유 결과다. */
public record GbtRecommendationReasonResult(String summary, List<String> usedEvidenceIds) {

    public GbtRecommendationReasonResult {
        usedEvidenceIds = usedEvidenceIds == null ? null : List.copyOf(usedEvidenceIds);
    }
}
