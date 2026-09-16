package com.feelm.backend.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.social.dto.request.PublicReviewCriteria;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("공개 한줄평 조회 조건")
class PublicReviewCriteriaTest {

    @Test
    @DisplayName("size 생략 시 기본값 20을 사용한다")
    void defaultsSizeToTwenty() {
        PublicReviewCriteria criteria = PublicReviewCriteria.from(null, null);

        assertThat(criteria.cursor()).isNull();
        assertThat(criteria.size()).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "50"})
    @DisplayName("최소·최대 page size를 허용한다")
    void acceptsBoundaryPageSizes(String rawSize) {
        assertThat(PublicReviewCriteria.from(null, rawSize).size())
                .isEqualTo(Integer.parseInt(rawSize));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "1.0", " ", "", "+1", "01", "999999999999999"})
    @DisplayName("범위 밖이거나 강제 변환이 필요한 size를 거절한다")
    void rejectsInvalidPageSizes(String rawSize) {
        assertValidationFailed(() -> PublicReviewCriteria.from(null, rawSize));
    }

    @Test
    @DisplayName("빈 cursor를 거절한다")
    void rejectsEmptyCursor() {
        assertValidationFailed(() -> PublicReviewCriteria.from("", null));
    }

    private static void assertValidationFailed(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        error ->
                                assertThat(((BusinessException) error).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
