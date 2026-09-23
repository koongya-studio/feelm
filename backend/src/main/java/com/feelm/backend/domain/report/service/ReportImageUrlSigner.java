package com.feelm.backend.domain.report.service;

import java.time.Instant;

public interface ReportImageUrlSigner {

    SignedUrl issue(long imageId);

    boolean verify(long imageId, long expiresEpochSecond, String signature);

    record SignedUrl(String url, Instant expiresAt) {}
}
