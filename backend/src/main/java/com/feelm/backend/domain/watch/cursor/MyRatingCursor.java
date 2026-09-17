package com.feelm.backend.domain.watch.cursor;

import com.feelm.backend.domain.watch.dto.request.MyRatingCriteria;
import com.feelm.backend.domain.watch.model.MyRatingItemType;
import com.feelm.backend.global.response.CursorCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** API-039의 사용자·필터·정렬 범위에 묶인 혼합 항목 keyset cursor. */
public record MyRatingCursor(
        Instant activityAt, BigDecimal score, MyRatingItemType itemType, long itemId) {

    private static final String VERSION = "1";
    private static final Instant MAX_TIME = Instant.parse("2100-01-01T00:00:00Z");

    public MyRatingCursor {
        if (itemId < 1 || itemType == null) {
            throw new IllegalArgumentException("invalid rating cursor id or type");
        }
        if (activityAt != null
                && (activityAt.isBefore(Instant.EPOCH)
                        || activityAt.isAfter(MAX_TIME)
                        || activityAt.getNano() % 1_000 != 0)) {
            throw new IllegalArgumentException("invalid rating cursor time");
        }
        if ((itemType == MyRatingItemType.UNRATED) != (score == null)) {
            throw new IllegalArgumentException("rating cursor score/type mismatch");
        }
        if (score != null
                && (score.compareTo(new BigDecimal("0.5")) < 0
                        || score.compareTo(new BigDecimal("5.0")) > 0
                        || score.remainder(new BigDecimal("0.5")).signum() != 0)) {
            throw new IllegalArgumentException("invalid rating cursor score");
        }
        if (itemType == MyRatingItemType.RATED && activityAt == null) {
            throw new IllegalArgumentException("rated cursor requires creation time");
        }
    }

    public String encode(long userId, MyRatingCriteria criteria) {
        String time =
                activityAt == null
                        ? "n"
                        : "t"
                                + activityAt.getEpochSecond()
                                + "."
                                + String.format(Locale.ROOT, "%06d", activityAt.getNano() / 1_000);
        String value =
                VERSION
                        + "|"
                        + time
                        + "|"
                        + (score == null ? "n" : score.toPlainString())
                        + "|"
                        + (itemType == MyRatingItemType.RATED ? "R" : "U")
                        + "|"
                        + scope(userId, criteria);
        return CursorCodec.encode(value, itemId);
    }

    public static MyRatingCursor decode(String encoded, long userId, MyRatingCriteria criteria) {
        String expectedScope = scope(userId, criteria);
        return CursorCodec.decode(
                encoded,
                (head, id) -> {
                    String[] parts = head.split("\\|", -1);
                    if (parts.length != 5
                            || !parts[0].equals(VERSION)
                            || !parts[4].equals(expectedScope)) {
                        throw new IllegalArgumentException("rating cursor scope mismatch");
                    }
                    Instant time = parseTime(parts[1]);
                    BigDecimal score = parts[2].equals("n") ? null : new BigDecimal(parts[2]);
                    MyRatingItemType itemType =
                            switch (parts[3]) {
                                case "R" -> MyRatingItemType.RATED;
                                case "U" -> MyRatingItemType.UNRATED;
                                default ->
                                        throw new IllegalArgumentException(
                                                "invalid rating cursor type");
                            };
                    return new MyRatingCursor(time, score, itemType, id);
                });
    }

    private static Instant parseTime(String text) {
        if (text.equals("n")) {
            return null;
        }
        if (!text.matches("t[0-9]{1,11}\\.[0-9]{6}")) {
            throw new IllegalArgumentException("invalid rating cursor time");
        }
        int dot = text.indexOf('.');
        return Instant.ofEpochSecond(
                Long.parseLong(text.substring(1, dot)),
                Long.parseLong(text.substring(dot + 1)) * 1_000);
    }

    private static String scope(long userId, MyRatingCriteria criteria) {
        if (userId < 1) {
            throw new IllegalArgumentException("invalid rating cursor user");
        }
        String canonical = VERSION + "|" + userId + "|" + criteria.filter() + "|" + criteria.sort();
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
