package com.feelm.backend.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "인앱 알림 커서 페이지")
public record NotificationPageResponse(
        List<NotificationItemResponse> items,
        @Schema(nullable = true) String nextCursor,
        boolean hasNext) {}
