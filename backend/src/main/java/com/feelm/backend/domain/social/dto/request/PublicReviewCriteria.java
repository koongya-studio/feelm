package com.feelm.backend.domain.social.dto.request;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;

/** API-032 query를 강제 형변환 없이 검증한 조회 조건. */
public record PublicReviewCriteria(String cursor, int size) {

    private static final int DEFAULT_SIZE = 20;

    public static PublicReviewCriteria from(String rawCursor, String rawSize) {
        return new PublicReviewCriteria(cursor(rawCursor), size(rawSize));
    }

    private static String cursor(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        if (rawCursor.isBlank()) {
            throw validationFailed();
        }
        return rawCursor;
    }

    private static int size(String rawSize) {
        if (rawSize == null) {
            return DEFAULT_SIZE;
        }
        if (!rawSize.matches("(?:[1-9]|[1-4][0-9]|50)")) {
            throw validationFailed();
        }
        return Integer.parseInt(rawSize);
    }

    private static BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
