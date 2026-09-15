package com.feelm.backend.domain.watch.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "감상 확인 요청")
public record WatchConfirmRequest(
        @Schema(
                        description = "실제로 영화를 감상했는지 여부",
                        example = "true",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @JsonDeserialize(using = WatchedDeserializer.class)
                Boolean watched) {}
