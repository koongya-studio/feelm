package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-004 이메일 로그인의 계약을 검증한다.
 *
 * <p>실패 사유(없는 이메일·비밀번호 불일치·탈퇴)는 계정 존재 여부를 노출하지 않도록 모두 {@code INVALID_CREDENTIALS}여야 하므로 각 경로가 같은
 * 코드로 응답하는지 함께 본다. 연속 실패 제한은 결정 4의 5회/10분이다.
 */
@DisplayName("이메일 로그인 — API-004")
class LoginContractTest extends EmailAuthApiTestSupport {

    @Autowired private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("활성 계정은 200과 Access Token, 사용자 요약, Refresh 쿠키를 받는다")
    void activeAccountLoginReturnsTokensAndCookie(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);

        MvcResult result =
                login(account.email(), account.password())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.expiresIn").value(1800))
                        .andExpect(jsonPath("$.user.userId").value(userId))
                        .andExpect(jsonPath("$.user.nickname").value(account.nickname()))
                        .andExpect(jsonPath("$.user.onboardingCompleted").value(false))
                        .andExpect(cookie().exists(COOKIE_NAME))
                        .andExpect(cookie().httpOnly(COOKIE_NAME, true))
                        .andExpect(cookie().path(COOKIE_NAME, "/api/v1/auth"))
                        .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                        .andReturn();

        // 응답의 Access Token이 실제로 회원 API에 통하는 값인지까지 확인한다.
        mockMvc.perform(
                        get(MEMBER_PATH)
                                .header(
                                        "Authorization",
                                        "Bearer " + stringField(result, "accessToken")))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(userId)));
    }

    @Test
    @DisplayName("이메일 대소문자가 달라도 같은 계정으로 로그인된다")
    void loginNormalizesEmailCase(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);

        login(account.email().toUpperCase(), account.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(userId));
    }

    @Test
    @DisplayName("비밀번호가 다르면 401 INVALID_CREDENTIALS이며 쿠키를 발급하지 않는다")
    void wrongPasswordReturnsInvalidCredentials(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);

        login(account.email(), "Wr0ng@pass")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(cookie().doesNotExist(COOKIE_NAME));
    }

    @Test
    @DisplayName("가입하지 않은 이메일도 같은 401 INVALID_CREDENTIALS로 응답한다")
    void unknownEmailReturnsInvalidCredentials() throws Exception {
        login(newAccount().email(), VALID_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("탈퇴한 계정도 같은 401 INVALID_CREDENTIALS로 응답한다")
    void withdrawnAccountReturnsInvalidCredentials(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        withdrawUser(signupActive(account, output));

        login(account.email(), account.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("연속 5회 실패하면 이후 요청은 비밀번호가 맞아도 429 RATE_LIMITED다")
    void sixthAttemptAfterFiveFailuresIsRateLimited(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);

        for (int attempt = 1; attempt <= 5; attempt++) {
            login(account.email(), "Wr0ng@pass")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        login(account.email(), account.password())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * 확인과 기록이 분리되어 있으면 실패 4회 상태의 동시 요청이 모두 확인을 통과해 전부 401이 된다. 시도를 원자적으로 먼저 세므로 다섯 번째 시도 하나만 대조되고
     * 나머지는 429여야 한다.
     */
    @Test
    @DisplayName("실패 4회 상태에서 동시에 요청하면 한 건만 401이고 나머지는 429 RATE_LIMITED다")
    void concurrentAttemptsBeyondLimitAreRateLimited(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(account.email(), "Wr0ng@pass").andExpect(status().isUnauthorized());
        }

        int attempts = 4;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return login(account.email(), "Wr0ng@pass")
                                            .andReturn()
                                            .getResponse();
                                }));
            }
            ready.await();
            start.countDown();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(responses).filteredOn(response -> response.getStatus() == 401).hasSize(1);
        assertThat(responses)
                .filteredOn(response -> response.getStatus() != 401)
                .hasSize(attempts - 1)
                .allSatisfy(
                        response -> {
                            assertThat(response.getStatus()).isEqualTo(429);
                            assertThat(response.getContentAsString()).contains("RATE_LIMITED");
                        });
    }

    @Test
    @DisplayName("실패 카운터에는 첫 실패부터 10분 만료가 걸려 있어 무기한 차단되지 않는다")
    void failureCounterExpires(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);

        login(account.email(), "Wr0ng@pass").andExpect(status().isUnauthorized());

        String key = "auth:login-fail:" + Sha256Hasher.hex(account.email());
        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(ttlSeconds).isNotNull().isBetween(1L, 600L);
    }

    @Test
    @DisplayName("로그인에 성공하면 실패 카운터가 초기화된다")
    void successfulLoginResetsFailureCounter(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        for (int attempt = 1; attempt <= 4; attempt++) {
            login(account.email(), "Wr0ng@pass").andExpect(status().isUnauthorized());
        }

        login(account.email(), account.password()).andExpect(status().isOk());

        // 카운터가 초기화되지 않았다면 아래 5회 중 두 번째부터 429가 돌아온다.
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(account.email(), "Wr0ng@pass")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @ParameterizedTest(name = "본문 {0}")
    @ValueSource(
            strings = {
                "{}",
                "{\"email\":\"user@example.com\"}",
                "{\"email\":\"\",\"password\":\"\"}"
            })
    @DisplayName("이메일이나 비밀번호가 없으면 400 VALIDATION_FAILED다")
    void missingCredentialsReturnValidationFailed(String body) throws Exception {
        postJson(LOGIN_PATH, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
