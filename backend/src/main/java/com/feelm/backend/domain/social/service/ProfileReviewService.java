package com.feelm.backend.domain.social.service;

import com.feelm.backend.domain.social.cursor.ProfileReviewCursor;
import com.feelm.backend.domain.social.dto.request.PublicReviewCriteria;
import com.feelm.backend.domain.social.dto.response.ProfileReviewPageResponse;
import com.feelm.backend.domain.social.repository.ProfileReviewQueryRepository;
import com.feelm.backend.domain.social.repository.PublicProfileQueryRepository;
import com.feelm.backend.domain.user.entity.Visibility;
import com.feelm.backend.domain.user.service.UserBlockFilter;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileReviewService {
    private final PublicProfileQueryRepository profiles;
    private final ProfileReviewQueryRepository reviews;
    private final UserBlockFilter blocks;

    /** 접근 판정과 목록을 같은 요청 스냅샷에서 읽는다. 다음 페이지는 최신 상태를 판정한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProfileReviewPageResponse getReviews(
            String publicId, Long viewerId, PublicReviewCriteria criteria) {
        if (viewerId != null && !reviews.isActiveViewer(viewerId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UUID id;
        try {
            id = UUID.fromString(publicId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var target =
                profiles.findActiveTargetByPublicId(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean self = viewerId != null && viewerId == target.userId();
        if (!self
                && (target.visibility() != Visibility.PUBLIC
                        || (viewerId != null && blocks.isBlocked(viewerId, target.userId())))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var cursor =
                criteria.cursor() == null
                        ? null
                        : ProfileReviewCursor.decode(criteria.cursor(), target.userId(), viewerId);
        if (!self && target.reviewVisibility() != Visibility.PUBLIC) {
            return new ProfileReviewPageResponse(List.of(), null, false);
        }
        var rows = reviews.findPage(target.userId(), cursor, criteria.size() + 1);
        boolean hasNext = rows.size() > criteria.size();
        var items = rows.subList(0, Math.min(rows.size(), criteria.size()));
        String next = null;
        if (hasNext) {
            var last = items.getLast();
            next =
                    new ProfileReviewCursor(last.createdAt(), last.ratingId())
                            .encode(target.userId(), viewerId);
        }
        return new ProfileReviewPageResponse(items, next, hasNext);
    }
}
