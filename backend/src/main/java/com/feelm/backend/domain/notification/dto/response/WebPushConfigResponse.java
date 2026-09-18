package com.feelm.backend.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "브라우저 Web Push 공개 설정")
public record WebPushConfigResponse(boolean enabled, @Schema(nullable = true) String publicKey) {}
