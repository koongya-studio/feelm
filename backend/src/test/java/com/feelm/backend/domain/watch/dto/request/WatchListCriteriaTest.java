package com.feelm.backend.domain.watch.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("감상 기록 목록 query 검증")
class WatchListCriteriaTest {

    @Test
    @DisplayName("상태 중복을 제거하고 confirmDue=false·size=20을 기본 적용한다")
    void normalizesDefaultsAndDuplicateStatuses() {
        WatchListCriteria criteria =
                WatchListCriteria.from(List.of("RATED", "LINK_CLICKED", "RATED"), null, null, null);

        assertThat(criteria.statuses())
                .containsExactlyInAnyOrder(WatchStatus.RATED, WatchStatus.LINK_CLICKED);
        assertThat(criteria.confirmDue()).isFalse();
        assertThat(criteria.cursor()).isNull();
        assertThat(criteria.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("confirmDue는 정확한 소문자 true 또는 false만 허용한다")
    void requiresStrictLowercaseBoolean() {
        assertThat(WatchListCriteria.from(null, "true", null, null).confirmDue()).isTrue();
        assertThat(WatchListCriteria.from(null, "false", null, null).confirmDue()).isFalse();
        for (String invalid : List.of("TRUE", "False", "1", "", " true")) {
            assertValidation(() -> WatchListCriteria.from(null, invalid, null, null));
        }
    }

    @Test
    @DisplayName("상태·cursor·size의 빈 값과 범위 밖 값을 거절한다")
    void rejectsInvalidStatusCursorAndSize() {
        assertValidation(() -> WatchListCriteria.from(List.of(""), null, null, null));
        assertValidation(() -> WatchListCriteria.from(List.of("WATCHED"), null, null, null));
        assertValidation(() -> WatchListCriteria.from(null, null, "", null));
        for (String invalid : List.of("0", "51", "-1", "+1", "1.0", "size")) {
            assertValidation(() -> WatchListCriteria.from(null, null, null, invalid));
        }
        assertThat(WatchListCriteria.from(null, null, null, "1").size()).isEqualTo(1);
        assertThat(WatchListCriteria.from(null, null, null, "50").size()).isEqualTo(50);
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
