package com.feelm.backend.domain.user.dto.response;

import com.feelm.backend.domain.user.entity.UserBlock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** API-020 사용자 차단 응답. */
public record UserBlockResponse(Long blockedUserId, Instant blockedAt) {

    public static UserBlockResponse from(UserBlock block) {
        // 저장 직후에는 JVM 시계가 만든 값을 쓴다. 목록(API-022)이 DB에서 다시 읽는 timestamptz와 같은 정밀도로 맞춘다.
        return new UserBlockResponse(
                block.getBlocked().getId(), block.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
    }
}
