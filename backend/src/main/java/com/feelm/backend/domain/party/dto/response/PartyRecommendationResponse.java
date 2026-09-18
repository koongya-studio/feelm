package com.feelm.backend.domain.party.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record PartyRecommendationResponse(
        long recommendationSetId,
        long partyId,
        OffsetDateTime generatedAt,
        List<PartyRecommendationItemResponse> items) {}
