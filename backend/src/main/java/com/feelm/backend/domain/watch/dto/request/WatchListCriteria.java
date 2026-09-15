package com.feelm.backend.domain.watch.dto.request;

import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** API-034 쿼리를 강제 형변환 없이 검증하고 정규화한 조회 조건. */
public record WatchListCriteria(
        Set<WatchStatus> statuses, boolean confirmDue, String cursor, int size) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    public WatchListCriteria {
        statuses = statuses.isEmpty() ? Set.of() : Set.copyOf(statuses);
    }

    public static WatchListCriteria from(
            List<String> rawStatuses, String rawConfirmDue, String rawCursor, String rawSize) {
        return new WatchListCriteria(
                statuses(rawStatuses), confirmDue(rawConfirmDue), cursor(rawCursor), size(rawSize));
    }

    private static Set<WatchStatus> statuses(List<String> rawStatuses) {
        if (rawStatuses == null) {
            return Set.of();
        }
        if (rawStatuses.isEmpty()) {
            throw validationFailed();
        }
        EnumSet<WatchStatus> statuses = EnumSet.noneOf(WatchStatus.class);
        try {
            for (String rawStatus : rawStatuses) {
                if (rawStatus == null || rawStatus.isEmpty()) {
                    throw new IllegalArgumentException("empty status");
                }
                statuses.add(WatchStatus.valueOf(rawStatus));
            }
            return statuses;
        } catch (IllegalArgumentException exception) {
            throw validationFailed();
        }
    }

    private static boolean confirmDue(String rawConfirmDue) {
        if (rawConfirmDue == null) {
            return false;
        }
        if (rawConfirmDue.equals("true")) {
            return true;
        }
        if (rawConfirmDue.equals("false")) {
            return false;
        }
        throw validationFailed();
    }

    private static String cursor(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        if (rawCursor.isEmpty()) {
            throw validationFailed();
        }
        return rawCursor;
    }

    private static int size(String rawSize) {
        if (rawSize == null) {
            return DEFAULT_SIZE;
        }
        try {
            if (!rawSize.matches("[0-9]+")) {
                throw new NumberFormatException("size must be decimal digits");
            }
            int parsed = Integer.parseInt(rawSize);
            if (parsed < 1 || parsed > MAX_SIZE) {
                throw new NumberFormatException("size out of range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw validationFailed();
        }
    }

    private static BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
