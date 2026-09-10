package com.feelm.backend.domain.party.service;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

record PartyInvitationCursor(Instant createdAt, long invitationId) {
    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((createdAt + ":" + invitationId).getBytes(StandardCharsets.UTF_8));
    }

    static PartyInvitationCursor decode(String encoded) {
        if (encoded == null) return null;
        try {
            if (encoded.length() > 128) throw new IllegalArgumentException("cursor too long");
            String value =
                    new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf(':');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("invalid cursor");
            }
            Instant createdAt = Instant.parse(value.substring(0, separator));
            long invitationId = Long.parseLong(value.substring(separator + 1));
            if (invitationId < 1) throw new IllegalArgumentException("invalid invitation id");
            return new PartyInvitationCursor(createdAt, invitationId);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
