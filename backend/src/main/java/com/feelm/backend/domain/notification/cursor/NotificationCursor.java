package com.feelm.backend.domain.notification.cursor;

import com.feelm.backend.global.response.CursorCodec;
import java.time.Instant;

public record NotificationCursor(Instant notifiedAt, long notificationId) {

    public String encode() {
        return CursorCodec.encode(notifiedAt, notificationId);
    }

    public static NotificationCursor decode(String encoded) {
        return CursorCodec.decodeInstant(encoded, NotificationCursor::new);
    }
}
