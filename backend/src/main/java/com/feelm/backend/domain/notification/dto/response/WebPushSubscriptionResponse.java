package com.feelm.backend.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "등록된 브라우저 Web Push 구독")
public record WebPushSubscriptionResponse(@Schema(example = "42") long subscriptionId) {}
