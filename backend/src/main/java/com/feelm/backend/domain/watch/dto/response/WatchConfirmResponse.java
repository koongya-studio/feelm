package com.feelm.backend.domain.watch.dto.response;

import com.feelm.backend.domain.watch.model.WatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "감상 확인 결과")
public record WatchConfirmResponse(
        @Schema(
                        description = "감상 기록 ID",
                        example = "5001",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long watchRecordId,
        @Schema(
                        description = "확인 후 상태",
                        allowableValues = {"WATCHED_UNRATED", "EXPIRED"},
                        requiredMode = Schema.RequiredMode.REQUIRED)
                WatchStatus status,
        @Schema(
                        description = "감상 완료 시각(UTC). 안 봤어요 응답이면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant watchedAt) {}
