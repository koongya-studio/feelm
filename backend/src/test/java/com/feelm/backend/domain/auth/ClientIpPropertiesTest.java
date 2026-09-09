package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
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
 * IP 제한 집계 단위 설정의 기본값과 검증을 확인한다(S15P21E106-288).
 *
 * <p>기본값을 고정하는 이유는 IPv4 {@code /32}와 IPv6 {@code /64}가 이 업무의 결정값이고, 설정 블록이 없는 환경도 그 결정으로 떠야 하기
 * 때문이다.
 *
 * <p>잘못된 값이 기동에 실패해야 하는 이유는 실패하지 않을 때의 결과가 <b>제한의 조용한 변형</b>이기 때문이다. 프리픽스 길이 {@code 0}은 모든 클라이언트를 한
 * 버킷에 넣어 IP 축을 전역 축으로 바꾸고, 대역 상한을 넘는 값은 마스크가 주소 전체를 남겨 설정한 의도와 다른 단위로 동작한다. 둘 다 기동은 성공하므로 운영 중에는
 * 드러나지 않는다.
 *
 * <p>바인딩은 {@link ApplicationContextRunner}로 실제 경로에서 확인한다. {@link Binder}에 접두사를 문자열로 넘기는 방식은
 * {@code @ConfigurationProperties}의 접두사를 참조하지 않아 애노테이션의 키가 틀려도 통과한다(S15P21E106-311 리뷰 A-3·B-3).
 */
@DisplayName("클라이언트 IP 집계 단위 설정 — 기본값과 검증")
class ClientIpPropertiesTest {

    private static final String PREFIX = "feelm.auth.client-ip";
    private static final String OPERATIONAL_CONFIG = "src/main/resources/application.yml";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("값을 주지 않으면 IPv4 /32, IPv6 /64다")
    void defaultsAreBoundWhenNothingIsConfigured() {
        ClientIpProperties properties =
                new Binder(new MapConfigurationPropertySource(Map.of()))
                        .bindOrCreate(PREFIX, ClientIpProperties.class);

        assertThat(properties.ipv4PrefixLength()).isEqualTo(32);
        assertThat(properties.ipv6PrefixLength()).isEqualTo(64);
    }

    @Test
    @DisplayName("접두사 아래 설정한 값이 빈에 그대로 바인딩된다")
    void configuredValuesAreBoundFromPrefix() {
        contextRunner
                .withPropertyValues(
                        PREFIX + ".ipv4-prefix-length=24", PREFIX + ".ipv6-prefix-length=56")
                .run(
                        context -> {
                            ClientIpProperties properties =
                                    context.getBean(ClientIpProperties.class);
                            assertThat(properties.ipv4PrefixLength()).isEqualTo(24);
                            assertThat(properties.ipv6PrefixLength()).isEqualTo(56);
                        });
    }

    /**
     * 결정 근거의 폭을 벗어난 값이 기동을 통과하면 제한이 조용히 바뀐다(리뷰 B-4). ADR-0019가 기각한 IPv6 {@code /128}과 IPv4 절반을 한
     * 예산에 묶는 {@code /1}이 여기에 든다.
     */
    @Test
    @DisplayName("프리픽스 길이가 결정 범위를 벗어나면 기동에 실패한다")
    void rejectsPrefixLengthOutOfRange() {
        assertStartupFailure(PREFIX + ".ipv4-prefix-length=1", "ipv4-prefix-length", "1");
        assertStartupFailure(PREFIX + ".ipv4-prefix-length=23", "ipv4-prefix-length", "23");
        assertStartupFailure(PREFIX + ".ipv4-prefix-length=33", "ipv4-prefix-length", "33");
        assertStartupFailure(PREFIX + ".ipv6-prefix-length=47", "ipv6-prefix-length", "47");
        assertStartupFailure(PREFIX + ".ipv6-prefix-length=128", "ipv6-prefix-length", "128");
    }

    @Test
    @DisplayName("결정 범위를 벗어난 값으로는 만들 수 없다")
    void rejectsInvalidPrefixLengths() {
        assertThatThrownBy(() -> new ClientIpProperties(0, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.client-ip.ipv4-prefix-length")
                .hasMessageContaining("현재 값: 0");
        assertThatThrownBy(() -> new ClientIpProperties(-1, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.client-ip.ipv4-prefix-length");
        assertThatThrownBy(() -> new ClientIpProperties(33, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.client-ip.ipv4-prefix-length");
        assertThatThrownBy(() -> new ClientIpProperties(32, 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.client-ip.ipv6-prefix-length")
                .hasMessageContaining("현재 값: 128");
        assertThatThrownBy(() -> new ClientIpProperties(32, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feelm.auth.client-ip.ipv6-prefix-length");
    }

    @Test
    @DisplayName("결정 범위의 경계값은 그대로 유지된다")
    void boundaryValuesArePreserved() {
        ClientIpProperties widest = new ClientIpProperties(24, 48);
        ClientIpProperties narrowest = new ClientIpProperties(32, 64);

        assertThat(widest.ipv4PrefixLength()).isEqualTo(24);
        assertThat(widest.ipv6PrefixLength()).isEqualTo(48);
        assertThat(narrowest.ipv4PrefixLength()).isEqualTo(32);
        assertThat(narrowest.ipv6PrefixLength()).isEqualTo(64);
    }

    /**
     * 운영 설정 파일이 결정값을 실제로 담는지 본다(리뷰 A-6·B-1).
     *
     * <p>테스트는 {@code src/test/resources/application.yml}을 로드하므로 운영 파일의 값은 어떤 테스트도 읽지 않는다. 그래서 운영
     * 파일에서 신뢰 경계나 집계 단위를 지우거나 바꿔도 전 테스트가 통과한다. 세 목적(실제 클라이언트 IP 사용, 헤더 우회 불가, 집계 단위)이 모두 이 파일 몇 줄에
     * 걸려 있으므로 파일 자체를 읽어 고정한다. 값 검증 방식은 S15P21E106-311의 같은 테스트를 따른다.
     */
    @Test
    @DisplayName("운영 application.yml이 결정값(IPv4 /32, IPv6 /64)과 신뢰 경계를 담는다")
    void operationalConfigCarriesDecidedValues() {
        Map<String, Object> source = operationalConfig();

        ClientIpProperties properties =
                new Binder(new MapConfigurationPropertySource(source))
                        .bindOrCreate(PREFIX, ClientIpProperties.class);

        assertThat(properties.ipv4PrefixLength()).isEqualTo(32);
        assertThat(properties.ipv6PrefixLength()).isEqualTo(64);
        assertThat(source)
                .as("운영 설정의 프록시 신뢰 설정")
                .containsEntry("server.forward-headers-strategy", "native")
                .containsEntry("server.tomcat.remoteip.remote-ip-header", "x-real-ip")
                .containsEntry(
                        "server.tomcat.remoteip.internal-proxies",
                        "127.0.0.0/8,::1/128,172.16.0.0/12")
                .containsEntry("server.tomcat.remoteip.host-header", "")
                .containsEntry("server.tomcat.remoteip.port-header", "");
    }

    /** 운영 설정 파일을 평탄화한 키·값으로 읽는다. */
    private Map<String, Object> operationalConfig() {
        FileSystemResource resource = new FileSystemResource(OPERATIONAL_CONFIG);
        assertThat(resource.exists()).as("운영 설정 파일 %s", OPERATIONAL_CONFIG).isTrue();
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        Properties flattened = yaml.getObject();
        assertThat(flattened).as("운영 설정 파일 파싱 결과").isNotNull();
        Map<String, Object> source = new HashMap<>();
        flattened.forEach((key, value) -> source.put((String) key, value));
        return source;
    }

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
    @EnableConfigurationProperties(ClientIpProperties.class)
    static class TestConfig {}
}
