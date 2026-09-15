package com.feelm.backend.infrastructure.mail;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;

/**
 * 계정 메일 발송을 요청 스레드에서 떼어 전용 스레드 풀로 넘긴다(S15P21E106-282).
 *
 * <p>커밋 뒤 요청 스레드에서 바로 보내면 SMTP 왕복(정상 수백 ms~수 초, 서버가 느리면 수십 초 이상)이 응답 시간에 그대로 실린다. 그러면 계정이 있는 이메일과
 * 없는 이메일의 202 응답 시간이 갈려 응답만으로는 감춘 계정 존재 여부가 시간으로 드러난다(S15P21E106-151 리뷰 R-7). 발송을 별도 풀로 넘기면 응답 경로에서
 * SMTP 대기가 사라진다. 계정이 있는 경로에 남는 토큰 발급·저장 수 ms 차이는 감수한다(플랜 U-2).
 *
 * <p>{@code @Async}와 {@code @EnableAsync}를 쓰지 않고 제출을 이 클래스에 직접 두었다. 제출은 {@code AFTER_COMMIT} 리스너
 * 안에서 일어나고, 그 지점에서 던진 예외는 커밋을 호출한 요청으로 전파되어 201·202가 500이 될 수 있다. 제출 실패와 발송 실패를 한 곳에서 잡아 두면 그 경로가
 * 코드로 막히고 테스트로 고정된다. 애플리케이션 전역 {@code @Async} 의미를 도입하지 않는 것도 이점이다.
 *
 * <p>큐가 차면 <b>거절하고 버린다</b>. 호출 스레드가 대신 실행하는 정책({@code CallerRunsPolicy})은 큐가 찬 순간 요청 스레드를 다시 SMTP
 * 앞에 세워 위에서 없앤 시간 차를 되살린다. 메일 유실은 사용자의 재요청이 복구 경로이므로 감수할 수 있는 쪽이다.
 *
 * <p>발송 실패도 요청을 되돌리지 않는다. 응답은 이미 나갔고, 계정과 토큰은 만들어져 있어 사용자가 재발송·재요청으로 복구할 수 있다. 로그에는 사용자 식별자만 남기고
 * 이메일과 토큰은 남기지 않는다(컨벤션 12장). 실패 원인은 예외 객체 대신 {@link #summarize(Throwable)}가 만든 요약으로 남긴다.
 *
 * <p>전역 발송 상한({@link MailSendBudget})도 이 풀의 스레드에서 본다(S15P21E106-287). 상한을 요청 경로가 아니라 여기에 둔 것은 그 확인이
 * <b>보낼 메일이 있을 때만</b> 일어나기 때문이다. 커밋 후 리스너에서 확인하면 계정이 있는 경로에만 Redis 왕복이 더해져 이 클래스가 없앤 응답 시간 차가
 * 되살아나고, 상한이 응답을 바꾸면 실제 발송을 세는 카운터가 계정 존재 여부를 알려 주는 신호가 된다. 이 자리에서는 큐에서 버려진 발송이 예산을 소모하지 않는 성질도 함께
 * 얻는다.
 */
@Slf4j
public class MailDispatcher {

    /**
     * 같은 포화 구간에서 경고를 다시 남기기까지의 최소 간격이자, 포화가 풀렸다고 판정하는 데 필요한 무유실 시간이다.
     *
     * <p>창을 "첫 성공 제출까지"로 잡으면 안 된다. 포화는 쭉 막혀 있는 상태가 아니라 발송 한 건이 끝날 때마다 큐가 한 칸 열리는 진동이고, 그 칸을 채운 제출이
     * 회복을 선언하면 바로 다음 거절이 진입 경고를 다시 남긴다. 그러면 경고는 구간당 한 번이 아니라 큐가 배수되는 횟수만큼 쌓인다(리뷰 F1·B-1).
     */
    static final Duration SATURATION_WARN_INTERVAL = Duration.ofMinutes(1);

    /** {@link #lastWarnAt}·{@link #lastDropAt}의 "아직 없다" 값. 다음 유실에서 경고를 바로 남긴다. */
    private static final long NEVER = Long.MIN_VALUE;

    /** 예외 요약에서 훑을 원인 깊이와 길이 상한. 원인 사슬이 순환하거나 아주 길어도 로그 한 줄을 넘기지 않게 한다. */
    private static final int SUMMARY_MAX_CAUSES = 5;

    private static final int SUMMARY_MAX_LENGTH = 1000;

    /**
     * 예외 메시지에 실린 이메일 주소. SMTP 실패는 주소를 메시지에 담는 것이 보통이다({@code MailSendException} 안의 {@code 550 ...
     * user@example.com}). 컨벤션 12장이 금지한 개인정보라 로그에 그대로 남길 수 없다.
     */
    private static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    private final TaskExecutor executor;

    /** 실행기가 종료 중인지 알려준다. 유실 로그의 원인 문구를 고르는 데만 쓴다. */
    private final BooleanSupplier shuttingDown;

    /** 전역 발송 상한. 발송 스레드에서 발송 직전에 본다(S15P21E106-287). */
    private final MailSendBudget budget;

    private final Clock clock;

    /** 제출했지만 아직 끝나지 않은 발송 수. {@link #isIdle()}의 근거다. */
    private final AtomicLong pending = new AtomicLong();

    /** 이번 포화 구간에서 버린 발송 수. 구간이 끝날 때 한 번 기록하고 0으로 되돌린다. */
    private final AtomicLong dropped = new AtomicLong();

    /** 마지막 유실 시각(epoch ms). 이 시각에서 {@link #SATURATION_WARN_INTERVAL}이 지나야 구간이 끝난 것으로 본다. */
    private final AtomicLong lastDropAt = new AtomicLong(NEVER);

    /** 포화 경고를 마지막으로 남긴 시각(epoch ms). */
    private final AtomicLong lastWarnAt = new AtomicLong(NEVER);

    public MailDispatcher(
            TaskExecutor executor,
            BooleanSupplier shuttingDown,
            Clock clock,
            MailSendBudget budget) {
        this.executor = executor;
        this.shuttingDown = shuttingDown;
        this.clock = clock;
        this.budget = budget;
    }

    /**
     * 발송을 전용 풀에 넘긴다. 호출자는 발송 결과를 기다리지 않으며 예외도 받지 않는다.
     *
     * <p>제출 실패는 거절({@link RejectedExecutionException})뿐 아니라 어떤 런타임 예외도 삼킨다. 이 메서드는 커밋 후 리스너에서 불리므로
     * 여기서 나가는 예외는 커밋 호출자로 전파되어 201·202를 500으로 바꾼다. 거절만 잡으면 실행기 상태 이상처럼 확률이 낮은 경로가 열려 있다(리뷰 F7).
     *
     * <p>제출에 성공해도 발송이 보장되지는 않는다. 전역 발송 상한을 넘으면 풀의 스레드가 이 메일을 버린다(S15P21E106-287).
     *
     * @param kind 메일 종류. 실패·유실 로그의 문구와 복구 경로를 정한다
     * @param userId 로그에 남길 사용자 식별자
     * @param send 실제 발송. 풀의 스레드에서 실행된다
     */
    public void dispatch(MailKind kind, long userId, Runnable send) {
        pending.incrementAndGet();
        try {
            executor.execute(() -> run(kind, userId, send));
        } catch (RuntimeException submissionFailure) {
            pending.decrementAndGet();
            reportDropped(kind, userId, submissionFailure);
            return;
        }
        reportRecovered();
    }

    /**
     * 제출한 발송이 모두 끝났는지 알려준다.
     *
     * <p>동작은 이 값에 의존하지 않는다. 발송이 응답보다 늦게 끝나므로, 발송 결과를 확인하는 테스트가 완료를 기다릴 지점이 필요해 열어 둔 관찰값이다. 제출은 응답이
     * 나가기 전에 끝나므로 응답을 받은 뒤에 이 값을 보면 발송이 큐에 들어간 사실을 놓치지 않는다.
     */
    public boolean isIdle() {
        return pending.get() == 0;
    }

    /**
     * 발송 스레드에서 전역 상한을 확보한 뒤 실제로 보낸다.
     *
     * <p>상한을 넘으면 이 메일은 <b>버린다</b>. 큐 포화 유실과 같은 성질이고 복구 경로도 같다. 여기서 남기는 로그는 없다. 상한 도달은 {@link
     * MailSendBudget}이 창마다 한 번 남기고, 버려지는 메일마다 줄을 남기면 그 기록이 밀려나기 때문이다(S15P21E106-287 결정 D-6·D-8).
     *
     * <p>발송이 실패하면 확보한 자리를 되돌린다(리뷰 B-4). 이 상한이 지키는 것은 SMTP 계정의 쿼터이고 실패한 발송은 쿼터를 쓰지 않는다.
     */
    private void run(MailKind kind, long userId, Runnable send) {
        try {
            if (!consumeBudget(kind, userId)) {
                return;
            }
            try {
                send.run();
            } catch (RuntimeException exception) {
                releaseBudget();
                log.error(
                        "{} 발송에 실패했습니다. 사용자 {}는 {}으로 복구할 수 있습니다. 원인: {}",
                        kind.label(),
                        userId,
                        kind.recovery(),
                        summarize(exception));
            }
        } finally {
            pending.decrementAndGet();
        }
    }

    /**
     * 상한을 확인한다. 확인 자체가 실패하면 보내지 않고 원인을 남긴다(fail-closed, 결정 D-11).
     *
     * <p>발송 실패와 다른 문구를 쓰는 이유는 복구 방법이 다르기 때문이다. 발송 실패는 SMTP 쪽을 보게 하고, 이 줄은 Redis 쪽을 보게 한다.
     */
    private boolean consumeBudget(MailKind kind, long userId) {
        try {
            return budget.tryConsume();
        } catch (RuntimeException budgetFailure) {
            log.error(
                    "{} 발송 상한을 확인하지 못해 보내지 않았습니다. 사용자 {}는 {}으로 복구할 수 있습니다. 원인: {}",
                    kind.label(),
                    userId,
                    kind.recovery(),
                    summarize(budgetFailure));
            return false;
        }
    }

    /**
     * 실패한 발송이 소모한 자리를 되돌린다(리뷰 B-4).
     *
     * <p>되돌리기가 실패해도 삼킨다. 이 경로는 이미 발송 실패를 기록하는 중이고, 여기서 던지면 그 기록이 사라진다. 되돌리지 못한 자리는 창이 끝날 때 함께 사라진다.
     */
    private void releaseBudget() {
        try {
            budget.release();
        } catch (RuntimeException releaseFailure) {
            log.debug("발송 상한 자리를 되돌리지 못했습니다. 원인: {}", summarize(releaseFailure));
        }
    }

    /**
     * 발송을 넘기지 못한 사실을 기록한다.
     *
     * <p>거절은 한 구간에서 {@link #SATURATION_WARN_INTERVAL}<b>마다 한 번</b>만 남긴다. 거절마다 남기면 부하가 이어지는 동안 같은 줄이
     * 초당 요청 수만큼 쌓여 정작 필요한 기록을 밀어낸다(S15P21E106-281의 같은 판단). 대신 각 줄에 그 구간의 누적 유실 건수를 함께 적어, 회복 요약이 오기
     * 전에도 유실량을 알 수 있게 한다.
     *
     * <p>종료 중의 거절은 큐가 찬 것이 아니다. 컨텍스트가 닫히면 실행기가 먼저 제출을 거절하기 시작하므로, 그 구간의 유실을 포화로 적으면 재배포마다 원인이 뒤바뀐
     * 로그가 남는다(리뷰 F3). 거절이 아닌 제출 실패는 드물고 원인이 코드·설정 쪽이므로 창에 넣지 않고 매번 ERROR로 남긴다.
     */
    private void reportDropped(MailKind kind, long userId, RuntimeException submissionFailure) {
        if (!(submissionFailure instanceof RejectedExecutionException)) {
            log.error(
                    "{} 발송을 발송 스레드에 넘기지 못했습니다. 사용자 {}는 {}으로 복구할 수 있습니다. 원인: {}",
                    kind.label(),
                    userId,
                    kind.recovery(),
                    summarize(submissionFailure));
            return;
        }

        long now = clock.millis();
        long lostInWindow = dropped.incrementAndGet();
        lastDropAt.set(now);
        if (!shouldWarn(now)) {
            return;
        }
        if (isShuttingDown()) {
            log.warn(
                    "애플리케이션이 종료 중이어서 {} 발송을 보내지 못했습니다. 사용자 {}는 {}으로 복구할 수 있습니다."
                            + " 이 구간 누적 유실 {}건.",
                    kind.label(),
                    userId,
                    kind.recovery(),
                    lostInWindow);
            return;
        }
        log.warn(
                "메일 발송 큐가 가득 차 {} 발송을 버렸습니다. 사용자 {}는 {}으로 복구할 수 있습니다."
                        + " 이 구간 누적 유실 {}건. 포화가 이어지면 같은 경고를 {}마다 한 번만 남깁니다.",
                kind.label(),
                userId,
                kind.recovery(),
                lostInWindow,
                SATURATION_WARN_INTERVAL);
    }

    /**
     * 포화 구간이 끝났으면 유실 건수를 합쳐서 남긴다.
     *
     * <p>마지막 유실에서 {@link #SATURATION_WARN_INTERVAL}이 지난 뒤의 성공 제출에서만 구간을 닫는다. 성공 제출 한 건으로 바로 닫으면 큐가
     * 배수되는 진동에서 회복과 진입이 번갈아 기록된다(리뷰 F1·B-1).
     */
    private void reportRecovered() {
        if (dropped.get() == 0) {
            return;
        }
        if (clock.millis() - lastDropAt.get() < SATURATION_WARN_INTERVAL.toMillis()) {
            return;
        }
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            lastWarnAt.set(NEVER);
            log.warn("메일 발송 큐 포화가 풀렸습니다. 그동안 발송 {}건을 버렸습니다.", lost);
        }
    }

    private boolean shouldWarn(long now) {
        long last = lastWarnAt.get();
        if (last != NEVER && now - last < SATURATION_WARN_INTERVAL.toMillis()) {
            return false;
        }
        return lastWarnAt.compareAndSet(last, now);
    }

    /** 종료 여부는 로그 문구를 고르는 데만 쓴다. 이 판단에서 던진 예외까지 커밋 호출자로 전파되면 안 된다. */
    private boolean isShuttingDown() {
        try {
            return shuttingDown.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 예외를 클래스 이름과 <b>이메일 주소를 가린</b> 메시지로 요약한다.
     *
     * <p>예외 객체를 로거에 넘기지 않는 이유는 스택 트레이스가 원인 사슬의 메시지를 함께 찍기 때문이다. SMTP 실패 메시지에는 수신 주소가 보통 들어 있어 컨벤션
     * 12장이 금지한 개인정보가 로그에 남는다(리뷰 F5·B-4). 주소를 가리는 대신 응답 코드와 원인 사슬은 남겨 진단 가능성을 지킨다.
     *
     * <p>{@link #SUMMARY_MAX_LENGTH}는 마지막에 잘라서 적용한다. 원인 하나의 메시지만으로도 상한을 넘길 수 있어 루프 조건만으로는 길이가 묶이지
     * 않는다. 자르는 시점이 가림 처리 뒤이므로 잘려 나간 꼬리에 주소가 남지 않는다.
     */
    private static String summarize(Throwable failure) {
        StringBuilder summary = new StringBuilder();
        Throwable cause = failure;
        for (int depth = 0;
                cause != null
                        && depth < SUMMARY_MAX_CAUSES
                        && summary.length() < SUMMARY_MAX_LENGTH;
                depth++) {
            if (!summary.isEmpty()) {
                summary.append(" -> ");
            }
            summary.append(cause.getClass().getName());
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                summary.append(": ").append(maskAddresses(message.replaceAll("\\s+", " ").trim()));
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        if (summary.length() <= SUMMARY_MAX_LENGTH) {
            return summary.toString();
        }
        return summary.substring(0, SUMMARY_MAX_LENGTH) + "…(이하 생략)";
    }

    /** 이메일 주소의 앞 두 자만 남긴다. 어느 주소인지 가리는 단서는 남기고 주소 자체는 남기지 않는다. */
    private static String maskAddresses(String message) {
        Matcher matcher = EMAIL_ADDRESS.matcher(message);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String address = matcher.group();
            int at = address.indexOf('@');
            String replacement =
                    head(address.substring(0, at)) + "@" + head(address.substring(at + 1));
            matcher.appendReplacement(masked, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    private static String head(String text) {
        return (text.length() <= 2 ? text : text.substring(0, 2)) + "***";
    }
}
