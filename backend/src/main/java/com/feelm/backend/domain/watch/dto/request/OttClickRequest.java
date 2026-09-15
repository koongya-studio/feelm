package com.feelm.backend.domain.watch.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "영화 OTT 링크 클릭 요청")
public record OttClickRequest(
        @Schema(
                        description = "영화에 연결된 OTT의 서비스 ID. 양의 64비트 정수",
                        example = "1",
                        minimum = "1",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull
                @Positive
                @JsonDeserialize(using = OttIdDeserializer.class)
                Long ottId) {}
