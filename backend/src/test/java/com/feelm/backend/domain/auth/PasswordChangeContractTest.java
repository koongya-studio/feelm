package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API-012 비밀번호 변경의 계약을 검증한다.
 *
 * <p>회원 전용이므로 Access Token으로 호출한다. 세션 폐기 범위는 플랜 7장 결정 A-1에 따라 <b>전체 폐기</b>다. 현재 세션만 남기는 계약 초안은 이
 * 엔드포인트에서 현재 세션을 식별할 수 없어(Refresh 쿠키 {@code Path}가 {@code /api/v1/auth}, Access Token에 {@code jti}
 * 없음) 채택하지 않았고, {@code docs/api/users.md}의 처리 규칙도 함께 정정했다.
 */
@DisplayName("비밀번호 변경 — API-012")
class PasswordChangeContractTest extends EmailAuthApiTestSupport {

    private static final String NEW_PASSWORD = "N3wP@ssw0rd";

    @Test
    @DisplayName("현재 비밀번호가 맞으면 204이고 새 비밀번호로만 로그인된다")
    void correctCurrentPasswordChangesPassword(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        MvcResult loggedIn = loginResult(account);

        changePassword(loggedIn, account.password(), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        login(account.email(), NEW_PASSWORD).andExpect(status().isOk());
        login(account.email(), account.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 401 INVALID_CREDENTIALS이고 비밀번호는 바뀌지 않는다")
    void wrongCurrentPasswordReturnsInvalidCredentials(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        MvcResult loggedIn = loginResult(account);

        changePassword(loggedIn, "Wr0ng@Pass", NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        login(account.email(), account.password()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("정책에 어긋난 새 비밀번호는 400 VALIDATION_FAILED이고 비밀번호는 바뀌지 않는다")
    void weakNewPasswordReturnsValidationFailed(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        MvcResult loggedIn = loginResult(account);

        changePassword(loggedIn, account.password(), "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        login(account.email(), account.password()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Access Token이 없으면 401 UNAUTHORIZED다")
    void requestWithoutAccessTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(
                        put(CHANGE_PASSWORD_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(changeBody(VALID_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("비밀번호가 없는 계정은 409 PASSWORD_NOT_SET이다")
    void passwordlessAccountReturnsPasswordNotSet(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult loggedIn = loginResult(account);
        clearPassword(userId);

        changePassword(loggedIn, account.password(), NEW_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_NOT_SET"));
    }

    @Test
    @DisplayName("탈퇴한 계정의 Access Token은 잔여 수명 안이라도 401 UNAUTHORIZED다")
    void withdrawnAccountReturnsUnauthorized(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult loggedIn = loginResult(account);
        withdrawUser(userId);

        changePassword(loggedIn, account.password(), NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("비밀번호가 없는 계정은 재설정 토큰도 무효화되지 않는다")
    void passwordlessAccountDoesNotInvalidateResetToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult loggedIn = loginResult(account);
        requestPasswordResetToken(account.email());
        clearPassword(userId);

        changePassword(loggedIn, account.password(), NEW_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PASSWORD_NOT_SET"));

        assertThat(unusedPasswordResetTokenCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴한 계정은 재설정 토큰도 무효화되지 않는다")
    void withdrawnAccountDoesNotInvalidateResetToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult loggedIn = loginResult(account);
        requestPasswordResetToken(account.email());
        withdrawUser(userId);

        changePassword(loggedIn, account.password(), NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(unusedPasswordResetTokenCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("변경하면 미사용 비밀번호 재설정 토큰도 모두 무효화된다")
    void changeInvalidatesOutstandingResetToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult loggedIn = loginResult(account);
        String resetToken = requestPasswordResetToken(account.email());

        changePassword(loggedIn, account.password(), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        assertThat(unusedPasswordResetTokenCount(userId)).isEqualTo(0);
        resetPassword(resetToken, "An0ther@Pass")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 재설정 토큰은 무효화되지 않는다")
    void wrongCurrentPasswordDoesNotInvalidateResetToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long userId = signupActive(account, output);
        MvcResult loggedIn = loginResult(account);
        requestPasswordResetToken(account.email());

        changePassword(loggedIn, "Wr0ng@Pass", NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        assertThat(unusedPasswordResetTokenCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("변경하면 해당 사용자의 모든 Refresh Token이 폐기된다")
    void changeRevokesAllRefreshTokens(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        MvcResult first = loginResult(account);
        Cookie firstCookie = refreshCookieOf(first);
        Cookie secondCookie = refreshCookieOf(loginResult(account));

        changePassword(first, account.password(), NEW_PASSWORD).andExpect(status().isNoContent());

        expectRefreshRejected(firstCookie);
        expectRefreshRejected(secondCookie);
    }

    private MvcResult loginResult(TestAccount account) throws Exception {
        return login(account.email(), account.password()).andExpect(status().isOk()).andReturn();
    }

    private ResultActions changePassword(
            MvcResult loggedIn, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(
                put(CHANGE_PASSWORD_PATH)
                        .header("Authorization", "Bearer " + stringField(loggedIn, "accessToken"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(currentPassword, newPassword)));
    }

    private static String changeBody(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(currentPassword, newPassword);
    }

    private void expectRefreshRejected(Cookie refreshCookie) throws Exception {
        mockMvc.perform(post(REFRESH_PATH).cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }
}
