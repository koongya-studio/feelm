package com.feelm.backend.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 메일 발송 풀 설정의 기본값과 검증을 확인한다(S15P21E106-282).
 *
 * <p>기본값을 고정하는 이유는 {@code application.yml}에 값을 두지 않기 때문이다. 값이 코드 안에만 있으면 바뀐 사실이 설정 diff로 드러나지 않는다.
 *
 * <p>잘못된 값이 기동에 실패해야 하는 이유는 실패하지 않을 때의 결과가 조용한 유실이기 때문이다. {@code pool-size: 0}은 발송을 통째로 멈추고 {@code
 * queue-capacity: 0}은 스레드가 모두 바쁜 동안의 발송을 전부 버린다. 바인딩이 이 생성자를 호출하므로 여기서 던지는 예외가 곧 기동 실패다({@code
 * feelm.mail.rate-limit}과 같은 방식).
 */
@DisplayName("메일 발송 풀 설정 — 기본값과 검증")
class MailAsyncPropertiesTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    @Test
    @DisplayName("값을 주지 않으면 스레드 2개, 큐 100칸, 종료 대기 10초다")
    void defaultsAreBoundWhenNothingIsConfigured() {
        MailAsyncProperties properties =
                new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate("feelm.mail.async", MailAsyncProperties.class);

        assertThat(properties.poolSize()).isEqualTo(2);
        assertThat(properties.queueCapacity()).isEqualTo(100);
        assertThat(properties.shutdownAwait()).isEqualTo(TEN_SECONDS);
    }

    /**
     * 리뷰 F6. 생성자 직접 호출은 "잘못된 값이 기동을 멈춘다"를 보이지 못한다. 접두사가 틀리거나 등록이 빠지면 값이 바인딩되지 않은 채 기본값으로 기동하기 때문에,
     * 바인딩 경로로 한 번은 확인해야 한다.
     */
    @Test
    @DisplayName("잘못된 값이 설정에서 들어오면 바인딩이 실패한다")
    void invalidConfiguredValueFailsBinding() {
        Binder binder =
                new Binder(
                        new MapConfigurationPropertySource(
                                Map.of("feelm.mail.async.pool-size", 0)));

        assertThatThrownBy(() -> binder.bindOrCreate("feelm.mail.async", MailAsyncProperties.class))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool-size");
    }

    @Test
    @DisplayName("스레드 수가 1보다 작으면 만들 수 없다")
    void rejectsNonPositivePoolSize() {
        assertThatThrownBy(() -> new MailAsyncProperties(0, 100, TEN_SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool-size");
        assertThatThrownBy(() -> new MailAsyncProperties(-1, 100, TEN_SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("큐 크기가 1보다 작으면 만들 수 없다")
    void rejectsNonPositiveQueueCapacity() {
        assertThatThrownBy(() -> new MailAsyncProperties(2, 0, TEN_SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue-capacity");
        assertThatThrownBy(() -> new MailAsyncProperties(2, -1, TEN_SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("종료 대기가 없거나 0 이하이면 만들 수 없다")
    void rejectsNonPositiveShutdownAwait() {
        assertThatThrownBy(() -> new MailAsyncProperties(2, 100, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdown-await");
        assertThatThrownBy(() -> new MailAsyncProperties(2, 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MailAsyncProperties(2, 100, TEN_SECONDS.negated()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
