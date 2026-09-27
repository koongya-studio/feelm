package com.feelm.backend.domain.party.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** API-083 파티 추천 이력의 세트 하나. 항목은 노출 시각 오름차순, 같으면 순위 순이다. */
public record PartyRecommendationHistorySetResponse(
        long recommendationSetId,
        OffsetDateTime generatedAt,
        List<PartyRecommendationHistoryItemResponse> items,
        List<PartyPopcornSnapshotResponse> popcornSnapshots) {
    public PartyRecommendationHistorySetResponse(
            long recommendationSetId,
            OffsetDateTime generatedAt,
            List<PartyRecommendationHistoryItemResponse> items) {
        this(recommendationSetId, generatedAt, items, List.of());
    }
}
