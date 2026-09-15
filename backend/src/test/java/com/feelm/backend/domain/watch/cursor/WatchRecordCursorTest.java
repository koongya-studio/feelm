package com.feelm.backend.domain.watch.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("감상 기록 cursor — 사용자·필터 범위와 nullable 시각")
class WatchRecordCursorTest {

    private static final long USER_ID = 10L;
    private static final Instant CLICKED_AT = Instant.parse("2026-09-15T01:23:45.123456Z");

    @Test
    @DisplayName("마이크로초 정렬 키를 같은 사용자·필터에서 왕복한다")
    void roundTripsMicrosWithinSameScope() {
        WatchListCriteria criteria = criteria(Set.of(WatchStatus.LINK_CLICKED), true, 20);
        WatchRecordCursor cursor = new WatchRecordCursor(CLICKED_AT, 101L);

        WatchRecordCursor decoded =
                WatchRecordCursor.decode(cursor.encode(USER_ID, criteria), USER_ID, criteria);

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    @DisplayName("clickedAt null cursor를 지원하고 페이지 크기 변경은 허용한다")
    void supportsNullTimeAndSizeChange() {
        WatchListCriteria first = criteria(Set.of(WatchStatus.RATED), false, 1);
        WatchListCriteria next = criteria(Set.of(WatchStatus.RATED), false, 50);
        String encoded = new WatchRecordCursor(null, 77L).encode(USER_ID, first);

        assertThat(WatchRecordCursor.decode(encoded, USER_ID, next))
                .isEqualTo(new WatchRecordCursor(null, 77L));
    }

    @Test
    @DisplayName("손상·사용자 불일치·필터 불일치 cursor는 공통 400 오류다")
    void rejectsDamagedOrOutOfScopeCursor() {
        WatchListCriteria criteria = criteria(Set.of(WatchStatus.LINK_CLICKED), false, 20);
        String encoded = new WatchRecordCursor(CLICKED_AT, 101L).encode(USER_ID, criteria);

        assertValidation(() -> WatchRecordCursor.decode("not-base64!", USER_ID, criteria));
        assertValidation(() -> WatchRecordCursor.decode(encoded, USER_ID + 1, criteria));
        assertValidation(
                () ->
                        WatchRecordCursor.decode(
                                encoded,
                                USER_ID,
                                criteria(Set.of(WatchStatus.EXPIRED), false, 20)));
        assertValidation(
                () ->
                        WatchRecordCursor.decode(
                                encoded,
                                USER_ID,
                                criteria(Set.of(WatchStatus.LINK_CLICKED), true, 20)));
    }

    private static WatchListCriteria criteria(
            Set<WatchStatus> statuses, boolean confirmDue, int size) {
        return new WatchListCriteria(statuses, confirmDue, null, size);
    }

    private static void assertValidation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
