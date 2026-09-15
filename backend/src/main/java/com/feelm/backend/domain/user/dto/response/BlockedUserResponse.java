package com.feelm.backend.domain.user.dto.response;

import com.feelm.backend.domain.user.entity.UserBlock;
import java.time.Instant;

/** API-022 차단 목록 항목. */
public record BlockedUserResponse(Long userId, String nickname, Instant blockedAt) {

    public static BlockedUserResponse from(UserBlock block) {
        return new BlockedUserResponse(
                block.getBlocked().getId(), block.getBlocked().getNickname(), block.getCreatedAt());
    }
}
