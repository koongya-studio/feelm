package com.feelm.backend.infrastructure.oauth;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 설정된 제공자만 {@link OAuthClients}에 등록한다.
 *
 * <p>제공자 호출에는 연결·응답 상한을 둔다. 기본값은 무한 대기라, 제공자가 응답하지 않으면 로그인 요청 스레드가 그대로 묶인다.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientConfig.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    OAuthClients oAuthClients(OAuthProperties properties) {
        RestClient restClient = restClient();
        Map<OAuthProvider, OAuthClient> clients = new EnumMap<>(OAuthProvider.class);
        properties
                .providers()
                .forEach(
                        (provider, registration) -> {
                            if (registration != null && registration.isConfigured()) {
                                clients.put(provider, create(provider, registration, restClient));
                            }
                        });
        if (clients.size() < OAuthProvider.values().length) {
            log.info("소셜 로그인에 사용할 수 있는 제공자: {}", clients.keySet());
        }
        return new OAuthClients(clients);
    }

    private OAuthClient create(
            OAuthProvider provider,
            OAuthProperties.Registration registration,
            RestClient restClient) {
        return switch (provider) {
            case GOOGLE -> new GoogleOAuthClient(restClient, registration);
            case KAKAO -> new KakaoOAuthClient(restClient, registration);
            case NAVER -> new NaverOAuthClient(restClient, registration);
        };
    }

    private RestClient restClient() {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
