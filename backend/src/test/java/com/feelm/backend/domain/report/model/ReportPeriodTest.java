package com.feelm.backend.domain.report.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportPeriodTest {

    @Test
    @DisplayName("23시 50분 경계는 공백 없이 다음 반기로 이어진다")
    void cutoffWindows() {
        ReportPeriod firstHalf = new ReportPeriod(2026, ReportHalf.H1);
        ReportPeriod secondHalf = new ReportPeriod(2026, ReportHalf.H2);
        ReportPeriod nextFirstHalf = new ReportPeriod(2027, ReportHalf.H1);

        assertThat(firstHalf.startInclusive()).isEqualTo("2025-12-31T14:50:00Z");
        assertThat(firstHalf.cutoffExclusive()).isEqualTo("2026-06-30T14:50:00Z");
        assertThat(secondHalf.startInclusive()).isEqualTo(firstHalf.cutoffExclusive());
        assertThat(secondHalf.cutoffExclusive()).isEqualTo("2026-12-31T14:50:00Z");
        assertThat(nextFirstHalf.startInclusive()).isEqualTo(secondHalf.cutoffExclusive());

        assertThat(ReportPeriod.containing(Instant.parse("2026-06-30T14:49:59.999999Z")))
                .isEqualTo(firstHalf);
        assertThat(ReportPeriod.containing(Instant.parse("2026-06-30T14:50:00Z")))
                .isEqualTo(secondHalf);
        assertThat(ReportPeriod.containing(Instant.parse("2026-12-31T14:50:00Z")))
                .isEqualTo(nextFirstHalf);

        assertThat(firstHalf.acceptsFinalSnapshotAt(Instant.parse("2026-06-30T14:49:59.999999Z")))
                .isFalse();
        assertThat(firstHalf.acceptsFinalSnapshotAt(Instant.parse("2026-06-30T14:50:00Z")))
                .isTrue();
        assertThat(firstHalf.acceptsFinalSnapshotAt(Instant.parse("2026-06-30T14:55:00Z")))
                .isTrue();
        assertThat(firstHalf.acceptsFinalSnapshotAt(Instant.parse("2026-06-30T14:55:00.000001Z")))
                .isFalse();
        assertThat(secondHalf.acceptsFinalSnapshotAt(Instant.parse("2026-12-31T14:55:00Z")))
                .isTrue();
    }
}
