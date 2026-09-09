package com.feelm.backend.infrastructure.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 계정 메일을 보내는 전용 스레드 풀의 설정값이다. 접두사는 {@code feelm.mail.async}다(S15P21E106-282).
 *
 * <p>기본값만으로 동작하므로 {@code application.yml}에는 값을 두지 않는다. 값을 바꿔야 하는 환경만 이 접두사로 덮어쓴다. 정상적인 SMTP 왕복은 수백
 * ms~수 초라 스레드 2개로도 분당 수십 건을 보낼 수 있고, 시연 규모의 발송량은 발송 제한(API-003·API-010의 간격 제한)이 이미 묶어 둔다.
 *
 * <p>{@code spring.mail}의 타임아웃은 발송 1건의 상한이 아니다. read·write 타임아웃이 왕복마다 다시 적용되므로 서버가 느리면 한 건이 수십 초 이상
 * 걸린다({@code application.yml}의 타임아웃 주석). 그 구간에는 스레드 2개가 오래 붙잡혀 큐가 빠르게 차고 발송이 버려진다. 그 유실을 감수하는 것이 이
 * 기본값의 전제다.
 *
 * <p>큐는 반드시 유한하다. 무한 큐는 SMTP 장애 중 요청을 받는 만큼 메모리가 늘고, 큐가 차지 않으므로 유실을 인지할 계기도 없어진다. 큐가 찼을 때의 정책은
 * {@link MailDispatcher}가 정한다.
 *
 * <p>잘못된 값은 기동 시점에 실패한다. {@code pool-size: 0}은 발송을 통째로 멈추고 {@code queue-capacity: 0}은 동시 발송 2건을 넘는
 * 순간부터 메일을 버리므로, 설정 오타를 런타임 유실이 아니라 기동 실패로 드러낸다({@code feelm.mail.rate-limit}과 같은 판단).
 *
 * @param poolSize 발송 스레드 수. 코어와 최대를 같은 값으로 쓴다
 * @param queueCapacity 대기 큐 크기. 넘치는 발송은 버린다
 * @param shutdownAwait 종료 시 진행 중·대기 중 발송을 내보내는 상한. 이 시간이 지나면 남은 발송은 버려진다({@link
 *     MailDispatchConfig})
 */
@ConfigurationProperties(prefix = "feelm.mail.async")
public record MailAsyncProperties(
        @DefaultValue("2") int poolSize,
        @DefaultValue("100") int queueCapacity,
        @DefaultValue("10s") Duration shutdownAwait) {

    public MailAsyncProperties {
        if (poolSize < 1) {
            throw new IllegalArgumentException(
                    "feelm.mail.async.pool-size는 1 이상이어야 합니다: " + poolSize);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException(
                    "feelm.mail.async.queue-capacity는 1 이상이어야 합니다: " + queueCapacity);
        }
        if (shutdownAwait == null || shutdownAwait.isZero() || shutdownAwait.isNegative()) {
            throw new IllegalArgumentException(
                    "feelm.mail.async.shutdown-await는 0보다 커야 합니다: " + shutdownAwait);
        }
    }
}
