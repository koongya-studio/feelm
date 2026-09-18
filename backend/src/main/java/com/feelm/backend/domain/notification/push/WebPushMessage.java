package com.feelm.backend.domain.notification.push;

import java.time.Instant;

public record WebPushMessage(
        long deliveryId,
        long notificationId,
        String endpoint,
        String p256dh,
        String auth,
        String payload,
        Instant expiresAt) {}
