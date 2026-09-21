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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.UrlPathHelper;

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
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 인증 없이 호출할 수 있는 경로. 도메인별 공개 API는 여기에 추가한다.
     *
     * <p>로그아웃(API-005)은 회원 구분이므로 넣지 않는다. 자기 세션만 끊을 수 있어야 하기 때문이다.
     */
    private static final String[] PUBLIC_PATHS = {
        "/api/v1/auth/signup",
        // API-078·076 가입 인증번호 발송·확인. 계정을 만들기 전 단계라 공개다.
        "/api/v1/auth/email/verification-code",
        "/api/v1/auth/email/verification-code/verify",
        "/api/v1/auth/login",
        "/api/v1/auth/token/refresh",
        // 소셜 로그인(API-007)과 소셜 회원가입 완료(API-008). 계정 연결(API-009)은 회원 구분이라
        // /api/v1/users/me/oauth/** 로 따로 두고 여기에 넣지 않는다.
        "/api/v1/auth/oauth/*",
        "/api/v1/auth/password/reset-request",
        "/api/v1/auth/password/reset"
    };

    /**
     * GET만 공개인 조회 API. 메서드를 함께 묶어 같은 경로에 쓰기 계열이 생겨도 자동으로 열리지 않게 한다(리뷰 B-3).
     *
     * <p>API-044 팝콘 맛 기준정보, API-016 닉네임 사용 가능 확인, API-025 OTT 목록, API-029 검색 필터 옵션, API-064 급상승 영화가
     * 여기에 속한다. 닉네임 확인은 회원 구분인 {@code /api/v1/users/me} 계열과 접두사를 공유하므로 와일드카드 없이 정확 경로만 적는다. OTT도 같은
     * 이유로 구독 설정 경로({@code /api/v1/users/me/otts})와 분리된 정확 경로만 연다.
     */
    private static final String[] PUBLIC_GET_PATHS = {
        "/api/v1/popcorn-flavors",
        "/api/v1/users/nickname-availability",
        "/api/v1/otts",
        "/api/v1/movies/filter-options",
        "/api/v1/movies/trending"
    };

    /** Nginx가 별도 접근 제어하는 Swagger 문서 경로. 일반 API 공개 목록과 분리한다. */
    private static final String[] SWAGGER_PATHS = {
        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**"
    };

    /** OpenAPI가 런타임과 같은 공개 API 계약을 표시할 때 사용하는 판정이다. */
    public static boolean isPublicApi(HttpMethod method, String path) {
        if (isOptionalAuthenticationApi(method, path)) {
            return true;
        }
        if (HttpMethod.GET.equals(method)) {
            for (String getOnlyPath : PUBLIC_GET_PATHS) {
                if (getOnlyPath.equals(path)) {
                    return true;
                }
            }
        }
        for (String pattern : PUBLIC_PATHS) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** API-030과 API-031은 선택 인증이다. 정적 검색·필터·급상승 경로는 영화 ID 템플릿에 포함하지 않는다. */
    public static boolean isOptionalAuthenticationApi(HttpMethod method, String path) {
        return HttpMethod.GET.equals(method)
                && !path.equals("/api/v1/movies/search")
                && !path.equals("/api/v1/movies/filter-options")
                && !path.equals("/api/v1/movies/trending")
                && (PATH_MATCHER.match("/api/v1/movies/{movieId}", path)
                        || PATH_MATCHER.match("/api/v1/movies/{movieId}/similar", path));
    }

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
                                        // Prometheus가 JWT 없이 수집한다. 백엔드는 내부 Compose
                                        // 네트워크에만 있고 Nginx는 이 경로를 프록시하지 않는다.
                                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, SWAGGER_PATHS)
                                        .permitAll()
                                        // API-029 등 공개 GET과 API-030 선택 인증을 같은 정책으로 판정한다.
                                        // context path·URL 인코딩도 정규화한다.
                                        .requestMatchers(
                                                request ->
                                                        isPublicApi(
                                                                HttpMethod.valueOf(
                                                                        request.getMethod()),
                                                                UrlPathHelper.defaultInstance
                                                                        .getPathWithinApplication(
                                                                                request)))
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

    /**
     * 비밀번호 해시 방식. BCrypt를 쓰고 강도는 기본값(10)을 유지한다.
     *
     * <p>{@code PasswordEncoder} 인터페이스로 등록해 두면 나중에 알고리즘을 바꿔도 호출 측이 영향을 받지 않는다. 저장된 해시에 알고리즘 식별자가
     * 필요해지면 {@code DelegatingPasswordEncoder}로 교체한다.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
