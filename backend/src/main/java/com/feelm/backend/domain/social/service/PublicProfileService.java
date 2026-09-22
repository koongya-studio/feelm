package com.feelm.backend.domain.social.service;

import com.feelm.backend.domain.social.dto.response.PublicProfileResponse;
import com.feelm.backend.domain.social.repository.PublicProfileQueryRepository;
import com.feelm.backend.domain.social.repository.PublicProfileQueryRepository.ProfileTarget;
import com.feelm.backend.domain.taste.dto.response.FrameListResponse;
import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import com.feelm.backend.domain.taste.dto.response.TasteAnalysisResponse;
import com.feelm.backend.domain.taste.service.FrameQueryService;
import com.feelm.backend.domain.taste.service.TasteQueryService;
import com.feelm.backend.domain.user.entity.Visibility;
import com.feelm.backend.domain.user.service.AvatarStorage;
import com.feelm.backend.domain.user.service.UserBlockFilter;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-062 공개 프로필 조회.
 *
 * <p>없는 사용자·{@code PRIVATE} 사용자·차단 관계를 모두 같은 404로 응답한다. 셋을 구분하면 공개 URL로 계정의 존재와 차단 여부를 확인할 수 있게
 * 되므로, 오류 응답에서 세 경우를 나누지 않는다(NFR-07).
 *
 * <p>본인 조회는 예외다. 자기 {@code publicId}로 들어오면 공개 범위와 무관하게 200이다. 7.2-A 내 공개 프로필 미리보기가 이 경로를 쓴다.
 */
@Service
@RequiredArgsConstructor
public class PublicProfileService {

    /**
     * 공개 프로필이 보여 주는 최근 필름 수. 필름 목록(API-042)의 기본 페이지 크기와 맞춘다.
     *
     * <p>계약에 개수가 없어 목록 API의 기본값을 따랐다. 화면이 더 적게 그리더라도 같은 첫 페이지를 받는 편이 두 화면의 값이 어긋나지 않는다.
     */
    private static final int RECENT_FRAME_LIMIT = 20;

    private final PublicProfileQueryRepository publicProfileQueryRepository;
    private final UserBlockFilter blockFilter;
    private final TasteQueryService tasteQueryService;
    private final FrameQueryService frameQueryService;
    private final AvatarStorage avatarStorage;

    /**
     * 공개 범위·차단 판정과 본문 집계를 같은 스냅샷에서 읽는다.
     *
     * <p>한 요청이 계정·차단·필름·팝콘·취향·한줄평을 잇달아 읽는다. PostgreSQL 기본 격리 수준인 READ COMMITTED는 문장마다 새 스냅샷을 주므로,
     * 판정을 통과한 직후 대상이 {@code PRIVATE}로 바뀌거나 차단이 성립해도 나머지 문장이 새 상태를 보지 못한 채 본문 한 벌을 내보낸다. {@code
     * User.changeVisibility}가 "{@code PRIVATE}이면 공개 프로필이 즉시 404"라고 적은 것과 어긋나므로 첫 문장의 스냅샷을 고정한다(컨벤션
     * 8장, 리뷰 B-3).
     *
     * @param publicId {@code users.public_id}. UUID가 아니면 없는 사용자와 같게 404다
     * @param viewerId 로그인 사용자. 비회원이면 {@code null}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicProfileResponse getPublicProfile(String publicId, Long viewerId) {
        ProfileTarget target =
                parsePublicId(publicId)
                        .flatMap(publicProfileQueryRepository::findActiveTargetByPublicId)
                        .orElseThrow(PublicProfileService::notFound);
        if (!isSelf(viewerId, target)) {
            requireVisible(target);
            requireNotBlocked(viewerId, target);
        }
        return assemble(target);
    }

    /** 공개 범위와 차단을 건너뛰는 본인 조회인지. 비회원은 언제나 아니다. */
    private static boolean isSelf(Long viewerId, ProfileTarget target) {
        return viewerId != null && viewerId == target.userId();
    }

    private static void requireVisible(ProfileTarget target) {
        if (target.visibility() != Visibility.PUBLIC) {
            throw notFound();
        }
    }

    /**
     * 차단 관계면 404. 양방향 판정이므로 내가 차단한 상대와 나를 차단한 상대가 모두 걸린다.
     *
     * <p>판정은 계정 도메인의 공통 필터가 소유한다. 이 도메인이 {@code user_blocks}를 직접 읽지 않는다(FR-39).
     */
    private void requireNotBlocked(Long viewerId, ProfileTarget target) {
        if (viewerId != null && blockFilter.isBlocked(viewerId, target.userId())) {
            throw notFound();
        }
    }

    private PublicProfileResponse assemble(ProfileTarget target) {
        long userId = target.userId();
        FrameListResponse frames = frameQueryService.getRecentFrames(userId, RECENT_FRAME_LIMIT);
        PopcornBucketResponse popcorn = tasteQueryService.getPopcornBucket(userId);
        TasteAnalysisResponse taste = tasteQueryService.getTasteAnalysis(userId);
        return new PublicProfileResponse(
                new PublicProfileResponse.Profile(
                        userId,
                        target.nickname(),
                        avatarStorage.publicUrl(target.avatarStorageKey()),
                        target.visibility()),
                keywords(taste),
                new PublicProfileResponse.Stats(frames.totalCount(), popcorn.totalCount()),
                new PublicProfileResponse.Popcorn(flavors(popcorn)),
                recentFrames(frames),
                publicProfileQueryRepository.countPublicReviews(userId),
                // 차단 관계는 위에서 이미 404로 끝냈으므로 200 응답에서는 항상 false다.
                false);
    }

    private static List<PublicProfileResponse.Keyword> keywords(TasteAnalysisResponse taste) {
        return taste.keywords().stream()
                .map(keyword -> new PublicProfileResponse.Keyword(keyword.code(), keyword.label()))
                .toList();
    }

    /** 평가가 없는 기준 맛도 0개 버킷으로 남긴다. 팝콘 버킷(API-041)과 같은 맛 구성을 그려야 하기 때문이다. */
    private static List<PublicProfileResponse.FlavorBucket> flavors(PopcornBucketResponse popcorn) {
        return popcorn.flavors().stream()
                .map(
                        flavor ->
                                new PublicProfileResponse.FlavorBucket(
                                        flavor.flavorCode(),
                                        flavor.popcornCount(),
                                        flavor.averageScore()))
                .toList();
    }

    private static List<PublicProfileResponse.RecentFrame> recentFrames(FrameListResponse frames) {
        return frames.items().stream()
                .map(
                        item ->
                                new PublicProfileResponse.RecentFrame(
                                        item.frameId(),
                                        new PublicProfileResponse.Movie(
                                                item.movie().movieId(),
                                                item.movie().title(),
                                                item.movie().posterUrl()),
                                        item.score(),
                                        item.createdAt()))
                .toList();
    }

    /** 경로 값이 UUID가 아니면 조회 자체를 하지 않는다. 형식 오류와 없는 사용자를 응답에서 구분하지 않기 위해 400으로 올리지 않는다. */
    private static Optional<UUID> parsePublicId(String publicId) {
        try {
            return Optional.of(UUID.fromString(publicId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
