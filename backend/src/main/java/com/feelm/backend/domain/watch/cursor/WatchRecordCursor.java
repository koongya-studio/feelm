package com.feelm.backend.domain.watch.cursor;

import com.feelm.backend.domain.watch.dto.request.WatchListCriteria;
import com.feelm.backend.global.response.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

/** API-034의 사용자·필터 범위에 묶인 불투명 keyset cursor. */
public record WatchRecordCursor(Instant clickedAt, long watchRecordId) {

    private static final Instant MIN_TIME = Instant.EPOCH;
    private static final Instant MAX_TIME = Instant.parse("2100-01-01T00:00:00Z");
    private static final String VERSION = "1";

    public String encode(long userId, WatchListCriteria criteria) {
        String time = clickedAt == null ? "n" : timeHead(clickedAt);
        return CursorCodec.encode(
                VERSION + "|" + time + "|" + scope(userId, criteria), watchRecordId);
    }

    public static WatchRecordCursor decode(
            String encoded, long userId, WatchListCriteria criteria) {
        String expectedScope = scope(userId, criteria);
        return CursorCodec.decode(
                encoded,
                (head, id) -> {
                    String[] parts = head.split("\\|", -1);
                    if (parts.length != 3
                            || !parts[0].equals(VERSION)
                            || !parts[2].equals(expectedScope)) {
                        throw new IllegalArgumentException("cursor scope mismatch");
                    }
                    Instant clickedAt = parts[1].equals("n") ? null : parseTime(parts[1]);
                    return new WatchRecordCursor(clickedAt, id);
                });
    }

    private static String timeHead(Instant instant) {
        validateTime(instant);
        return "t" + instant.getEpochSecond() + "." + instant.getNano() / 1_000;
    }

    private static Instant parseTime(String value) {
        if (!value.startsWith("t")) {
            throw new IllegalArgumentException("invalid cursor time");
        }
        int separator = value.indexOf('.', 1);
        if (separator < 2 || separator == value.length() - 1) {
            throw new IllegalArgumentException("invalid cursor time");
        }
        long epochSecond = Long.parseLong(value.substring(1, separator));
        String microsText = value.substring(separator + 1);
        if (!microsText.matches("[0-9]{1,6}")) {
            throw new IllegalArgumentException("invalid cursor micros");
        }
        int micros = Integer.parseInt(microsText);
        Instant instant = Instant.ofEpochSecond(epochSecond, micros * 1_000L);
        validateTime(instant);
        return instant;
    }

    private static void validateTime(Instant instant) {
        if (instant.isBefore(MIN_TIME) || instant.isAfter(MAX_TIME)) {
            throw new IllegalArgumentException("cursor time out of range");
        }
    }

    private static String scope(long userId, WatchListCriteria criteria) {
        String statuses =
                criteria.statuses().stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(Collectors.joining(","));
        String canonical = VERSION + "|" + userId + "|" + statuses + "|" + criteria.confirmDue();
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(Arrays.copyOf(digest, 12));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
