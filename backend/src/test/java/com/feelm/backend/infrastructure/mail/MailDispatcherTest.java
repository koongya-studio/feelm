package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.feelm.backend.support.SharedTestRuntime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 큐가 찼을 때와 발송이 실패했을 때의 디스패처 동작을 검증한다(S15P21E106-282).
 *
 * <p>이 두 경로는 계약 테스트로 만들 수 없다. 큐 포화는 스레드와 큐를 1로 줄인 실행기에서만 재현되고, 포화·실패의 결과는 응답이 아니라 로그와 "요청 스레드에서
 * 실행되지 않는다"는 사실이기 때문이다.
 *
 * <p>포화 경고의 창은 시간 기준이라 시계를 테스트가 쥐고 있어야 한다. 실제 시각을 쓰면 창이 닫히는 구간(1분)을 테스트가 기다려야 하고, 반대로 창 안에서만 보면 회복
 * 요약을 볼 수 없다.
 */
@DisplayName("계정 메일 디스패처 — 포화와 실패")
@ExtendWith(OutputCaptureExtension.class)
@ResourceLock("backend-output-capture")
@SharedTestRuntime
class MailDispatcherTest {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(5);
    private static final String SATURATION_LOG = "메일 발송 큐가 가득 차";
    private static final String SHUTDOWN_LOG = "애플리케이션이 종료 중이어서";

    private final MovableClock clock = new MovableClock();

    private final RecordingBudget budget = new RecordingBudget();

    @Test
    @DisplayName("큐가 차면 발송을 버리고 호출 스레드에서 대신 실행하지 않는다")
    void rejectedSendIsNotRunOnCallerThread() {
        ThreadPoolTaskExecutor executor = singleSlotExecutor();
        try {
            MailDispatcher dispatcher = dispatcher(executor);
            CountDownLatch gate = new CountDownLatch(1);
            List<String> senderThreads = new CopyOnWriteArrayList<>();
            String callerThread = Thread.currentThread().getName();

            dispatch(dispatcher, 1L, senderThreads, gate);
            dispatch(dispatcher, 2L, senderThreads, null);
            dispatch(dispatcher, 3L, senderThreads, null);

            assertThat(senderThreads).as("호출 스레드는 발송을 실행하지 않는다").doesNotContain(callerThread);

            gate.countDown();
            awaitIdle(dispatcher);

            assertThat(senderThreads).as("스레드 1개 + 큐 1칸이므로 세 번째는 버려진다").hasSize(2);
            assertThat(senderThreads).doesNotContain(callerThread);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("포화 경고는 구간마다 한 번이고 유실 건수는 구간이 끝날 때 한 번 남는다")
    void saturationLogsOncePerWindow(CapturedOutput output) {
        ThreadPoolTaskExecutor executor = singleSlotExecutor();
        try {
            MailDispatcher dispatcher = dispatcher(executor);
            CountDownLatch gate = new CountDownLatch(1);
            List<String> senderThreads = new CopyOnWriteArrayList<>();

            dispatch(dispatcher, 1L, senderThreads, gate);
            dispatch(dispatcher, 2L, senderThreads, null);
            for (long userId = 3; userId <= 5; userId++) {
                dispatch(dispatcher, userId, senderThreads, null);
            }

            assertThat(countOccurrences(output.toString(), SATURATION_LOG))
                    .as("거절 3건에 경고는 1건")
                    .isEqualTo(1);

            gate.countDown();
            awaitIdle(dispatcher);
            clock.advance(MailDispatcher.SATURATION_WARN_INTERVAL);
            dispatch(dispatcher, 6L, senderThreads, null);
            awaitIdle(dispatcher);

            assertThat(output).contains("그동안 발송 3건을 버렸습니다");
            assertThat(countOccurrences(output.toString(), SATURATION_LOG))
                    .as("회복 뒤에도 포화 경고가 늘지 않는다")
                    .isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 리뷰 F1·B-1의 회귀 방지 장치다. 포화는 큐가 한 칸씩 열리는 진동이므로 성공 제출 한 건으로 구간을 닫으면 진입·회복 경고가 번갈아 쌓인다. 프로브 재현값은
     * 유실 169건에 WARN 35줄이었다.
     */
    @Test
    @DisplayName("거절과 성공이 번갈아 일어나도 경고는 구간당 한 번이다")
    void alternatingRejectionsStillWarnOncePerWindow(CapturedOutput output) {
        AtomicBoolean reject = new AtomicBoolean(true);
        MailDispatcher dispatcher =
                new MailDispatcher(drainingExecutor(reject), () -> false, clock, budget);

        for (int attempt = 0; attempt < 20; attempt++) {
            dispatcher.dispatch(MailKind.VERIFICATION_CODE, (long) attempt, () -> {});
            reject.set(!reject.get());
        }

        assertThat(countOccurrences(output.toString(), SATURATION_LOG))
                .as("거절 10건 + 성공 10건이 번갈아 일어나도 경고는 1건")
                .isEqualTo(1);
        assertThat(output).as("구간 안에서는 회복을 선언하지 않는다").doesNotContain("포화가 풀렸습니다");

        clock.advance(MailDispatcher.SATURATION_WARN_INTERVAL);
        reject.set(false);
        dispatcher.dispatch(MailKind.VERIFICATION_CODE, 99L, () -> {});

        assertThat(output).as("구간이 끝나면 유실 총량을 한 줄로 남긴다").contains("그동안 발송 10건을 버렸습니다");
    }

    /** 리뷰 F3. 종료 창의 거절은 큐가 찬 것이 아니라 실행기가 닫히는 중이라는 뜻이다. */
    @Test
    @DisplayName("종료 중의 거절은 큐 포화가 아니라 종료로 기록된다")
    void rejectionWhileShuttingDownIsNotReportedAsSaturation(CapturedOutput output) {
        MailDispatcher dispatcher =
                new MailDispatcher(rejectingExecutor(), () -> true, clock, budget);

        dispatcher.dispatch(MailKind.PASSWORD_RESET, 7L, () -> {});

        assertThat(output).contains(SHUTDOWN_LOG, "사용자 7", "재요청(API-010)");
        assertThat(output).doesNotContain(SATURATION_LOG);
        assertThat(dispatcher.isIdle()).as("거절된 발송이 미완료로 남지 않는다").isTrue();
    }

    /** 리뷰 F7. 거절이 아닌 제출 실패도 커밋 호출자로 전파되면 201·202가 500이 된다. */
    @Test
    @DisplayName("거절이 아닌 제출 실패도 호출자에게 전파되지 않는다")
    void submissionFailureOtherThanRejectionIsNotPropagated(CapturedOutput output) {
        TaskExecutor broken =
                task -> {
                    throw new IllegalStateException("ThreadPoolTaskExecutor not initialized");
                };
        MailDispatcher dispatcher = new MailDispatcher(broken, () -> false, clock, budget);

        assertThatCode(() -> dispatcher.dispatch(MailKind.VERIFICATION_CODE, 8L, () -> {}))
                .doesNotThrowAnyException();

        assertThat(output).contains("발송 스레드에 넘기지 못했습니다", "사용자 8", "인증번호 재요청(API-078)");
        assertThat(output).doesNotContain(SATURATION_LOG);
        assertThat(dispatcher.isIdle()).isTrue();
    }

    /**
     * 리뷰 F5·B-4. 실제 실패 경로는 수신 주소를 예외 메시지에 담는다({@code MailSendException} 안의 {@code 550 ... 주소}). 예외를
     * 로거에 그대로 넘기면 그 메시지가 스택 트레이스와 함께 남는다.
     */
    @Test
    @DisplayName("발송 실패는 호출자에게 전파되지 않고 수신 주소를 가린 채 기록된다")
    void sendFailureIsLoggedWithoutEmailOrToken(CapturedOutput output) {
        MailDispatcher dispatcher =
                new MailDispatcher(new SyncTaskExecutor(), () -> false, clock, budget);

        dispatcher.dispatch(
                MailKind.PASSWORD_RESET,
                42L,
                () -> {
                    throw new MailSendException(
                            "Failed messages: Invalid Addresses",
                            new IllegalStateException("550 5.1.1 <user@example.com> not found"));
                });

        assertThat(dispatcher.isIdle()).isTrue();
        assertThat(output).contains("비밀번호 재설정 메일 발송에 실패했습니다", "사용자 42", "재요청(API-010)");
        assertThat(output).as("주소는 가리고 원인 사슬과 응답 코드는 남긴다").contains("550 5.1.1", "us***@ex***");
        assertThat(output).doesNotContain("user@example.com", "raw-token");
    }

    /**
     * 리뷰 2. 원인 하나의 메시지만으로도 길이 상한을 넘길 수 있어, 원인 깊이만 세는 루프 조건으로는 로그 한 줄이 묶이지 않는다. 자르는 시점이 가림 처리 뒤라는 것도
     * 함께 고정한다.
     */
    @Test
    @DisplayName("아주 긴 실패 메시지는 상한에서 잘리고 잘린 뒤에도 주소는 남지 않는다")
    void longFailureMessageIsTruncated(CapturedOutput output) {
        MailDispatcher dispatcher =
                new MailDispatcher(new SyncTaskExecutor(), () -> false, clock, budget);
        String longMessage = "x".repeat(5000) + " 550 5.1.1 <user@example.com> not found";

        dispatcher.dispatch(
                MailKind.VERIFICATION_CODE,
                43L,
                () -> {
                    throw new MailSendException(longMessage);
                });

        assertThat(output).contains("인증번호 메일 발송에 실패했습니다", "…(이하 생략)");
        assertThat(output.toString()).as("상한을 넘긴 꼬리는 로그에 남지 않는다").doesNotContain("x".repeat(1100));
        assertThat(output).doesNotContain("user@example.com");
    }

    /**
     * 전역 발송 상한을 넘긴 메일은 보내지 않는다(S15P21E106-287 결정 D-6). 상한 확인이 발송 스레드에 있으므로 응답 경로에는 아무 영향이 없고, 버려진
     * 발송이 예외로 새어 나가지도 않는다.
     */
    @Test
    @DisplayName("전역 발송 상한을 넘기면 발송하지 않고 조용히 버린다")
    void skipsSendWhenBudgetRefuses(CapturedOutput output) {
        MailDispatcher dispatcher =
                new MailDispatcher(new SyncTaskExecutor(), () -> false, clock, budget);
        budget.refuse();
        AtomicBoolean sent = new AtomicBoolean();

        assertThatCode(
                        () ->
                                dispatcher.dispatch(
                                        MailKind.VERIFICATION_CODE, 11L, () -> sent.set(true)))
                .doesNotThrowAnyException();

        assertThat(sent).as("상한을 넘긴 메일은 발송하지 않는다").isFalse();
        assertThat(dispatcher.isIdle()).as("버려진 발송이 미완료로 남지 않는다").isTrue();
        assertThat(output).as("버려지는 메일마다 줄을 남기지 않는다").doesNotContain("발송에 실패했습니다");
    }

    /**
     * 예산은 실제 발송만 센다(결정 D-1). 큐가 차서 버려진 발송이 예산을 소모하면, 보내지도 않은 메일 때문에 상한이 마르는 옛 문제가 다른 자리에서 되살아난다.
     */
    @Test
    @DisplayName("큐에서 버려진 발송은 전역 예산을 소모하지 않는다")
    void rejectedSubmissionDoesNotConsumeBudget() {
        MailDispatcher dispatcher =
                new MailDispatcher(rejectingExecutor(), () -> false, clock, budget);

        dispatcher.dispatch(MailKind.VERIFICATION_CODE, 12L, () -> {});

        assertThat(budget.consumed()).as("제출되지 못한 발송의 예산 소모").isZero();
    }

    /** 상한 확인이 실패하면 보내지 않는다(fail-closed, 결정 D-11). 원인은 발송 실패와 같은 자리에 남는다. */
    @Test
    @DisplayName("상한을 확인하지 못하면 발송하지 않고 원인을 남긴다")
    void doesNotSendWhenBudgetCheckFails(CapturedOutput output) {
        MailDispatcher dispatcher =
                new MailDispatcher(new SyncTaskExecutor(), () -> false, clock, budget);
        budget.fail();
        AtomicBoolean sent = new AtomicBoolean();

        assertThatCode(
                        () ->
                                dispatcher.dispatch(
                                        MailKind.PASSWORD_RESET, 13L, () -> sent.set(true)))
                .doesNotThrowAnyException();

        assertThat(sent).isFalse();
        assertThat(output).contains("비밀번호 재설정 메일 발송 상한을 확인하지 못해 보내지 않았습니다", "사용자 13");
        assertThat(dispatcher.isIdle()).isTrue();
    }

    /**
     * 이 상한이 지키는 것은 SMTP 계정의 쿼터이고 실패한 발송은 쿼터를 쓰지 않는다(리뷰 B-4). 되돌리지 않으면 자격 증명 폐기처럼 즉시 실패하는 장애에서 상한이 몇
     * 초 만에 마르고, 원인을 고쳐도 창이 끝날 때까지 메일이 나가지 않는다.
     */
    @Test
    @DisplayName("발송이 실패하면 확보한 예산 자리를 되돌린다")
    void releasesBudgetWhenSendFails() {
        MailDispatcher dispatcher =
                new MailDispatcher(new SyncTaskExecutor(), () -> false, clock, budget);

        dispatcher.dispatch(
                MailKind.VERIFICATION_CODE,
                44L,
                () -> {
                    throw new MailSendException("Failed messages");
                });

        assertThat(budget.consumed()).as("확보").isEqualTo(1);
        assertThat(budget.released()).as("되돌림").isEqualTo(1);
    }

    @Test
    @DisplayName("발송에 성공하면 예산을 되돌리지 않는다")
    void keepsBudgetWhenSendSucceeds() {
        MailDispatcher dispatcher =
                new MailDispatcher(new SyncTaskExecutor(), () -> false, clock, budget);

        dispatcher.dispatch(MailKind.VERIFICATION_CODE, 45L, () -> {});

        assertThat(budget.consumed()).isEqualTo(1);
        assertThat(budget.released()).isZero();
    }

    private MailDispatcher dispatcher(TaskExecutor executor) {
        return new MailDispatcher(executor, () -> false, clock, budget);
    }

    private static void dispatch(
            MailDispatcher dispatcher,
            long userId,
            List<String> senderThreads,
            CountDownLatch gate) {
        dispatcher.dispatch(
                MailKind.VERIFICATION_CODE,
                userId,
                () -> {
                    senderThreads.add(Thread.currentThread().getName());
                    awaitGate(gate);
                });
    }

    /** 스레드 1개, 큐 1칸. 첫 발송이 스레드를 잡고 두 번째가 큐에 앉으면 그다음부터 거절된다. */
    private static ThreadPoolTaskExecutor singleSlotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("mail-test-");
        executor.initialize();
        return executor;
    }

    /** 항상 거절하는 실행기. */
    private static TaskExecutor rejectingExecutor() {
        return task -> {
            throw new RejectedExecutionException("큐가 찼다");
        };
    }

    /** 큐가 배수되는 진동을 모방한다. 플래그가 켜져 있으면 거절하고, 꺼져 있으면 호출 스레드에서 바로 실행한다. */
    private static TaskExecutor drainingExecutor(AtomicBoolean reject) {
        return task -> {
            if (reject.get()) {
                throw new RejectedExecutionException("큐가 찼다");
            }
            task.run();
        };
    }

    private static void awaitGate(CountDownLatch gate) {
        if (gate == null) {
            return;
        }
        try {
            if (!gate.await(IDLE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("발송 래치가 열리지 않았다");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void awaitIdle(MailDispatcher dispatcher) {
        Instant deadline = Instant.now().plus(IDLE_TIMEOUT);
        while (!dispatcher.isIdle()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("발송이 " + IDLE_TIMEOUT + " 안에 끝나지 않았다");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    /** 테스트가 창을 넘길 수 있게 앞으로만 가는 시계. 발송 스레드도 읽으므로 값은 원자적으로 바꾼다. */
    private static final class MovableClock extends Clock {

        private static final Instant START = Instant.parse("2026-09-09T00:00:00Z");

        private final AtomicLong offsetMillis = new AtomicLong();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return START.plusMillis(offsetMillis.get());
        }

        void advance(Duration amount) {
            offsetMillis.addAndGet(amount.toMillis());
        }
    }

    /** 예산 판정을 테스트가 쥔다. 소모 횟수는 "실제 발송만 센다"를 확인하는 데 쓴다. */
    private static final class RecordingBudget implements MailSendBudget {

        private final AtomicLong consumed = new AtomicLong();
        private final AtomicLong released = new AtomicLong();
        private volatile boolean allow = true;
        private volatile boolean broken;

        @Override
        public boolean tryConsume() {
            if (broken) {
                throw new IllegalStateException("Redis에 닿지 못했다");
            }
            consumed.incrementAndGet();
            return allow;
        }

        @Override
        public void release() {
            released.incrementAndGet();
        }

        @Override
        public boolean hasCapacity() {
            return allow;
        }

        long released() {
            return released.get();
        }

        void refuse() {
            allow = false;
        }

        void fail() {
            broken = true;
        }

        long consumed() {
            return consumed.get();
        }
    }
}
