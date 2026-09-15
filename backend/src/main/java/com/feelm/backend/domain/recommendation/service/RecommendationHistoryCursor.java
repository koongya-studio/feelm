package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

/** API-048 추천 세트 최신순 조회의 불투명 커서. */
record RecommendationHistoryCursor(Instant generatedAt, long recommendationSetId) {

    String encode() {
        return CursorCodec.encode(generatedAt, recommendationSetId);
    }

    static RecommendationHistoryCursor decode(String encoded) {
        return CursorCodec.decodeInstant(encoded, RecommendationHistoryCursor::new);
    }
}
