package com.feelm.backend.domain.report.service;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** 하나의 서명 URL을 병렬 재생해 파일 decode부터 HTTP body 전송까지 쌓는 요청을 제한한다. */
@Component
public class ReportImageDownloadLimiter {

    private static final int MAX_CONCURRENT_DOWNLOADS = 8;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_DOWNLOADS, true);

    public <T> T withPermit(IOSupplier<T> action) throws IOException {
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    @FunctionalInterface
    public interface IOSupplier<T> {
        T get() throws IOException;
    }
}
