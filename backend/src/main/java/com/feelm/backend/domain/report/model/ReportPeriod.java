package com.feelm.backend.domain.report.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public record ReportPeriod(int year, ReportHalf half) {

    public static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public ReportPeriod {
        if (year < 2000 || year > 9999) {
            throw new IllegalArgumentException("Report year is out of range");
        }
    }

    public static ReportPeriod containing(Instant instant) {
        ZonedDateTime koreaTime = instant.atZone(KOREA);
        int year = koreaTime.getYear();
        Instant firstHalfCutoff = cutoff(year, ReportHalf.H1);
        Instant secondHalfCutoff = cutoff(year, ReportHalf.H2);
        if (instant.isBefore(firstHalfCutoff)) {
            return new ReportPeriod(year, ReportHalf.H1);
        }
        if (instant.isBefore(secondHalfCutoff)) {
            return new ReportPeriod(year, ReportHalf.H2);
        }
        return new ReportPeriod(year + 1, ReportHalf.H1);
    }

    public Instant startInclusive() {
        return half == ReportHalf.H1
                ? cutoff(year - 1, ReportHalf.H2)
                : cutoff(year, ReportHalf.H1);
    }

    public Instant cutoffExclusive() {
        return cutoff(year, half);
    }

    public Instant publicationAt() {
        return half == ReportHalf.H1
                ? LocalDateTime.of(year, 7, 1, 0, 0).atZone(KOREA).toInstant()
                : LocalDateTime.of(year + 1, 1, 1, 0, 0).atZone(KOREA).toInstant();
    }

    public Instant nextPublicationAt() {
        return half == ReportHalf.H1
                ? LocalDateTime.of(year + 1, 1, 1, 0, 0).atZone(KOREA).toInstant()
                : LocalDateTime.of(year + 1, 7, 1, 0, 0).atZone(KOREA).toInstant();
    }

    public static ReportPeriod latestReleasedAt(Instant instant) {
        ZonedDateTime koreaTime = instant.atZone(KOREA);
        int year = koreaTime.getYear();
        return koreaTime.getMonthValue() >= 7
                ? new ReportPeriod(year, ReportHalf.H1)
                : new ReportPeriod(year - 1, ReportHalf.H2);
    }

    public boolean acceptsFinalSnapshotAt(Instant snapshotAt) {
        Instant cutoffAt = cutoffExclusive();
        return !snapshotAt.isBefore(cutoffAt)
                && !snapshotAt.isAfter(cutoffAt.plus(5, ChronoUnit.MINUTES));
    }

    public ReportPeriod previous() {
        return half == ReportHalf.H1
                ? new ReportPeriod(year - 1, ReportHalf.H2)
                : new ReportPeriod(year, ReportHalf.H1);
    }

    public long lockKey() {
        return year * 10L + (half == ReportHalf.H1 ? 1L : 2L);
    }

    private static Instant cutoff(int year, ReportHalf half) {
        LocalDateTime local =
                half == ReportHalf.H1
                        ? LocalDateTime.of(year, 6, 30, 23, 50)
                        : LocalDateTime.of(year, 12, 31, 23, 50);
        return local.atZone(KOREA).toInstant();
    }
}
