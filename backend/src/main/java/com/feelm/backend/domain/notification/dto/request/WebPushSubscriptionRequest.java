package com.feelm.backend.domain.notification.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record WebPushSubscriptionRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @Schema(minimum = "1") @Positive Long expirationTime,
        @NotNull @Valid Keys keys) {

    public record Keys(
            @NotBlank @Size(max = 256) String p256dh, @NotBlank @Size(max = 128) String auth) {}
}
