package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.auth.LoginRateLimitProperties.Quota;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

/**
 * 로그인 시도 제한 설정값의 기본값과 검증을 확인한다(S15P21E106-311, IP 축은 S15P21E106-312).
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
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    /** 운영 설정 파일. 테스트 작업 디렉터리는 Gradle 기본값인 {@code backend}다. */
    private static final String OPERATIONAL_CONFIG = "src/main/resources/application.yml";

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
        // IP 축은 목록이라 애노테이션으로 계약 기본값을 둘 수 없다. 값이 없으면 그 축은 제한하지 않는다.
        assertThat(properties.ip()).isEmpty();
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
        assertThatThrownBy(() -> new LoginRateLimitProperties(0, TEN_MINUTES, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-limit")
                .hasMessageContaining("현재 값: 0");
        assertThatThrownBy(() -> new LoginRateLimitProperties(-1, TEN_MINUTES, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-limit")
                .hasMessageContaining("현재 값: -1");
    }

    @Test
    @DisplayName("창이 없거나 하한보다 짧으면 만들 수 없다")
    void rejectsMissingOrTooShortWindow() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, Duration.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, TEN_MINUTES.negated(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        // 0보다 크지만 밀리초로 바꾸면 0이 되는 구간. PEXPIRE 0은 오류가 아니라 키 삭제다.
        assertThatThrownBy(
                        () -> new LoginRateLimitProperties(5, Duration.ofNanos(500_000), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
    }

    @Test
    @DisplayName("창이 상한을 넘으면 만들 수 없다")
    void rejectsTooLongWindow() {
        assertThatThrownBy(() -> new LoginRateLimitProperties(5, Duration.ofHours(25), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
        // toMillis()가 long 범위를 넘어 요청 시점에 터지던 값.
        assertThatThrownBy(
                        () ->
                                new LoginRateLimitProperties(
                                        5, Duration.ofDays(100_000_000_000L), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.attempt-window");
    }

    @Test
    @DisplayName("정상 값은 그대로 유지된다")
    void validValuesArePreserved() {
        LoginRateLimitProperties properties =
                new LoginRateLimitProperties(3, Duration.ofMinutes(5), List.of());

        assertThat(properties.attemptLimit()).isEqualTo(3);
        assertThat(properties.attemptWindow()).isEqualTo(Duration.ofMinutes(5));
    }

    /** 이 업무(S15P21E106-312)의 완료 조건 3을 검증한다. 목록 형식이라 창을 늘리거나 줄여도 코드가 그대로여야 한다. */
    @Test
    @DisplayName("IP 축의 창 목록이 접두사 아래 설정에서 순서대로 바인딩된다")
    void ipQuotasAreBoundFromPrefix() {
        contextRunner
                .withPropertyValues(
                        PREFIX + ".ip[0].limit=60",
                        PREFIX + ".ip[0].window=10m",
                        PREFIX + ".ip[1].limit=300",
                        PREFIX + ".ip[1].window=1h")
                .run(
                        context -> {
                            LoginRateLimitProperties properties =
                                    context.getBean(LoginRateLimitProperties.class);
                            assertThat(properties.ip())
                                    .containsExactly(
                                            new Quota(60, TEN_MINUTES),
                                            new Quota(300, Duration.ofHours(1)));
                        });
    }

    /** 이메일 축의 창과 같은 함정이다. {@code window: 600}을 600초로 의도한 설정이 600ms로 조용히 바인딩되면 IP 제한이 사라진다. */
    @Test
    @DisplayName("IP 축의 창도 단위를 생략하면 초로 읽는다")
    void unitlessIpWindowIsReadAsSeconds() {
        contextRunner
                .withPropertyValues(PREFIX + ".ip[0].limit=60", PREFIX + ".ip[0].window=600")
                .run(
                        context ->
                                assertThat(context.getBean(LoginRateLimitProperties.class).ip())
                                        .containsExactly(new Quota(60, TEN_MINUTES)));
    }

    @Test
    @DisplayName("IP 한도가 1보다 작으면 기동에 실패하고 메시지가 키와 값을 가리킨다")
    void invalidIpLimitFailsStartup() {
        assertStartupFailure(
                new String[] {PREFIX + ".ip[0].limit=0", PREFIX + ".ip[0].window=10m"},
                "feelm.auth.login.ip",
                "0");
    }

    @Test
    @DisplayName("IP 축의 창이 범위를 벗어나면 기동에 실패한다")
    void invalidIpWindowFailsStartup() {
        assertStartupFailure(
                new String[] {PREFIX + ".ip[0].limit=60", PREFIX + ".ip[0].window=600ms"},
                "feelm.auth.login.ip",
                "PT0.6S");
        assertStartupFailure(
                new String[] {PREFIX + ".ip[0].limit=60", PREFIX + ".ip[0].window=48h"},
                "feelm.auth.login.ip",
                "PT48H");
    }

    @Test
    @DisplayName("IP 축의 잘못된 값은 만들 수 없고 메시지가 메일 축이 아니라 로그인 키를 가리킨다")
    void rejectsInvalidIpQuota() {
        assertThatThrownBy(() -> new Quota(0, TEN_MINUTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.ip")
                .hasMessageContaining("현재 값: 0");
        assertThatThrownBy(() -> new Quota(60, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.ip");
        assertThatThrownBy(() -> new Quota(60, Duration.ofMillis(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.ip");
        assertThatThrownBy(() -> new Quota(60, Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.ip");
    }

    /** 키만 있고 항목이 없는 설정({@code ip:})이 {@code null}로 들어와도 그 축만 제한하지 않게 한다. */
    @Test
    @DisplayName("IP 목록이 없으면 빈 목록으로 맞춘다")
    void nullIpListBecomesEmptyList() {
        assertThat(new LoginRateLimitProperties(5, TEN_MINUTES, null).ip()).isEmpty();
    }

    /**
     * 운영 설정 파일에 계약 기본값이 실제로 들어 있는지 본다.
     *
     * <p>IP 축은 목록이라 record에 계약 기본값을 둘 수 없고 값이 없으면 그 축이 열린다. 그래서 {@code application.yml}의 블록을 지우거나
     * 키를 잘못 적어도 다른 테스트는 전부 통과한다(테스트는 자기 설정을 쓴다). 계약값을 약속한 문서(API-004)와 실제 배포 값이 어긋나지 않게 운영 파일 자체를
     * 읽어 고정한다(리뷰 A-1·B-5).
     */
    @Test
    @DisplayName("운영 application.yml이 계약 기본값(5회/10분, IP 10분 60회·1시간 300회)을 담는다")
    void operationalConfigCarriesContractDefaults() {
        FileSystemResource resource = new FileSystemResource(OPERATIONAL_CONFIG);
        assertThat(resource.exists()).as("운영 설정 파일 %s", OPERATIONAL_CONFIG).isTrue();
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        Properties flattened = yaml.getObject();
        assertThat(flattened).as("운영 설정 파일 파싱 결과").isNotNull();
        Map<String, Object> source = new HashMap<>();
        flattened.forEach((key, value) -> source.put((String) key, value));

        LoginRateLimitProperties properties =
                new Binder(new MapConfigurationPropertySource(source))
                        .bindOrCreate(PREFIX, LoginRateLimitProperties.class);

        assertThat(properties.attemptLimit()).isEqualTo(5);
        assertThat(properties.attemptWindow()).isEqualTo(TEN_MINUTES);
        assertThat(properties.ip())
                .containsExactly(new Quota(60, TEN_MINUTES), new Quota(300, ONE_HOUR));
    }

    /**
     * 앞 창에서 막히면 뒤 창의 카운터를 올리지 않으므로 목록 순서가 정책을 바꾼다. 긴 창을 먼저 두면 짧은 폭주가 긴 창의 예산을 먼저 태워 차단이 확대된다. 적은
     * 순서와 무관하게 짧은 창이 먼저 평가되도록 정규화하는지 본다(리뷰 A-2).
     */
    @Test
    @DisplayName("IP 창 목록은 적은 순서와 무관하게 창 길이 오름차순으로 정규화된다")
    void ipQuotasAreSortedByWindow() {
        LoginRateLimitProperties properties =
                new LoginRateLimitProperties(
                        5,
                        TEN_MINUTES,
                        List.of(new Quota(300, ONE_HOUR), new Quota(60, TEN_MINUTES)));

        assertThat(properties.ip())
                .containsExactly(new Quota(60, TEN_MINUTES), new Quota(300, ONE_HOUR));
    }

    /**
     * 창은 초 단위로 카운터 키에 들어가므로 {@code 10m}과 {@code 600}은 같은 키다. 그 설정은 한 요청이 같은 카운터를 두 번 올려 실효 한도를 절반으로
     * 떨어뜨리는데, 검증이 없으면 기동도 성공하고 로그도 남지 않는다(리뷰 B-4·A-5).
     */
    @Test
    @DisplayName("같은 창이 두 번 있으면 만들 수 없다")
    void rejectsDuplicateIpWindow() {
        assertThatThrownBy(
                        () ->
                                new LoginRateLimitProperties(
                                        5,
                                        TEN_MINUTES,
                                        List.of(
                                                new Quota(60, TEN_MINUTES),
                                                new Quota(300, Duration.ofSeconds(600)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.login.ip")
                .hasMessageContaining("600초");
    }

    @Test
    @DisplayName("표기가 다른 같은 창도 기동에 실패한다")
    void duplicateIpWindowFailsStartup() {
        assertStartupFailure(
                new String[] {
                    PREFIX + ".ip[0].limit=60",
                    PREFIX + ".ip[0].window=10m",
                    PREFIX + ".ip[1].limit=300",
                    PREFIX + ".ip[1].window=600"
                },
                "feelm.auth.login.ip",
                "600초");
    }

    /** 실제 바인딩 경로에서 컨텍스트가 실패하고, 원인 메시지가 어떤 키가 왜 잘못됐는지 담는지 본다(완료 조건 2). */
    private void assertStartupFailure(String property, String key, String value) {
        assertStartupFailure(new String[] {property}, key, value);
    }

    private void assertStartupFailure(String[] properties, String key, String value) {
        contextRunner
                .withPropertyValues(properties)
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
