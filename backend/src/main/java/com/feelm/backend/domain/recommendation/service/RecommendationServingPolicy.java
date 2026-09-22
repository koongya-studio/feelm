package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;

/** API-045가 지원하는 화면별 노출 구성이다. */
record RecommendationServingPolicy(int size, int tasteSize, int discoverySize) {

    static RecommendationServingPolicy forSize(int size) {
        return switch (size) {
            case 3 -> new RecommendationServingPolicy(3, 2, 1);
            case 6 -> new RecommendationServingPolicy(6, 3, 3);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }
}
