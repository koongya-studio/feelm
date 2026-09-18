package com.feelm.backend.domain.notification.dto.response;

import com.feelm.backend.domain.notification.model.NotificationTargetType;
import com.feelm.backend.domain.notification.model.NotificationType;
import com.feelm.backend.domain.notification.repository.NotificationRepository.NotificationRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "인앱 알림 항목")
public record NotificationItemResponse(
        @Schema(example = "101") long notificationId,
        @Schema(example = "PARTY_INVITE") NotificationType type,
        @Schema(example = "파티 모드 초대") String title,
        @Schema(example = "새로운 파티 초대가 도착했어요.") String body,
        @Schema(example = "PARTY_INVITATION") NotificationTargetType targetType,
        @Schema(example = "501") long targetId,
        @Schema(nullable = true) Instant readAt,
        Instant notifiedAt) {

    public static NotificationItemResponse from(NotificationRow row) {
        return new NotificationItemResponse(
                row.notificationId(),
                row.type(),
                row.title(),
                row.body(),
                row.targetType(),
                row.targetId(),
                row.readAt(),
                row.notifiedAt());
    }
}
