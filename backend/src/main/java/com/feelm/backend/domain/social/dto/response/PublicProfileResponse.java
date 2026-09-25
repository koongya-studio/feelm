package com.feelm.backend.domain.social.dto.response;

import com.feelm.backend.domain.user.entity.Visibility;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * API-062 공개 프로필 조회 응답.
 *
 * <p>계정·취향 분석·팝콘·필름·한줄평 집계를 조립하므로 Entity 하나를 받는 {@code from} 대신 서비스가 조립한 값을 그대로 담는다.
 *
 * <p>키워드와 팝콘 버킷은 취향 도메인이 주는 값이지만 그 도메인의 응답 DTO를 그대로 노출하지 않고 계약이 정의한 필드만 다시 담는다. 목적이 다른 API가 DTO를
 * 공유하면 한쪽 계약 변경이 다른 쪽으로 새기 때문이다(컨벤션 5.1).
 *
 * <p>{@code isBlockedByMe}는 계약에 있는 필드라 그대로 두지만 <b>200 응답에서는 항상 {@code false}</b>다. 차단 관계는 어느 방향이든
 * 404로 감추기 때문에(API-062 오류 응답) 차단한 상대의 프로필이 200으로 내려올 수 없다. 화면이 "내가 차단한 사용자" 잠금 상태를 그리려면 계약에서 404
 * 규칙과 이 필드 중 하나를 정리해야 한다(후속 확인 필요).
 */
public record PublicProfileResponse(
        Profile user,
        List<Keyword> keywords,
        Stats stats,
        Popcorn popcorn,
        List<RecentFrame> recentFrames,
        long publicReviewCount,
        boolean isBlockedByMe) {

    /**
     * 공개 프로필의 계정 요약.
     *
     * <p>경로 식별자는 {@code publicId}지만(ADR-0008) 본문의 {@code userId}는 내부 숫자 ID다. 차단(API-020)과 신고가 이 값을
     * 그대로 쓰므로 계약이 본문에는 숫자 ID를 둔다.
     *
     * <p>세 항목 공개 범위는 대상의 실제 설정값이다(S15P21E106-856). 타인 조회에서 {@code PRIVATE}인 구역은 서비스가 값을 비워 담으므로,
     * 화면은 이 값으로 "없음"과 "가림"을 구분한다.
     */
    public record Profile(
            long userId,
            String nickname,
            String avatarUrl,
            Visibility visibility,
            Visibility frameVisibility,
            Visibility popcornVisibility,
            Visibility reviewVisibility) {}

    /** 공개된 취향 키워드. 취향 분석(API-040)이 만든 값을 그대로 보여 준다. */
    public record Keyword(String code, String label) {}

    /** 프레임 수와 팝콘 수. 내 프로필(API-014)의 같은 이름 값과 같은 집계다. */
    public record Stats(long frameCount, long popcornCount) {}

    public record Popcorn(List<FlavorBucket> flavors) {}

    /** 맛별 팝콘 집계. 평가가 없는 기준 맛은 {@code popcornCount}가 0이고 {@code averageScore}가 없다. */
    public record FlavorBucket(String flavorCode, long popcornCount, BigDecimal averageScore) {}

    public record RecentFrame(long frameId, Movie movie, BigDecimal score, Instant createdAt) {}

    public record Movie(long movieId, String title, String posterUrl) {}
}
