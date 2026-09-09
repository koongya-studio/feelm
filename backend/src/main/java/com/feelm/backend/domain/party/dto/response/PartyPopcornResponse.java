package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import java.math.BigDecimal;
import java.util.List;

public record PartyPopcornResponse(long partyId, List<MemberPopcorn> members) {

    public record MemberPopcorn(UserSummary user, long totalCount, List<FlavorBucket> flavors) {
        public static MemberPopcorn from(
                long userId, String nickname, PopcornBucketResponse bucket) {
            return new MemberPopcorn(
                    new UserSummary(userId, nickname),
                    bucket.totalCount(),
                    bucket.flavors().stream().map(FlavorBucket::from).toList());
        }
    }

    public record UserSummary(long userId, String nickname) {}

    public record FlavorBucket(String flavorCode, long popcornCount, BigDecimal averageScore) {
        private static FlavorBucket from(PopcornBucketResponse.FlavorBucket bucket) {
            return new FlavorBucket(
                    bucket.flavorCode(), bucket.popcornCount(), bucket.averageScore());
        }
    }
}
