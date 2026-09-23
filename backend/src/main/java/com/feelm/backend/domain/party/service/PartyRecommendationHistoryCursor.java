package com.feelm.backend.domain.party.service;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

/**
 * API-083 파티 추천 이력의 세트 최신순 조회 커서.
 *
 * <p>정렬 키는 세트 ID 내림차순 하나다. {@code generatedAt}은 표시값으로만 싣고 판정에 쓰지 않는다(API-048과 같은 규칙 — 서버 시계가 역전되어도
 * 순서가 바뀌지 않는다). 개인 이력의 커서와 모양이 같지만 record는 도메인마다 따로 둔다(컨벤션 11장).
 */
record PartyRecommendationHistoryCursor(Instant generatedAt, long recommendationSetId) {

    String encode() {
        return CursorCodec.encode(generatedAt, recommendationSetId);
    }

    static PartyRecommendationHistoryCursor decode(String encoded) {
        return CursorCodec.decodeInstant(encoded, PartyRecommendationHistoryCursor::new);
    }
}
