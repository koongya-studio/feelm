package com.feelm.backend.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 평가 변경 이벤트의 payload다.
 *
 * <p>삭제는 점수를 싣지 않으며 Consumer는 action과 무관하게 PostgreSQL의 최신 평가를 다시 읽는다.
 */
public record RatingEventPayload(
        RatingAction action,
        Long movieId,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal score) {

    /** action에 따라 score의 필수 여부를 검증한다. */
    public RatingEventPayload {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(movieId, "movieId must not be null");
        if (action == RatingAction.DELETE && score != null) {
            throw new IllegalArgumentException("DELETE rating must not include score");
        }
        if (action != RatingAction.DELETE && score == null) {
            throw new IllegalArgumentException("CREATE or UPDATE rating must include score");
        }
    }
}
