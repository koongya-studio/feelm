package com.feelm.backend.domain.report.dto;

import java.time.Instant;

public record ReportIssueResponse(long reportId, Instant issuedAt) {}
