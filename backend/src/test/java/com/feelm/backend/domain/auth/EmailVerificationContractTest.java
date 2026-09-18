package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * API-002 이메일 인증 확인의 계약을 검증한다.
 *
 * <p>토큰은 불투명 랜덤이고 DB에는 해시만 남으므로(결정 8) 테스트는 토큰 원문을 만들지 않고 발송된 값을 그대로 쓴다. 만료와 '이미 활성' 상태는 API로 만들 방법이
 * 없어 저장된 토큰 행을 테스트 전용 SQL로 조정한다.
 */
@DisplayName("이메일 인증 확인 — API-002")
class EmailVerificationContractTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("유효한 토큰은 200과 함께 계정을 ACTIVE로 전이시킨다")
    void validTokenActivatesAccount() throws Exception {
        TestAccount account = newAccount();
        long userId = signupPending(account);

        verifyEmail(pendingVerificationToken(account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(userStatus(userId)).isEqualTo("ACTIVE");
    }

    /** 동시 요청을 한 지점에서 출발시켜 조회와 소진 표시 사이의 틈을 노린다. 토큰 행 잠금이 없으면 두 요청이 모두 미사용 상태를 읽고 함께 성공할 수 있다. */
    @Test
    @DisplayName("같은 토큰으로 동시에 인증해도 한 요청만 성공하고 나머지는 400 VERIFICATION_TOKEN_INVALID다")
    void concurrentVerificationConsumesTokenOnce() throws Exception {
        TestAccount account = newAccount();
        long userId = signupPending(account);
        String token = pendingVerificationToken(account);
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
                                    return verifyEmail(token).andReturn().getResponse();
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

        assertThat(responses).filteredOn(response -> response.getStatus() == 200).hasSize(1);
        assertThat(responses)
                .filteredOn(response -> response.getStatus() != 200)
                .hasSize(attempts - 1)
                .allSatisfy(
                        response -> {
                            assertThat(response.getStatus()).isEqualTo(400);
                            assertThat(response.getContentAsString())
                                    .contains("VERIFICATION_TOKEN_INVALID");
                        });
        assertThat(userStatus(userId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("토큰은 1회용이므로 같은 토큰을 다시 쓰면 400 VERIFICATION_TOKEN_INVALID다")
    void usedTokenIsRejected() throws Exception {
        TestAccount account = newAccount();
        signupPending(account);
        String token = pendingVerificationToken(account);
        verifyEmail(token).andExpect(status().isOk());

        verifyEmail(token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("발급하지 않은 토큰은 400 VERIFICATION_TOKEN_INVALID다")
    void unknownTokenIsRejected() throws Exception {
        verifyEmail("Zm9yZ2VkLXRva2VuLXRoYXQtd2FzLW5ldmVyLWlzc3VlZA")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @ParameterizedTest(name = "본문 {0}")
    @ValueSource(strings = {"{}", "{\"token\":\"\"}", "{\"token\":\"   \"}"})
    @DisplayName("token이 없거나 비어 있으면 400 VALIDATION_FAILED다")
    void missingTokenReturnsValidationFailed(String body) throws Exception {
        postJson(VERIFY_PATH, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("만료된 토큰은 410 VERIFICATION_TOKEN_EXPIRED이며 계정은 미인증으로 남는다")
    void expiredTokenReturnsGone() throws Exception {
        TestAccount account = newAccount();
        long userId = signupPending(account);
        String token = pendingVerificationToken(account);
        expireVerificationTokens(userId);

        verifyEmail(token)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERIFICATION_TOKEN_EXPIRED"));

        assertThat(userStatus(userId)).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    @DisplayName("이미 활성화된 계정의 유효한 토큰은 409 ALREADY_VERIFIED다")
    void alreadyVerifiedAccountReturnsConflict() throws Exception {
        TestAccount account = newAccount();
        long userId = signupPending(account);
        String token = pendingVerificationToken(account);
        verifyEmail(token).andExpect(status().isOk());
        // 활성 계정 + 미사용·미만료 토큰이 겹친 상태는 토큰 원문을 새로 만들 수 없어 API로 구성할 수 없다.
        restoreVerificationTokens(userId);

        verifyEmail(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }
}
