package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;

/** 추천 후보 저장소의 구현 방식과 무관하게 사용자별 최신 후보 묶음을 읽는 포트다. */
public interface RecommendationCandidateReader {

    /**
     * 사용자의 최신 추천 후보를 읽는다.
     *
     * @throws com.feelm.backend.global.error.BusinessException 후보가 없거나 사용할 수 없는 경우
     */
    RecommendationCandidateBatch read(long userId);
}
