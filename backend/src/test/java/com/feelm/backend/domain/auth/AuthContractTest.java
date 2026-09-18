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

import com.feelm.backend.domain.user.entity.Gender;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedRedisContainer;
import com.feelm.backend.support.SharedRedisState;
import com.feelm.backend.support.SharedTestRuntime;
import com.redis.testcontainers.RedisContainer;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 골격의 계약을 실제 Redis에 대해 검증한다.
 *
 * <p>회전과 재사용 감지는 "키가 실제로 지워졌는가"가 핵심이라 가짜 저장소로는 확인하려는 것을 확인할 수 없다. 그래서 이 시나리오는 Testcontainers로 실제
 * Redis를 띄워 검증한다.
 */
@SpringBootTest
@SharedTestRuntime
@AutoConfigureMockMvc
@DisplayName("인증 골격 — API-000 인증 규약과 API-006 재발급")
class AuthContractTest {

    private static final String MEMBER_PATH = "/api/v1/test/security/me";
    private static final String PRIVATE_AUTH_PATH = "/api/v1/auth/private-test";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";
    private static final String RESET_REQUEST_PATH = "/api/v1/auth/password/reset-request";
    private static final String RESET_PATH = "/api/v1/auth/password/reset";
    private static final String COOKIE_NAME = "refresh_token";

    @ServiceConnection static RedisContainer redis = SharedRedisContainer.instance();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private TokenProvider tokenProvider;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @BeforeEach
    void clearRedis() {
        SharedRedisState.clear(redisTemplate);
    }

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

    /**
     * 재설정 요청은 IP 단위 발송 제한을 받는다(S15P21E106-281). 이 클래스는 요청마다 IP를 바꾸지 않아 모두 {@code 127.0.0.1}에서 나가므로,
     * 이 경로 요청을 IP 한도({@code feelm.mail.rate-limit.ip}, 테스트 설정 기준 3건)보다 많이 보내면 429로 깨진다. 늘려야 하면
     * {@link EmailAuthApiTestSupport}처럼 요청마다 remote addr을 바꾼다.
     */
    @Test
    @DisplayName("비밀번호 재설정 경로는 공개 경로로 등록되어 인증 없이 호출된다")
    void passwordResetPathsArePublic() throws Exception {
        mockMvc.perform(
                        post(RESET_REQUEST_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(
                        post(RESET_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"token\":\"none\",\"newPassword\":\"N3wP@ssw0rd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("재발급은 새 Access Token과 회전된 httpOnly Refresh 쿠키를 반환한다")
    void refreshRotatesCookieAndReturnsAccessToken() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);

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
        assertThat(tokenProvider.parseAccessToken(accessTokenOf(result))).isEqualTo(userId);
    }

    @Test
    @DisplayName("회전 직후 유예 안에 같은 Refresh Token이 다시 오면 같은 세션 토큰을 다시 내려준다")
    void duplicateRefreshWithinGraceReturnsSameSession() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);
        RefreshTokenService.IssuedTokens otherDevice = refreshTokenService.issue(userId);

        MvcResult first =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn();
        MvcResult duplicate =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(tokenIdOf(rotatedCookieValue(duplicate)))
                .isEqualTo(tokenIdOf(rotatedCookieValue(first)));
        assertThat(isStored(rotatedCookieValue(duplicate))).isTrue();
        assertThat(isStored(otherDevice.refreshToken())).isTrue();
        assertThat(tokenProvider.parseAccessToken(accessTokenOf(duplicate))).isEqualTo(userId);
        assertThat(graceTtlMillis(userId))
                .isPositive()
                .isLessThanOrEqualTo(jwtProperties.refreshRotationGrace().toMillis());
    }

    @Test
    @DisplayName("유예가 지난 뒤 폐기된 Refresh Token을 다시 쓰면 거절하고 해당 사용자의 토큰을 모두 폐기한다")
    void reusedRefreshTokenAfterGraceRevokesEverySession() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens stolen = refreshTokenService.issue(userId);
        RefreshTokenService.IssuedTokens otherDevice = refreshTokenService.issue(userId);

        MvcResult rotated =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(stolen.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn();
        String currentRefreshToken = rotatedCookieValue(rotated);
        expireGrace(userId);

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(stolen.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(COOKIE_NAME, 0))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        assertThat(isStored(currentRefreshToken)).isFalse();
        assertThat(isStored(otherDevice.refreshToken())).isFalse();
    }

    /**
     * 유예 안에 회전이 이어진 뒤 옛 토큰의 중복이 도착하는 경우다.
     *
     * <p>한 페이지 로드에서 재발급이 여러 번 나가는 것이 2026-09-17 QA의 관측이었으므로(D-3), 중복이 도착하기 전에 다른 요청이 한 번 더 회전하는 것은
     * 흔하다. 유예가 한 세대만 덮으면 바로 이 경우에 세션이 전멸한다(리뷰 A-2·B-2).
     */
    @Test
    @DisplayName("유예 안에 회전이 이어져도 옛 토큰의 중복 도착은 현재 세션을 돌려받는다")
    void duplicateRefreshFollowsRotationChainWithinGrace() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);

        MvcResult first =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn();
        MvcResult second =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(rotatedCookieValue(first))))
                        .andExpect(status().isOk())
                        .andReturn();

        MvcResult late =
                mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(tokenIdOf(rotatedCookieValue(late)))
                .isEqualTo(tokenIdOf(rotatedCookieValue(second)));
        assertThat(isStored(rotatedCookieValue(late))).isTrue();
    }

    /**
     * 전량 폐기 직후 다시 로그인한 세션은 옛 중복에 끊기지 않아야 한다.
     *
     * <p>유예를 토큰마다 따로 저장하면 {@link RefreshTokenService#revokeAll}이 그것을 지울 수 없어, 뒤늦게 도착한 중복 하나가 방금 만든
     * 새 세션을 재사용으로 몰아 폐기한다(리뷰 B-3).
     */
    @Test
    @DisplayName("전량 폐기 뒤 다시 로그인한 세션은 옛 토큰의 중복 도착에 끊기지 않는다")
    void revocationClearsGraceSoNewSessionSurvivesLateDuplicate() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                .andExpect(status().isOk());
        // 비밀번호 변경(API-012)이나 로그아웃 뒤 다시 로그인한 상황이다
        refreshTokenService.revokeAll(userId);
        RefreshTokenService.IssuedTokens reLogin = refreshTokenService.issue(userId);

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        assertThat(isStored(reLogin.refreshToken())).isTrue();
    }

    @Test
    @DisplayName("저장소에 세션이 없으면 폐기할 것 없이 거절한다")
    void rotationWithoutStoredSessionIsRejected() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);
        refreshTokenRepository.deleteAll(userId);

        RefreshTokenRepository.Rotation rotation =
                refreshTokenRepository.rotate(
                        userId, tokenIdOf(issued.refreshToken()), UUID.randomUUID().toString());

        assertThat(rotation.outcome()).isEqualTo(RefreshTokenRepository.Outcome.UNKNOWN_SESSION);
        assertThat(rotation.accepted()).isFalse();

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("전량 폐기 뒤에는 유예가 남아 있어도 세션이 되살아나지 않는다")
    void graceDoesNotResurrectRevokedSession() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                .andExpect(status().isOk());
        // 비밀번호 변경(API-012)과 탈퇴(API-013)가 하는 일이다
        refreshTokenService.revokeAll(userId);

        mockMvc.perform(post(REFRESH_PATH).cookie(cookieOf(issued.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    /**
     * 중복 도착을 유예가 흡수하므로 동시 회전은 더 이상 한 건만 살아남지 않는다.
     *
     * <p>전에는 "성공 1건, 나머지 전량 폐기"를 고정했다(S15P21E106-596 이전). 그것이 QA에서 관측된 세션 전멸의 원인이었으므로 기대값을 "모두 같은
     * 세션을 받고 그 세션이 살아 있다"로 바꿨다.
     */
    @Test
    @DisplayName("같은 Refresh Token으로 동시에 회전해도 모두 같은 세션을 받고 세션이 살아 있다")
    void concurrentRotationKeepsSessionAlive() throws Exception {
        long userId = activeUserId();
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(userId);
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

        List<String> tokenIds = new ArrayList<>();
        for (Future<RefreshTokenService.IssuedTokens> result : results) {
            assertThat(result.get()).isNotNull();
            tokenIds.add(tokenIdOf(result.get().refreshToken()));
        }
        assertThat(tokenIds).hasSize(attempts).containsOnly(tokenIds.get(0));
        assertThat(refreshTokenRepository.exists(userId, tokenIds.get(0))).isTrue();
    }

    @Test
    @DisplayName("발급한 Refresh Token 식별자가 Redis에 저장된다")
    void issuedRefreshTokenIsStoredInRedis() {
        RefreshTokenService.IssuedTokens issued = refreshTokenService.issue(activeUserId());

        assertThat(isStored(issued.refreshToken())).isTrue();
    }

    /**
     * 회전에 쓸 활성 계정을 만든다.
     *
     * <p>예전에는 DB에 없는 식별자로 토큰만 발급해 Redis 동작만 봤다. 회전이 계정 상태를 확인하게 되면서(API-006 처리 규칙, S15P21E106-198)
     * 실재하는 활성 계정이 필요하다. 검증 대상은 그대로 Redis의 회전·재사용 감지다.
     */
    private long activeUserId() {
        String suffix = String.format("%06d", SEQUENCE.incrementAndGet());
        User user =
                User.register(
                        "auth-contract" + suffix + "@example.com",
                        "$2a$10$hash",
                        "auth" + suffix,
                        LocalDate.of(1999, 3, 14),
                        Gender.FEMALE);
        return userRepository.saveAndFlush(user).getId();
    }

    private String expiredAccessToken() {
        JjwtTokenProvider expiring =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                Duration.ofSeconds(-1),
                                jwtProperties.refreshTokenTtl(),
                                jwtProperties.signupTokenTtl(),
                                jwtProperties.emailVerificationTokenTtl(),
                                jwtProperties.refreshRotationGrace(),
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
                                jwtProperties.signupTokenTtl(),
                                jwtProperties.emailVerificationTokenTtl(),
                                jwtProperties.refreshRotationGrace(),
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

    private String tokenIdOf(String refreshToken) {
        return tokenProvider.parseRefreshToken(refreshToken).tokenId();
    }

    /** 유예 Hash는 {@link RedisRefreshTokenRepository}가 만든다. 형식을 바꾸면 이 도우미들도 함께 고친다. */
    private String graceKey(long userId) {
        return "auth:refresh:grace:" + userId;
    }

    private Long graceTtlMillis(long userId) {
        return redisTemplate.getExpire(graceKey(userId), TimeUnit.MILLISECONDS);
    }

    /** 유예가 지난 상황을 기다리지 않고 만든다. 유예 길이 자체는 TTL 단정으로 본다. */
    private void expireGrace(long userId) {
        redisTemplate.delete(graceKey(userId));
    }

    private Cookie cookieOf(String value) {
        return new Cookie(COOKIE_NAME, value);
    }
}
