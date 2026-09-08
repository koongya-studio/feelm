package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-007 소셜 로그인의 계약을 검증한다.
 *
 * <p>연결된 소셜 계정은 이메일 로그인(API-004)과 같은 본문·쿠키로 로그인되고, 연결된 계정이 없으면 가입을 이어갈 {@code signupToken}을 받는다. 실패
 * 경로는 provider 응답 오류(교환 실패·제공자 오류)와 요청 검증(미지원 provider·허용되지 않은 redirectUri)을 구분한다.
 */
@DisplayName("소셜 로그인 — API-007")
class OAuthLoginContractTest extends OAuthApiTestSupport {

    @Test
    @DisplayName("연결된 사용자가 없으면 200과 signupToken, 추천 닉네임을 받는다")
    void unlinkedProviderAccountReturnsSignupToken() throws Exception {
        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.GOOGLE).willReturn(profile);

        oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.suggestedNickname").value(profile.nickname()))
                .andExpect(cookie().doesNotExist(COOKIE_NAME));
    }

    @Test
    @DisplayName("이미 연결된 소셜 계정은 로그인 본문과 Refresh 쿠키를 받는다")
    void linkedProviderAccountLogsIn() throws Exception {
        OAuthUserProfile profile = newProfile();
        long userId = userIdOf(socialSignup(OAuthProvider.GOOGLE, profile));

        fake(OAuthProvider.GOOGLE).willReturn(profile);
        MvcResult result =
                oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.signupRequired").doesNotExist())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.expiresIn").value(1800))
                        .andExpect(jsonPath("$.user.userId").value(userId))
                        .andExpect(jsonPath("$.user.onboardingCompleted").value(false))
                        .andExpect(cookie().exists(COOKIE_NAME))
                        .andExpect(cookie().httpOnly(COOKIE_NAME, true))
                        .andReturn();

        mockMvc.perform(
                        get(MEMBER_PATH)
                                .header(
                                        "Authorization",
                                        "Bearer " + stringField(result, "accessToken")))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(userId)));
    }

    @Test
    @DisplayName("탈퇴 계정에 연결된 소셜 계정은 401 INVALID_CREDENTIALS다")
    void withdrawnAccountCannotLogIn() throws Exception {
        OAuthUserProfile profile = newProfile();
        long userId = userIdOf(socialSignup(OAuthProvider.GOOGLE, profile));
        withdrawUser(userId);

        fake(OAuthProvider.GOOGLE).willReturn(profile);
        oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(cookie().doesNotExist(COOKIE_NAME));
    }

    @Test
    @DisplayName("알 수 없는 provider는 400 UNSUPPORTED_PROVIDER다")
    void unknownProviderIsRejected() throws Exception {
        oauthLogin("facebook", VALID_CODE, ALLOWED_REDIRECT_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"));
    }

    @Test
    @DisplayName("자격 증명이 설정되지 않은 provider도 400 UNSUPPORTED_PROVIDER다 (D-5)")
    void unconfiguredProviderIsRejected() throws Exception {
        oauthLogin(OAuthProvider.NAVER, VALID_CODE, ALLOWED_REDIRECT_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PROVIDER"));
    }

    @Test
    @DisplayName("지원하지 않는 redirectUri와 미설정 provider가 겹치면 redirectUri 검증이 우선한다 (D-4)")
    void redirectUriValidationTakesPriorityOverUnsupportedProvider() throws Exception {
        oauthLogin(OAuthProvider.NAVER, VALID_CODE, "https://evil.example.com/callback")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("허용 목록에 없는 redirectUri는 provider를 호출하지 않고 400이다 (D-4)")
    void redirectUriOutsideAllowListIsRejectedBeforeProviderCall() throws Exception {
        fake(OAuthProvider.GOOGLE).willReturn(newProfile());

        oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, "https://evil.example.com/callback")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(fake(OAuthProvider.GOOGLE).calls())
                .as("허용되지 않은 redirectUri로 provider를 호출하면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("인가 코드 교환에 실패하면 400 OAUTH_CODE_INVALID다")
    void codeExchangeFailureReturnsCodeInvalid() throws Exception {
        fake(OAuthProvider.GOOGLE).willFail(new BusinessException(ErrorCode.OAUTH_CODE_INVALID));

        oauthLogin(OAuthProvider.GOOGLE, "expired-code", ALLOWED_REDIRECT_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_CODE_INVALID"));
    }

    @Test
    @DisplayName("provider 응답 오류는 502 OAUTH_PROVIDER_ERROR다")
    void providerFailureReturnsBadGateway() throws Exception {
        fake(OAuthProvider.KAKAO).willFail(new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR));

        oauthLogin(OAuthProvider.KAKAO, VALID_CODE, ALLOWED_REDIRECT_URI)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_ERROR"));
    }

    @Test
    @DisplayName("provider가 이메일을 주지 않으면 400 OAUTH_EMAIL_REQUIRED다 (D-2)")
    void missingProviderEmailIsRejected() throws Exception {
        fake(OAuthProvider.KAKAO)
                .willReturn(new OAuthUserProfile("provider-user-no-email", null, "무명"));

        oauthLogin(OAuthProvider.KAKAO, VALID_CODE, ALLOWED_REDIRECT_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_EMAIL_REQUIRED"));
    }

    @Test
    @DisplayName("code가 비어 있으면 400 VALIDATION_FAILED다")
    void blankCodeIsRejected() throws Exception {
        oauthLogin(OAuthProvider.GOOGLE, "", ALLOWED_REDIRECT_URI)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("추천 닉네임이 닉네임 규칙을 어기면 규칙에 맞는 값으로 대체된다")
    void suggestedNicknameFallsBackWhenProviderNicknameIsInvalid() throws Exception {
        fake(OAuthProvider.KAKAO)
                .willReturn(
                        new OAuthUserProfile(
                                "provider-user-long-nickname",
                                "long-nickname@example.com",
                                "이 닉네임은 규칙보다 훨씬 깁니다"));

        MvcResult result =
                oauthLogin(OAuthProvider.KAKAO, VALID_CODE, ALLOWED_REDIRECT_URI)
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(stringField(result, "suggestedNickname"))
                .as("추천 닉네임은 그대로 가입에 쓸 수 있어야 한다")
                .matches("^[가-힣a-zA-Z0-9_]{2,10}$");
    }
}
