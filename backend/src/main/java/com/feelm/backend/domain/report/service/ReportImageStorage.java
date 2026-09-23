package com.feelm.backend.domain.report.service;

public interface ReportImageStorage {

    int MAX_PNG_BYTES = 25 * 1024 * 1024;

    void validate(byte[] png);

    String store(long reportId, byte[] png);

    void restore(String storageKey, byte[] png);

    boolean exists(String storageKey);

    byte[] load(String storageKey);

    void delete(String storageKey);
}
