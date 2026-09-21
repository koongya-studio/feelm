package com.feelm.backend.domain.watch.dto.response;

import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.domain.watch.repository.WatchRepository.WatchRecordSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "OTT 클릭으로 처리된 감상 기록")
public record OttClickResponse(
        @Schema(
                        description = "처리된 감상 기록 ID",
                        example = "5001",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long watchRecordId,
        @Schema(
                        description = "서비스 영화 ID",
                        example = "550",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long movieId,
        @Schema(
                        description = "현재 감상 상태",
                        allowableValues = {"LINK_CLICKED", "WATCHED_UNRATED", "RATED"},
                        requiredMode = Schema.RequiredMode.REQUIRED)
                WatchStatus status,
        @Schema(
                        description = "최근 OTT 클릭 시각(UTC). 클릭 이력 없는 평가 기록이면 null",
                        example = "2026-08-30T05:21:00Z",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant clickedAt,
        @Schema(
                        description = "감상 확인 가능 시각(UTC, clickedAt과 같다). 클릭 이력이 없으면 null",
                        example = "2026-08-30T05:21:00Z",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant confirmDueAt,
        @Schema(
                        description = "감상 기록 만료 시각(UTC, clickedAt + 7일). 클릭 이력이 없으면 null",
                        example = "2026-09-06T05:21:00Z",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant expiresAt) {

    public static OttClickResponse from(WatchRecordSnapshot record) {
        return new OttClickResponse(
                record.watchRecordId(),
                record.movieId(),
                record.status(),
                record.clickedAt(),
                record.confirmDueAt(),
                record.expiresAt());
    }
}
