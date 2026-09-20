package com.feelm.backend.domain.recommendation.model;

/**
 * 생성했거나 동일 후보 버전으로 재사용한 PostgreSQL 추천 세트의 식별자를 반환한다.
 *
 * <p>{@code created}는 이번 호출이 세트를 새로 저장했는지다. 재사용한 경우 이유 보강을 다시 제출하지 않는 데 쓴다.
 */
public record RecommendationBufferResult(long recommendationSetId, boolean created) {

    public RecommendationBufferResult(long recommendationSetId) {
        this(recommendationSetId, true);
    }
}
