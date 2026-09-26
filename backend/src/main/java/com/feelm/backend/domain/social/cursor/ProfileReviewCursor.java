package com.feelm.backend.domain.social.cursor;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

/** API-087 전용 커서. 영화별 커서와 호환되지 않으며 대상과 조회자에 묶인다. */
public record ProfileReviewCursor(Instant createdAt, long ratingId) {
    public ProfileReviewCursor {
        if (createdAt == null
                || createdAt.isBefore(Instant.EPOCH)
                || createdAt.isAfter(Instant.parse("2100-01-01T00:00:00Z"))
                || ratingId < 1) {
            throw new IllegalArgumentException("invalid profile review cursor");
        }
    }

    public String encode(long targetId, Long viewerId) {
        return CursorCodec.encode(scope(targetId, viewerId) + "|" + createdAt, ratingId);
    }

    public static ProfileReviewCursor decode(String encoded, long targetId, Long viewerId) {
        return CursorCodec.decode(
                encoded,
                (head, id) -> {
                    String prefix = scope(targetId, viewerId) + "|";
                    if (!head.startsWith(prefix))
                        throw new IllegalArgumentException("cursor scope mismatch");
                    return new ProfileReviewCursor(
                            Instant.parse(head.substring(prefix.length())), id);
                });
    }

    private static String scope(long targetId, Long viewerId) {
        return "profile-review-v1|" + targetId + "|" + (viewerId == null ? "anonymous" : viewerId);
    }
}
