package com.feelm.backend.domain.report.dto;

import java.time.Instant;

public record ReportGenerateResponse(
        long reportId, Instant createdAt, boolean reused, Instant issuedAt, String reuseReason) {}
