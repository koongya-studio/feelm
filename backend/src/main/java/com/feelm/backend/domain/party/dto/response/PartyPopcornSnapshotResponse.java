package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.FlavorBucket;
import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.MemberPopcorn;
import java.time.OffsetDateTime;
import java.util.List;

public record PartyPopcornSnapshotResponse(
        long recommendationSetId,
        OffsetDateTime exposedAt,
        String recommendationMode,
        List<MemberPopcorn> members,
        long totalCount,
        List<FlavorBucket> flavors) {}
