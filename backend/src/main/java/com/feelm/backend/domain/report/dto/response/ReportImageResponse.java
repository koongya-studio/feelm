package com.feelm.backend.domain.report.dto.response;

import java.time.Instant;

public record ReportImageResponse(
        long imageId, String format, String downloadUrl, Instant expiresAt) {}
