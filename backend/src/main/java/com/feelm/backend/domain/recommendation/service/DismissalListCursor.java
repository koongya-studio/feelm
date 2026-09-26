package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

/** API-087: 본인과 관심 없음 조회에 한정한 최근순 keyset cursor. */
public record DismissalListCursor(Instant createdAt, long id) {
    public String encode(long userId) {
        return CursorCodec.encode("dismissals1|" + userId + "|" + createdAt, id);
    }

    public static DismissalListCursor decode(String encoded, long userId) {
        return CursorCodec.decode(
                encoded,
                (head, id) -> {
                    String prefix = "dismissals1|" + userId + "|";
                    if (!head.startsWith(prefix)) {
                        throw new IllegalArgumentException("dismissal cursor scope mismatch");
                    }
                    // 공통 시각 범위 검사를 거쳐 DB에 전달한다.
                    return CursorCodec.decodeInstant(
                            CursorCodec.encode(head.substring(prefix.length()), id),
                            (time, itemId) -> {
                                if (time.getNano() % 1000 != 0) {
                                    throw new IllegalArgumentException("invalid cursor precision");
                                }
                                return new DismissalListCursor(time, itemId);
                            });
                });
    }
}
