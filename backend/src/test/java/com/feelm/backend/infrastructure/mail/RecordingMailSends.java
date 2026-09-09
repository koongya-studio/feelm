package com.feelm.backend.infrastructure.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 발송 구현 두 개가 공유하는 기록·제어 로직이다.
 *
 * <p>발송이 요청 스레드를 떠나면서(S15P21E106-282) 테스트가 발송 지점을 붙잡거나 실패시킬 수단이 필요해졌다. 두 스파이 빈이 같은 래치·카운터 코드를 각각 들고
 * 있지 않도록 여기에 모았다.
 */
public final class RecordingMailSends {

    /** 발송 지점을 붙잡는 래치가 열릴 때까지 기다리는 상한. 넘기면 테스트가 실패해야 하므로 예외로 끝낸다. */
    private static final long GATE_TIMEOUT_SECONDS = 10;

    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();
    private volatile CountDownLatch gate = openGate();

    /** 발송을 기록한다. 래치가 걸려 있으면 열릴 때까지 이 스레드에서 기다린다. */
    public void record(String token) {
        awaitGate();
        if (failures.getAndUpdate(remaining -> Math.max(remaining - 1, 0)) > 0) {
            throw new IllegalStateException("테스트가 지시한 발송 실패");
        }
        tokens.add(token);
    }

    public List<String> tokens() {
        return List.copyOf(tokens);
    }

    /** 기록, 지시한 실패, 걸어 둔 래치를 모두 되돌린다. */
    public void clear() {
        releaseSends();
        gate = openGate();
        failures.set(0);
        tokens.clear();
    }

    /**
     * 다음 발송들을 발송 지점에서 붙잡는다. 응답이 발송을 기다리지 않는다는 것을 확인하는 데 쓴다. 요청이 끝난 뒤 {@link #releaseSends()}로 풀어
     * 준다.
     */
    public void blockSends() {
        gate = new CountDownLatch(1);
    }

    public void releaseSends() {
        gate.countDown();
    }

    /** 다음 발송 한 건을 실패시킨다. 발송 실패가 응답과 이후 발송에 영향을 주지 않는지 확인하는 데 쓴다. */
    public void failNextSend() {
        failures.incrementAndGet();
    }

    private void awaitGate() {
        try {
            if (!gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("발송 래치가 " + GATE_TIMEOUT_SECONDS + "초 안에 열리지 않았다");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발송 대기가 중단됐다", interrupted);
        }
    }

    private static CountDownLatch openGate() {
        return new CountDownLatch(0);
    }
}
