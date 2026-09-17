package com.feelm.backend.domain.watch.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.feelm.backend.domain.watch.model.RatingVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = RatingUpdateRequestDeserializer.class)
public record RatingUpdateRequest(
        @Schema(minimum = "0.5", maximum = "5.0", multipleOf = 0.5)
                @NotNull
                @DecimalMin("0.5")
                @DecimalMax("5.0")
                BigDecimal score,
        @Size(max = 200) String comment,
        RatingVisibility visibility,
        @Schema(
                        description = "한줄평 스포일러 여부. 생략 시 false",
                        defaultValue = "false",
                        requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                Boolean spoiler) {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    public RatingUpdateRequest {
        if (spoiler == null) {
            spoiler = false;
        }
    }

    @JsonIgnore
    @AssertTrue(message = "score는 0.5점 단위여야 합니다.")
    public boolean isScoreStepValid() {
        return score == null || score.remainder(HALF).compareTo(BigDecimal.ZERO) == 0;
    }

    @JsonIgnore
    @AssertTrue(message = "comment가 있으면 visibility가 필요합니다.")
    public boolean isCommentVisibilityValid() {
        return comment == null || visibility != null;
    }

    @JsonIgnore
    @AssertTrue(message = "스포일러 한줄평에는 내용이 필요합니다.")
    public boolean isSpoilerCommentValid() {
        return !spoiler
                || comment != null && comment.codePoints().anyMatch(RatingUpdateRequest::isVisible);
    }

    private static boolean isVisible(int codePoint) {
        return !Character.isISOControl(codePoint)
                && Character.getType(codePoint) != Character.FORMAT
                && !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint);
    }
}
