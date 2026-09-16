package com.feelm.backend.domain.watch.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.watch.model.RatingVisibility;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("평가 생성 요청 규칙")
class RatingCreateRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("0.5~5.0 범위의 0.5 단위 평점을 허용한다")
    void acceptsHalfStepScores() {
        for (String score : new String[] {"0.5", "1.0", "4.5", "5.0"}) {
            assertThat(violations(score, null, null)).isEmpty();
        }
    }

    @Test
    @DisplayName("범위 밖, 0.5 단위가 아닌 평점과 null을 거절한다")
    void rejectsInvalidScores() {
        for (String score : new String[] {"0", "4.49", "5.5"}) {
            assertThat(violations(score, null, null)).isNotEmpty();
        }
        assertThat(validator.validate(new RatingCreateRequest(null, null, null))).isNotEmpty();
    }

    @Test
    @DisplayName("한줄평이 있으면 공개 범위가 필수이고 200자까지 허용한다")
    void validatesCommentAndVisibilityTogether() {
        assertThat(violations("4.0", "a".repeat(200), RatingVisibility.PUBLIC)).isEmpty();
        assertThat(violations("4.0", "좋아요", null)).isNotEmpty();
        assertThat(violations("4.0", "a".repeat(201), RatingVisibility.PRIVATE)).isNotEmpty();
    }

    private Set<ConstraintViolation<RatingCreateRequest>> violations(
            String score, String comment, RatingVisibility visibility) {
        return validator.validate(
                new RatingCreateRequest(new BigDecimal(score), comment, visibility));
    }
}
