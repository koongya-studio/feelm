package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import java.math.BigDecimal;
import java.util.List;

/**
 * API-057 파티원 팝콘 응답.
 *
 * <p>사용자 요약을 {@link PartyResponse.UserSummary#from}으로 만들지 않는 유일한 파티 응답이다. 팝콘 조회는 native 질의로 <b>활성
 * 파티의 현재 멤버</b>만 읽어 닉네임 문자열을 받으므로 탈퇴자가 여기 도달할 수 없다 — 탈퇴는 활성 파티 멤버십을 지우고(API-013), 종료 파티는 {@code
 * 404}다. 이 전제가 바뀌면(종료 파티 팝콘 조회 등) 질의가 계정 상태도 읽고 탈퇴자 표기 규칙(S15P21E106-759)을 적용해야 한다.
 */
public record PartyPopcornResponse(long partyId, List<MemberPopcorn> members) {

    public record MemberPopcorn(UserSummary user, long totalCount, List<FlavorBucket> flavors) {
        public static MemberPopcorn from(
                long userId, String nickname, String avatarUrl, PopcornBucketResponse bucket) {
            return new MemberPopcorn(
                    new UserSummary(userId, nickname, avatarUrl),
                    bucket.totalCount(),
                    bucket.flavors().stream().map(FlavorBucket::from).toList());
        }
    }

    public record UserSummary(long userId, String nickname, String avatarUrl) {}

    public record FlavorBucket(String flavorCode, long popcornCount, BigDecimal averageScore) {
        private static FlavorBucket from(PopcornBucketResponse.FlavorBucket bucket) {
            return new FlavorBucket(
                    bucket.flavorCode(), bucket.popcornCount(), bucket.averageScore());
        }
    }
}
