package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 로그인 연속 실패 제한 설정값의 기본값과 검증을 확인한다(S15P21E106-311).
 *
 * <p>기본값을 고정하는 이유는 5회/10분이 {@code docs/api/users.md} API-004에 적힌 계약값이기 때문이다. 설정 블록이 없는 환경도 계약값으로 떠야
 * 한다.
 *
 * <p>잘못된 값이 기동에 실패해야 하는 이유는 실패하지 않을 때의 결과가 런타임 장애이기 때문이다. {@code attempt-limit: 0}은 비밀번호가 맞아도 모든
 * 로그인을 대조 없이 429로 막고, 1초 미만의 창은 {@code PEXPIRE key 0}으로 카운터를 매번 삭제해 제한을 조용히 무력화하며(Redis는 만료 0을 오류로
 * 거부하지 않는다), 과대한 창은 첫 로그인에서 {@code ArithmeticException}으로 500이 된다.
 *
 * <p>바인딩은 {@link ApplicationContextRunner}로 <b>실제 경로</b>에서 확인한다. {@link Binder}에 접두사를 문자열로 넘기는 방식은
 * {@code @ConfigurationProperties}의 접두사를 참조하지 않아, 애노테이션이나 {@code application.yml}의 키가 틀려도 통과한다. 그러면
 * 이 업무의 목적(값을 설정에서 읽는다)을 증명하지 못한다(리뷰 A-3·B-3). 그래서 기본값만 {@link Binder}로 보고, 접두사와 기동 실패는 컨텍스트로 본다.
 */
@DisplayName("로그인 연속 실패 제한 설정 — 기본값과 검증")
class LoginRateLimitPropertiesTest {

    private static final String PREFIX = "feelm.auth.login";
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("값을 주지 않으면 계약 기본값인 5회/10분이다")
    void defaultsAreBoundWhenNothingIsConfigured() {
        LoginRateLimitProperties properties =
                new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate(PREFIX, LoginRateLimitProperties.class);

        assertThat(properties.attemptLimit()).isEqualTo(5);
        assertThat(properties.attemptWindow()).isEqualTo(TEN_MINUTES);
    }

    /**
     * 이 업무의 완료 조건 1({@code application.yml}의 {@code feelm.auth.login} 아래 설정으로 읽힌다)을 검증한다. 기본값과 다른
     * 값을 넣어, 애노테이션 접두사가 틀리면(그래서 값이 무시되고 기본값이 남으면) 실패하게 한다.
     */
    @Test
    @DisplayName("접두사 아래 설정한 값이 빈에 그대로 바인딩된다")
    void configuredValuesAreBoundFromPrefix() {
        contextRunner
                .withPropertyValues(PREFIX + ".attempt-limit=3", PREFIX + ".attempt-window=42s")
                .run(
                        context -> {
                            LoginRateLimitProperties properties =
                                    context.getBean(LoginRateLimitProperties.class);
                            assertThat(properties.attemptLimit()).isEqualTo(3);
                            assertThat(properties.attemptWindow())
                                    .isEqualTo(Duration.ofSeconds(42));
                        });
    }

    /**
     * 단위 없는 숫자의 Spring 기본 해석은 밀리초다. {@code 600}을 600초로 의도한 설정이 600ms로 바인딩되면 기동은 성공하고 무차별 대입 보호만
     * 사라진다(리뷰 B-1). 그 해석을 초로 못박았는지 본다.
     */
    @Test
    @DisplayName("창에 단위를 생략하면 밀리초가 아니라 초로 읽는다")
    void unitlessWindowIsReadAsSeconds() {
        contextRunner
                .withPropertyValues(PREFIX + ".attempt-window=600")
                .run(
                        context ->
                                assertThat(
                                                context.getBean(LoginRateLimitProperties.class)
                                                        .attemptWindow())
                                        .isEqualTo(TEN_MINUTES));
    }

    @Test
    @DisplayName("한도가 1보다 작으면 기동에 실패하고 메시지가 키와 값을 가리킨다")
    void invalidLimitFailsStartup() {
        assertStartupFailure(PREFIX + ".attempt-limit=0", "attempt-limit", "0");
    }

    @Test
    @DisplayName("창이 1초 미만이면 기동에 실패한다")
    void tooShortWindowFailsStartup() {
        assertStartupFailure(PREFIX + ".attempt-window=600ms", "attempt-window", "PT0.6S");
    }

    @Test
    @DisplayName("창이 24시간을 넘으면 기동에 실패한다")
    void tooLongWindowFailsStartup() {
        assertStartupFailure(PREFIX + ".attempt-window=48h", "attempt-window", "PT48H");
    }

    @Test
    @DisplayName("시도 한도가 1보다 작으면 만들 수 없다")
    void rejectsNonPositiveAttemptLimit() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(0, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-limit")
                .hasMessageContaining("현재 값: 0");
        assertThatThrownBy(() -> new LoginRateLimitProperties(-1, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-limit")
                .hasMessageContaining("현재 값: -1");
    }

    @Test
    @DisplayName("창이 없거나 하한보다 짧으면 만들 수 없다")
    void rejectsMissingOrTooShortWindow() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, TEN_MINUTES.negated()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        // 0보다 크지만 밀리초로 바꾸면 0이 되는 구간. PEXPIRE 0은 오류가 아니라 키 삭제다.
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
    }

    @Test
    @DisplayName("창이 상한을 넘으면 만들 수 없다")
    void rejectsTooLongWindow() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        // toMillis()가 long 범위를 넘어 요청 시점에 터지던 값.
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, Duration.ofDays(100_000_000_000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
    }

    @Test
    @DisplayName("정상 값은 그대로 유지된다")
    void validValuesArePreserved() {
        LoginRateLimitProperties properties =
                new LoginRateLimitProperties(3, Duration.ofMinutes(5));

        assertThat(properties.attemptLimit()).isEqualTo(3);
        assertThat(properties.attemptWindow()).isEqualTo(Duration.ofMinutes(5));
    }

    /** 실제 바인딩 경로에서 컨텍스트가 실패하고, 원인 메시지가 어떤 키가 왜 잘못됐는지 담는지 본다(완료 조건 2). */
    private void assertStartupFailure(String property, String key, String value) {
        contextRunner
                .withPropertyValues(property)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(rootCauseMessage(context)).contains(key).contains(value);
                        });
    }

    private String rootCauseMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure.getMessage();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoginRateLimitProperties.class)
    static class TestConfig {}
}
