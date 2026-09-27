package com.feelm.backend.domain.party.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param hasNext 다시 받기로 더 보여줄 유효한 미노출 후보가 남아 있으면 {@code true}. {@code false}면 더 보여줄 유효 후보가 없는
 *     상태다(저장 후보를 모두 소진했거나 남은 후보가 전부 파티원 기준으로 제외됨)
 */
public record PartyRecommendationResponse(
        long recommendationSetId,
        long partyId,
        OffsetDateTime generatedAt,
        List<PartyRecommendationItemResponse> items,
        boolean hasNext,
        PartyPopcornSnapshotResponse popcorn,
        String recommendationMode) {
    public PartyRecommendationResponse(
            long recommendationSetId,
            long partyId,
            OffsetDateTime generatedAt,
            List<PartyRecommendationItemResponse> items,
            boolean hasNext) {
        this(recommendationSetId, partyId, generatedAt, items, hasNext, null, "PERSONALIZED");
    }
}
