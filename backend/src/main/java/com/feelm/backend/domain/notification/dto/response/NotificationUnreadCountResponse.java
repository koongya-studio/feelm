package com.feelm.backend.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "미확인 인앱 알림 개수")
public record NotificationUnreadCountResponse(@Schema(example = "2") long count) {}
