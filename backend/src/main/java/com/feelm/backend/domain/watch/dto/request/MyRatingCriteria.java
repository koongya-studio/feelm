package com.feelm.backend.domain.watch.dto.request;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;

/** API-039의 기본 평가 목록과 명시적 통합 피드를 구분하는 엄격한 조회 조건. */
public record MyRatingCriteria(Filter filter, Sort sort, String cursor, int size) {

    private static final int DEFAULT_SIZE = 20;

    public enum Filter {
        RATED,
        ALL,
        COMMENTED,
        UNRATED
    }

    public enum Sort {
        LATEST,
        SCORE_DESC,
        SCORE_ASC,
        OLDEST
    }

    public static MyRatingCriteria from(
            String rawFilter, String rawSort, String rawCursor, String rawSize) {
        Filter filter = Filter.RATED;
        if (rawFilter != null) {
            filter =
                    switch (rawFilter) {
                        case "ALL" -> Filter.ALL;
                        case "COMMENTED" -> Filter.COMMENTED;
                        case "UNRATED" -> Filter.UNRATED;
                        default -> throw validationFailed();
                    };
        }
        Sort sort = Sort.LATEST;
        if (rawSort != null) {
            try {
                sort = Sort.valueOf(rawSort);
            } catch (IllegalArgumentException exception) {
                throw validationFailed();
            }
        }
        if (rawCursor != null && rawCursor.isBlank()) {
            throw validationFailed();
        }
        int size = DEFAULT_SIZE;
        if (rawSize != null) {
            if (!rawSize.matches("(?:[1-9]|[1-4][0-9]|50)")) {
                throw validationFailed();
            }
            size = Integer.parseInt(rawSize);
        }
        return new MyRatingCriteria(filter, sort, rawCursor, size);
    }

    private static BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
