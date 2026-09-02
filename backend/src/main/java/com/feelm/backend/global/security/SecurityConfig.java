package com.feelm.backend.global.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 인증·인가 필터 체인을 구성한다.
 *
 * <p><b>공통 계약 파일이다.</b> 기본 정책은 "인증이 필요함"이며, 비회원도 호출할 수 있는 <b>공개</b>·<b>선택</b> 구분의 API는 각 도메인 담당이 이
 * 파일의 허용 목록에 경로를 추가해야 한다. 기본값을 허용으로 두면 규칙을 빠뜨린 새 API가 조용히 열리므로, 반대로 막아 두고 열도록 했다. 변경은 {@code
 * AGENTS.md} 5장에 따라 담당 간 합의 후 진행한다.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthProperties.class})
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** 인증 없이 호출할 수 있는 경로. 도메인별 공개 API는 여기에 추가한다. */
    private static final String[] PUBLIC_PATHS = {"/api/v1/auth/token/refresh"};

    private final TokenProvider tokenProvider;
    private final AuthProperties authProperties;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            TokenProvider tokenProvider,
            AuthProperties authProperties,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JwtAccessDeniedHandler accessDeniedHandler) {
        this.tokenProvider = tokenProvider;
        this.authProperties = authProperties;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.GET, "/actuator/health")
                                        .permitAll()
                                        .requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        handling ->
                                handling.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        if (authProperties.mock().enabled()) {
            log.warn(
                    "개발용 mock 인증이 켜져 있습니다. 인증 없는 요청이 사용자 {}로 처리됩니다.",
                    authProperties.mock().userId());
            http.addFilterAfter(
                    new MockAuthenticationFilter(authProperties.mock().userId()),
                    JwtAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * Refresh Token 쿠키를 주고받으려면 {@code allowCredentials}가 켜져 있어야 하며, 이때 브라우저는 와일드카드 Origin을 거부한다.
     * 그래서 허용 Origin은 환경별 설정으로만 지정하고 기본값을 두지 않는다.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(authProperties.allowedOrigins());
        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
