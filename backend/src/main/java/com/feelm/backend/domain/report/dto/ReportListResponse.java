package com.feelm.backend.domain.report.dto;

import com.feelm.backend.domain.report.model.ReportPeriod;
import java.time.Instant;
import java.util.List;

public record ReportListResponse(
        List<Item> items, Current current, boolean preparing, Generation generation) {

    public record Generation(
            int frameCount, int minimumFrames, boolean canGenerate, String reason) {}

    public record Item(
            long reportId, ReportPeriod period, String status, String title, Instant createdAt) {}

    public record Current(long reportId, ReportPeriod period, String status, Instant issuedAt) {}
}
