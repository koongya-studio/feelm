package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.redis.testcontainers.RedisContainer;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 골격의 계약을 실제 Redis에 대해 검증한다.
 *
 * <p>회전과 재사용 감지는 "키가 실제로 지워졌는가"가 핵심이라 가짜 저장소로는 확인하려는 것을 확인할 수 없다. 그래서 이 시나리오는 Testcontainers로 실제
 * Redis를 띄워 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("인증 골격 — API-000 인증 규약과 API-006 재발급")
class AuthContractTest {

    private static final String MEMBER_PATH = "/api/v1/test/security/me";
    private static final String PRIVATE_AUTH_PATH = "/api/v1/auth/private-test";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    private static final String COOKIE_NAME = "refresh_token";

    @Container @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:8-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("토큰 없이 회원 API를 호출하면 401 UNAUTHORIZED로 응답한다")
    void missingTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(MEMBER_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("서명이 맞지 않는 토큰은 401 UNAUTHORIZED로 응답한다")
    void tamperedTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(MEMBER_PATH).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("만료된 토큰은 401 TOKEN_EXPIRED로 구분해 응답한다")
    void expiredTokenReturnsTokenExpired() throws Exception {
        mockMvc.perform(get(MEMBER_PATH).header("Authorization", "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 호출하면 현재 사용자가 컨트롤러에 주입된다")
    void validTokenExposesCurrentUser() throws Exception {
        String accessToken = tokenProvider.createAccessToken(7L);

        mockMvc.perform(get(MEMBER_PATH).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().string("7"));
    }

    @Test
    @DisplayName("Refresh 쿠키가 없으면 401 REFRESH_TOKEN_INVALID로 응답한다")
    void missingRefreshCookieReturnsInvalid() throws Exception {
        mockMvc.perform(post(REFRESH_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(COOKIE_NAME, 0))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("만료된 Refresh Token은 401 REFRESH_TOKEN_EXPIRED로 구분하고 쿠키를 지운다")
    void expiredRefreshCookieReturnsExpiredAndClearsCookie() throws Exception {
        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(expiredRefreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(COOKIE_NAME, 0))
                .andExpect(cookie().value(COOKIE_NAME, ""))
                .andExpect(cookie().path(COOKIE_NAME, "/api/v1/auth"))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("명시적으로 공개하지 않은 auth 경로는 인증이 필요하다")
    void unspecifiedAuthPathRequiresAuthentication() throws Exception {
        mockMvc.perform(get(PRIVATE_AUTH_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("재발급은 새 Access Token과 회전된 httpOnly Refresh 쿠키를 반환한다")
    void refreshRotatesCookieAndReturnsAccessToken() throws Exception {
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(11L);

        MvcResult result =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.expiresIn").value(1800))
                        .andExpect(cookie().exists(COOKIE_NAME))
                        .andExpect(cookie().httpOnly(COOKIE_NAME, true))
                        .andExpect(cookie().path(COOKIE_NAME, "/api/v1/auth"))
                        .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                        .andReturn();

        assertThat(rotatedCookieValue(result)).isNotEqualTo(issued.refreshToken());
        assertThat(tokenProvider.parseAccessToken(accessTokenOf(result))).isEqualTo(11L);
    }

    @Test
    @DisplayName("회전으로 폐기된 Refresh Token을 다시 쓰면 거절하고 해당 사용자의 토큰을 모두 폐기한다")
    void reusedRefreshTokenRevokesEverySessionOfUser() throws Exception {
        RefreshTokenService.IssuedTokens stolen = refreshTokenService.issue(12L);
        RefreshTokenService.IssuedTokens otherDevice = refreshTokenService.issue(12L);

        MvcResult rotated =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(stolen.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn();
        String currentRefreshToken = rotatedCookieValue(rotated);

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(stolen.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(COOKIE_NAME, 0))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        assertThat(isStored(currentRefreshToken)).isFalse();
        assertThat(isStored(otherDevice.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("같은 Refresh Token으로 동시에 회전해도 한 번만 성공한다")
    void concurrentRotationSucceedsOnlyOnce() throws Exception {
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(14L);
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<RefreshTokenService.IssuedTokens>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    return refreshTokenService.rotate(issued.refreshToken());
                                } catch (BusinessException exception) {
                                    return null;
                                }
                            }));
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long succeeded = 0;
        RefreshTokenService.IssuedTokens rotated = null;
        for (Future<RefreshTokenService.IssuedTokens> result : results) {
            if (result.get() != null) {
                succeeded++;
                rotated = result.get();
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(rotated).isNotNull();
        assertThat(isStored(rotated.refreshToken())).isFalse();
    }

    @Test
    @DisplayName("발급한 Refresh Token 식별자가 Redis에 저장된다")
    void issuedRefreshTokenIsStoredInRedis() {
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(13L);

        assertThat(isStored(issued.refreshToken())).isTrue();
    }

    private String expiredAccessToken() {
        JjwtTokenProvider expiring =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                Duration.ofSeconds(-1),
                                jwtProperties.refreshTokenTtl(),
                                jwtProperties.refreshCookie()));
        return expiring.createAccessToken(9L);
    }

    /** Redis 조회 이전에 만료가 판정되므로 저장 없이 만든 토큰으로 검증한다. */
    private String expiredRefreshToken() {
        JjwtTokenProvider expiring =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                jwtProperties.accessTokenTtl(),
                                Duration.ofSeconds(-1),
                                jwtProperties.refreshCookie()));
        return expiring.createRefreshToken(15L, UUID.randomUUID().toString());
    }

    private boolean isStored(String refreshToken) {
        TokenProvider.RefreshTokenClaims claims = tokenProvider.parseRefreshToken(refreshToken);
        return refreshTokenRepository.exists(claims.userId(), claims.tokenId());
    }

    private String rotatedCookieValue(MvcResult result) {
        return result.getResponse().getCookie(COOKIE_NAME).getValue();
    }

    private String accessTokenOf(MvcResult result) throws Exception {
        return objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
    }

    private Cookie cookieOf(String value) {
        return new Cookie(COOKIE_NAME, value);
    }
}
