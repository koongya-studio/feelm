package com.feelm.backend.domain.report.service;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** 요청 본문과 기존 파일을 읽고 ImageIO로 검증하는 전체 업로드 처리 수를 제한한다. */
@Component
public class ReportImageUploadLimiter {

    private static final int MAX_CONCURRENT_UPLOADS = 2;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_UPLOADS, true);

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
