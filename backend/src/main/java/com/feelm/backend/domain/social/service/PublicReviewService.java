package com.feelm.backend.domain.social.service;

import com.feelm.backend.domain.social.cursor.PublicReviewCursor;
import com.feelm.backend.domain.social.dto.request.PublicReviewCriteria;
import com.feelm.backend.domain.social.dto.response.PublicReviewItemResponse;
import com.feelm.backend.domain.social.dto.response.PublicReviewPageResponse;
import com.feelm.backend.domain.social.dto.response.PublicReviewUserResponse;
import com.feelm.backend.domain.social.repository.PublicReviewQueryRepository;
import com.feelm.backend.domain.social.repository.PublicReviewQueryRepository.PageSnapshot;
import com.feelm.backend.domain.social.repository.PublicReviewQueryRepository.PublicReviewRow;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublicReviewService {

    private final PublicReviewQueryRepository repository;

    @Transactional(readOnly = true)
    public PublicReviewPageResponse getReviews(
            long movieId, long viewerId, PublicReviewCriteria criteria) {
        PublicReviewCursor cursor = null;
        BusinessException cursorFailure = null;
        try {
            cursor = PublicReviewCursor.decode(criteria.cursor(), movieId, viewerId);
        } catch (BusinessException exception) {
            // 접근 오류가 잘못된 cursor보다 우선한다는 기존 응답 계약을 유지한다.
            cursorFailure = exception;
        }
        PageSnapshot snapshot =
                repository.findPageSnapshot(
                        movieId, viewerId, cursor, criteria.size() + 1, cursorFailure == null);
        if (!snapshot.access().viewerActive()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!snapshot.access().movieExists()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (cursorFailure != null) {
            throw cursorFailure;
        }

        List<PublicReviewRow> results = snapshot.reviews();
        boolean hasNext = results.size() > criteria.size();
        List<PublicReviewRow> page = hasNext ? results.subList(0, criteria.size()) : results;
        String nextCursor = null;
        if (hasNext) {
            PublicReviewRow last = page.getLast();
            nextCursor =
                    new PublicReviewCursor(last.createdAt(), last.ratingId())
                            .encode(movieId, viewerId);
        }

        List<PublicReviewItemResponse> items =
                page.stream().map(row -> response(row, viewerId)).toList();
        return new PublicReviewPageResponse(items, nextCursor, hasNext);
    }

    private static PublicReviewItemResponse response(PublicReviewRow row, long viewerId) {
        return new PublicReviewItemResponse(
                row.ratingId(),
                new PublicReviewUserResponse(row.userId(), row.nickname()),
                row.score(),
                row.comment(),
                row.userId() == viewerId,
                row.spoiler(),
                row.createdAt());
    }
}
