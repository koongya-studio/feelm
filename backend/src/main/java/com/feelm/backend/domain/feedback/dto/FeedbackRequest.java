package com.feelm.backend.domain.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull Category category,
        Satisfaction satisfaction,
        @Size(max = 1000) String comment,
        @NotBlank @Size(max = 300) String screenPath,
        @Positive Long movieId,
        @Pattern(regexp = "[A-Z0-9_]{1,30}") String recommendationType) {

    public enum Category {
        GENERAL,
        RECOMMENDATION
    }

    public enum Satisfaction {
        GOOD,
        MIXED,
        BAD
    }
}
