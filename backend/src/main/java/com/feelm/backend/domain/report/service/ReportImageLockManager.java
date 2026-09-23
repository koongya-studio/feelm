package com.feelm.backend.domain.report.service;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 단일 서비스 노드에서 같은 리포트의 PNG 저장을 한 번으로 수렴시키는 고정 크기 striped lock. */
@Component
public class ReportImageLockManager {

    private static final int STRIPE_COUNT = 256;

    private final Lock[] locks = new Lock[STRIPE_COUNT];

    public ReportImageLockManager() {
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    public <T> T withLock(long reportId, Supplier<T> action) {
        Lock lock = locks[Math.floorMod(Long.hashCode(reportId), locks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
