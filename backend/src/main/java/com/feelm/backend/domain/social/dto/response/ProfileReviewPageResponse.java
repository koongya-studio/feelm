package com.feelm.backend.domain.social.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "사용자별 공개 한줄평 커서 페이지")
public record ProfileReviewPageResponse(List<Item> items, String nextCursor, boolean hasNext) {
    @Schema(name = "ProfileReviewItem", description = "사용자별 공개 한줄평 항목")
    public record Item(
            long ratingId,
            Movie movie,
            BigDecimal score,
            String comment,
            boolean spoiler,
            Instant createdAt) {}

    @Schema(name = "ProfileReviewMovie", description = "한줄평 영화 요약")
    public record Movie(long movieId, String title, String posterUrl) {}
}
