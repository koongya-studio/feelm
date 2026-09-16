package com.feelm.backend.domain.social.cursor;

import com.feelm.backend.global.response.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** API-032의 영화·로그인 사용자 범위에 묶인 불투명 keyset cursor. */
public record PublicReviewCursor(Instant createdAt, long ratingId) {

    private static final Instant MIN_TIME = Instant.EPOCH;
    private static final Instant MAX_TIME = Instant.parse("2100-01-01T00:00:00Z");
    private static final String VERSION = "1";
    private static final String POLICY_VERSION = "active-public-own-block-v1";

    public PublicReviewCursor {
        if (createdAt == null || ratingId < 1) {
            throw new IllegalArgumentException("invalid public review cursor");
        }
        validateTime(createdAt);
    }

    public String encode(long movieId, long userId) {
        return CursorCodec.encode(
                VERSION + "|" + timeHead(createdAt) + "|" + scope(movieId, userId), ratingId);
    }

    public static PublicReviewCursor decode(String encoded, long movieId, long userId) {
        String expectedScope = scope(movieId, userId);
        return CursorCodec.decode(
                encoded,
                (head, id) -> {
                    String[] parts = head.split("\\|", -1);
                    if (parts.length != 3
                            || !parts[0].equals(VERSION)
                            || !parts[2].equals(expectedScope)) {
                        throw new IllegalArgumentException("cursor scope mismatch");
                    }
                    return new PublicReviewCursor(parseTime(parts[1]), id);
                });
    }

    private static String timeHead(Instant instant) {
        validateTime(instant);
        return "t"
                + instant.getEpochSecond()
                + "."
                + String.format(Locale.ROOT, "%06d", instant.getNano() / 1_000);
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
        if (!microsText.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("invalid cursor micros");
        }
        Instant instant = Instant.ofEpochSecond(epochSecond, Integer.parseInt(microsText) * 1_000L);
        validateTime(instant);
        return instant;
    }

    private static void validateTime(Instant instant) {
        if (instant.isBefore(MIN_TIME) || instant.isAfter(MAX_TIME)) {
            throw new IllegalArgumentException("cursor time out of range");
        }
    }

    private static String scope(long movieId, long userId) {
        if (movieId < 1 || userId < 1) {
            throw new IllegalArgumentException("cursor scope ids must be positive");
        }
        String canonical = VERSION + "|" + movieId + "|" + userId + "|" + POLICY_VERSION;
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
