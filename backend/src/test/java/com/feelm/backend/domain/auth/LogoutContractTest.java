package com.feelm.backend.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-005 로그아웃의 계약을 검증한다.
 *
 * <p>로그아웃은 회원 인증이 필요하고(플랜 2.8) 쿠키가 없거나 이미 폐기됐어도 204로 응답한다(결정 12). 폐기 여부는 Redis를 직접 들여다보지 않고 폐기된
 * Refresh Token으로 재발급(API-006)이 거절되는지로 확인한다.
 */
@DisplayName("로그아웃 — API-005")
class LogoutContractTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("로그아웃하면 204와 함께 쿠키를 지우고 Refresh Token을 폐기한다")
    void logoutClearsCookieAndRevokesRefreshToken(CapturedOutput output) throws Exception {
        MvcResult loggedIn = loginWithNewAccount(output);
        Cookie refreshCookie = refreshCookieOf(loggedIn);

        mockMvc.perform(
                        post(LOGOUT_PATH)
                                .header("Authorization", bearerOf(loggedIn))
                                .cookie(refreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(COOKIE_NAME, ""))
                .andExpect(cookie().maxAge(COOKIE_NAME, 0))
                .andExpect(cookie().path(COOKIE_NAME, "/api/v1/auth"));

        mockMvc.perform(post(REFRESH_PATH).cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Access Token이 없으면 401 UNAUTHORIZED다")
    void logoutWithoutAccessTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post(LOGOUT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Refresh 쿠키가 없어도 204로 응답하고 쿠키 삭제 헤더를 내려준다")
    void logoutWithoutRefreshCookieReturnsNoContent(CapturedOutput output) throws Exception {
        MvcResult loggedIn = loginWithNewAccount(output);

        mockMvc.perform(post(LOGOUT_PATH).header("Authorization", bearerOf(loggedIn)))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(COOKIE_NAME, ""))
                .andExpect(cookie().maxAge(COOKIE_NAME, 0));
    }

    @Test
    @DisplayName("이미 폐기된 Refresh Token으로 다시 로그아웃해도 204다")
    void logoutIsIdempotent(CapturedOutput output) throws Exception {
        MvcResult loggedIn = loginWithNewAccount(output);
        Cookie refreshCookie = refreshCookieOf(loggedIn);
        mockMvc.perform(
                        post(LOGOUT_PATH)
                                .header("Authorization", bearerOf(loggedIn))
                                .cookie(refreshCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        post(LOGOUT_PATH)
                                .header("Authorization", bearerOf(loggedIn))
                                .cookie(refreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(COOKIE_NAME, 0));
    }

    /** 가입·인증·로그인까지 API로 마친 세션을 만든다. */
    private MvcResult loginWithNewAccount(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        return login(account.email(), account.password()).andExpect(status().isOk()).andReturn();
    }

    private String bearerOf(MvcResult loggedIn) throws Exception {
        return "Bearer " + stringField(loggedIn, "accessToken");
    }
}
