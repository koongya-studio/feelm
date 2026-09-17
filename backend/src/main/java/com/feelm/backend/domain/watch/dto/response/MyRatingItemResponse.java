package com.feelm.backend.domain.watch.dto.response;

import com.feelm.backend.domain.watch.model.MyRatingItemType;
import com.feelm.backend.domain.watch.model.RatingVisibility;
import com.feelm.backend.domain.watch.repository.MyRatingQueryRepository.MyRatingRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "본인 평가 또는 감상 후 미평가 영화")
public record MyRatingItemResponse(
        @Schema(description = "평가 여부", requiredMode = Schema.RequiredMode.REQUIRED)
                MyRatingItemType itemType,
        @Schema(
                        description = "평가 ID. 미평가이면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Long ratingId,
        @Schema(description = "영화 요약", requiredMode = Schema.RequiredMode.REQUIRED) Movie movie,
        @Schema(
                        description = "본인 별점. 미평가이면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal score,
        @Schema(
                        description = "본인 한줄평 원문. 없으면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String comment,
        @Schema(
                        description = "한줄평 공개 범위. 없으면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                RatingVisibility visibility,
        @Schema(
                        description = "스포일러 여부. 미평가이면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Boolean spoiler,
        @Schema(
                        description = "프레임 ID. 없으면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Long frameId,
        @Schema(
                        description = "평가 생성 시각(UTC). 미평가이면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant createdAt,
        @Schema(
                        description = "목록 정렬 시각(UTC). 이력이 없으면 null",
                        nullable = true,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Instant activityAt) {

    public static MyRatingItemResponse from(MyRatingRow row) {
        return new MyRatingItemResponse(
                row.itemType(),
                row.ratingId(),
                new Movie(row.movieId(), row.title(), row.posterUrl()),
                row.score(),
                row.comment(),
                row.visibility() == null ? null : RatingVisibility.valueOf(row.visibility()),
                row.spoiler(),
                row.frameId(),
                row.createdAt(),
                row.activityAt());
    }

    @Schema(description = "평가 피드의 영화 요약")
    public record Movie(
            @Schema(description = "서비스 영화 ID", requiredMode = Schema.RequiredMode.REQUIRED)
                    long movieId,
            @Schema(description = "영화 제목", requiredMode = Schema.RequiredMode.REQUIRED)
                    String title,
            @Schema(
                            description = "포스터 URL. 없으면 null",
                            nullable = true,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String posterUrl) {}
}
