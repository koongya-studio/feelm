package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-011 비밀번호 재설정의 계약을 검증한다.
 *
 * <p>비밀번호가 실제로 바뀌었는지는 DB를 들여다보지 않고 로그인(API-004)이 새 비밀번호로만 통과하는지로 확인한다. 토큰 폐기도 같은 방식으로, 폐기된 Refresh
 * Token으로 재발급(API-006)이 거절되는지로 확인한다.
 */
@DisplayName("비밀번호 재설정 — API-011")
class PasswordResetContractTest extends EmailAuthApiTestSupport {

    private static final String NEW_PASSWORD = "N3wP@ssw0rd";

    @Test
    @DisplayName("유효한 토큰으로 재설정하면 204이고 새 비밀번호로만 로그인된다")
    void validTokenResetsPassword(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        String token = requestPasswordResetToken(account.email());

        resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login(account.email(), NEW_PASSWORD).andExpect(status().isOk());
        login(account.email(), account.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("재설정하면 해당 사용자의 모든 Refresh Token이 폐기된다")
    void resetRevokesAllRefreshTokens(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        Cookie first = refreshCookieOf(loginResult(account));
        Cookie second = refreshCookieOf(loginResult(account));
        String token = requestPasswordResetToken(account.email());

        resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

        expectRefreshRejected(first);
        expectRefreshRejected(second);
    }

    @Test
    @DisplayName("한 번 쓴 토큰을 다시 쓰면 400 RESET_TOKEN_INVALID다")
    void reusedTokenReturnsResetTokenInvalid(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        String token = requestPasswordResetToken(account.email());
        resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());

        resetPassword(token, "An0ther@Pass")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("만료된 토큰은 410 RESET_TOKEN_EXPIRED로 구분해 응답한다")
    void expiredTokenReturnsResetTokenExpired(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        String token = requestPasswordResetToken(account.email());
        expirePasswordResetTokens(userId);

        resetPassword(token, NEW_PASSWORD)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("발급되지 않은 토큰은 400 RESET_TOKEN_INVALID다")
    void unknownTokenReturnsResetTokenInvalid() throws Exception {
        resetPassword("not-an-issued-token", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("정책에 어긋난 새 비밀번호는 400 VALIDATION_FAILED이고 토큰도 소진되지 않는다")
    void weakNewPasswordReturnsValidationFailed(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        String token = requestPasswordResetToken(account.email());

        resetPassword(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        login(account.email(), account.password()).andExpect(status().isOk());
        assertThat(unusedPasswordResetTokenCount(userId)).as("토큰은 소진되지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴한 계정의 토큰은 400 RESET_TOKEN_INVALID다")
    void withdrawnAccountTokenReturnsResetTokenInvalid(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        String token = requestPasswordResetToken(account.email());
        withdrawUser(userId);

        resetPassword(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
    }

    private MvcResult loginResult(TestAccount account) throws Exception {
        return login(account.email(), account.password()).andExpect(status().isOk()).andReturn();
    }

    private void expectRefreshRejected(Cookie refreshCookie) throws Exception {
        mockMvc.perform(post(REFRESH_PATH).cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }
}
