package com.feelm.backend.domain.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@link ClientIpEchoController}의 경로만 인증 없이 열어 주는 검증 전용 설정이다.
 *
 * <p>운영 {@code SecurityConfig}의 기본 정책은 "인증이 필요함"이고 그 허용 목록은 공통 계약이다. 검증용 경로를 그 목록에 넣지 않으려고 별도 체인을
 * 앞에 둔다. {@code @TestConfiguration}이라 컴포넌트 스캔에 걸리지 않으므로 이 설정을 {@code @Import}한 테스트에만 적용된다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ClientIpEchoSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain clientIpEchoSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(
                        ClientIpEchoController.PATH, ClientIpEchoController.SERVER_NAME_PATH)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
