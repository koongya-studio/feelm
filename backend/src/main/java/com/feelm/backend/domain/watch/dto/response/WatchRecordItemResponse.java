package com.feelm.backend.domain.watch.dto.response;

import com.feelm.backend.domain.watch.model.WatchStatus;
import com.feelm.backend.domain.watch.repository.WatchRepository.WatchRecordListRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "감상 기록 목록 항목")
public record WatchRecordItemResponse(
        @Schema(
                        description = "감상 기록 ID",
                        example = "5001",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                long watchRecordId,
        @Schema(description = "영화 요약", requiredMode = Schema.RequiredMode.REQUIRED)
                MovieSummary movie,
        @Schema(
                        description = "감상 상태",
                        allowableValues = {"LINK_CLICKED", "WATCHED_UNRATED", "RATED", "EXPIRED"},
                        requiredMode = Schema.RequiredMode.REQUIRED)
                WatchStatus status,
        @Schema(
                        description = "클릭한 OTT 요약. 클릭 이력이 없으면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                OttSummary ott,
        @Schema(
                        description = "OTT 클릭 시각(UTC)",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant clickedAt,
        @Schema(
                        description = "감상 확인 노출 시각(UTC, clickedAt + 2일)",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant confirmDueAt,
        @Schema(
                        description = "감상 기록 만료 시각(UTC, clickedAt + 7일)",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant expiresAt,
        @Schema(
                        description = "감상 완료 시각(UTC)",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant watchedAt,
        @Schema(
                        description = "연결된 평가 ID. 평가가 없으면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Long ratingId) {

    public static WatchRecordItemResponse from(WatchRecordListRow row) {
        OttSummary ott = row.ottId() == null ? null : new OttSummary(row.ottId(), row.ottCode());
        return new WatchRecordItemResponse(
                row.watchRecordId(),
                new MovieSummary(row.movieId(), row.movieTitle(), row.posterUrl()),
                row.status(),
                ott,
                row.clickedAt(),
                row.confirmDueAt(),
                row.expiresAt(),
                row.watchedAt(),
                row.ratingId());
    }

    @Schema(description = "감상 기록의 영화 요약")
    public record MovieSummary(
            @Schema(
                            description = "서비스 영화 ID",
                            example = "550",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    long movieId,
            @Schema(
                            description = "영화 제목",
                            example = "파이트 클럽",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String title,
            @Schema(
                            description = "포스터 URL",
                            nullable = true,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String posterUrl) {}

    @Schema(description = "클릭한 OTT 요약")
    public record OttSummary(
            @Schema(
                            description = "서비스 OTT ID",
                            example = "1",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    long ottId,
            @Schema(
                            description = "OTT 코드",
                            example = "NETFLIX",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String code) {}
}
