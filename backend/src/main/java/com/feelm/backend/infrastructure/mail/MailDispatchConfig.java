package com.feelm.backend.infrastructure.mail;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 계정 메일 발송 전용 스레드 풀과 {@link MailDispatcher}를 등록한다(S15P21E106-282).
 *
 * <p>풀을 Spring 빈으로 두는 이유는 수명 관리다. 종료 시 큐에 있는 발송을 {@code shutdown-await}까지 내보내려면 컨테이너가 초기화·종료를 맡아야
 * 한다.
 *
 * <p>종료 대기에는 두 겹이 있다. {@code waitForTasksToCompleteOnShutdown}은 대기 중인 발송까지 실행하게 하고, {@code
 * awaitTerminationMillis}는 컨테이너가 그것을 기다리는 시간을 끊는다. 그런데 Spring은 그 시간을 넘겨도 경고만 남기고 강제로 취소하지 않으므로
 * (spring-context 7.0.9 {@code ExecutorConfigurationSupport.shutdown()}), 스레드가 non-daemon이면 남은 큐를
 * 계속 처리하며 JVM 종료를 붙잡는다. SMTP 왕복은 타임아웃이 왕복마다 다시 적용되어 한 건이 수십 초 이상 걸릴 수 있으므로({@code application.yml}의
 * 타임아웃 주석), 큐 100칸을 스레드 2개로 비우는 데 수십 분이 걸릴 수 있다. 그래서 발송 스레드를 <b>daemon</b>으로 둔다. 대기 시간 안에 실제로 나가는
 * 것은 스레드 2개가 처리하는 몇 건뿐이고, 그 뒤에는 JVM 종료가 남은 발송을 끊어 {@code shutdown-await}가 실제 상한이 된다(리뷰 F2·B-2). 끊긴
 * 발송의 유실은 사용자의 재요청이 복구 경로다.
 *
 * <p>거절 정책은 {@link ThreadPoolExecutor.AbortPolicy}다. {@code ThreadPoolTaskExecutor}의 기본값과 같지만, 이
 * 선택이 {@link MailDispatcher}가 없애려는 응답 시간 차와 직결되므로 기본값에 기대지 않고 명시한다.
 *
 * <p>이 풀은 {@code Executor} 타입 빈이라 Boot의 {@code applicationTaskExecutor} 자동 설정이 물러난다. 현재
 * {@code @Async}와 비동기 MVC를 쓰는 곳이 없어 영향이 없다. 나중에 애플리케이션 공용 실행기가 필요해지면 계정 메일 풀을 공유하지 말고 별도 빈으로 정의한다.
 * 한 풀을 공유하면 다른 작업이 큐를 채워 메일이 버려질 수 있다.
 */
@Configuration
@EnableConfigurationProperties(MailAsyncProperties.class)
class MailDispatchConfig {

    @Bean
    ThreadPoolTaskExecutor mailTaskExecutor(MailAsyncProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("mail-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return executor;
    }

    /**
     * 종료 여부를 실행기에서 직접 읽어 디스패처에 넘긴다. 컨텍스트가 닫히는 동안의 거절을 큐 포화로 적지 않기 위한 값이다(리뷰 F3). 풀이 초기화되기 전에는 이
     * 접근자가 예외를 던지므로 디스패처가 그 예외를 삼킨다.
     */
    @Bean
    MailDispatcher mailDispatcher(ThreadPoolTaskExecutor mailTaskExecutor) {
        return new MailDispatcher(
                mailTaskExecutor,
                () -> mailTaskExecutor.getThreadPoolExecutor().isShutdown(),
                Clock.systemUTC());
    }
}
