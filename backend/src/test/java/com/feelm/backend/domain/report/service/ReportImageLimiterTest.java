package com.feelm.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("리포트 이미지 I/O 동시성 제한")
class ReportImageLimiterTest {

    @Test
    @DisplayName("업로드 두 건이 처리 중이면 추가 요청은 429이고 완료 뒤 permit을 재사용한다")
    void uploadLimitIsBoundedAndReusable() throws Exception {
        ReportImageUploadLimiter limiter = new ReportImageUploadLimiter();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> running = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                running.add(
                        executor.submit(
                                () ->
                                        limiter.withPermit(
                                                () -> {
                                                    entered.countDown();
                                                    await(release);
                                                    return 1;
                                                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertRateLimited(() -> limiter.withPermit(() -> 2));
            release.countDown();
            for (Future<Integer> future : running) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            }
            assertThat(limiter.withPermit(() -> 3)).isEqualTo(3);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다운로드 여덟 건의 body 전송이 처리 중이면 추가 요청은 429다")
    void downloadLimitIsBoundedAndReusable() throws Exception {
        ReportImageDownloadLimiter limiter = new ReportImageDownloadLimiter();
        CountDownLatch entered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<Integer>> running = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                running.add(
                        executor.submit(
                                () ->
                                        limiter.withPermit(
                                                () -> {
                                                    entered.countDown();
                                                    await(release);
                                                    return 1;
                                                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertRateLimited(() -> limiter.withPermit(() -> 2));
            release.countDown();
            for (Future<Integer> future : running) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            }
            assertThat(limiter.withPermit(() -> 3)).isEqualTo(3);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertRateLimited(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting for test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test thread interrupted", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
