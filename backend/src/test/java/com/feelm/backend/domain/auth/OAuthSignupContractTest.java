package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API-008 소셜 회원가입 완료의 계약을 검증한다.
 *
 * <p>가입은 이메일 인증 없이 즉시 {@code ACTIVE}이고 비밀번호를 갖지 않는다. 이메일 계정과 겹치는 이메일은 통합하지 않고 거절한다(D-1). 발급 토큰은 종류가
 * 달라 Access Token으로 대체할 수 없어야 한다.
 */
@DisplayName("소셜 회원가입 완료 — API-008")
class OAuthSignupContractTest extends OAuthApiTestSupport {

    @Test
    @DisplayName("정상 가입은 201과 로그인 본문·쿠키를 주고 활성 계정과 연결 정보를 만든다")
    void signupCreatesActiveSocialAccount() throws Exception {
        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.GOOGLE).willReturn(profile);
        MvcResult login =
                oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI)
                        .andExpect(status().isOk())
                        .andReturn();

        MvcResult result =
                oauthSignup(stringField(login, "signupToken"), profile.nickname())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.expiresIn").value(1800))
                        .andExpect(jsonPath("$.user.nickname").value(profile.nickname()))
                        .andExpect(jsonPath("$.user.onboardingCompleted").value(false))
                        .andExpect(cookie().exists(COOKIE_NAME))
                        .andExpect(cookie().httpOnly(COOKIE_NAME, true))
                        .andReturn();

        long userId = userIdOf(result);
        assertThat(userStatus(userId)).as("소셜 가입은 이메일 인증 없이 즉시 활성이다").isEqualTo("ACTIVE");
        assertThat(passwordHash(userId)).as("소셜 전용 계정은 비밀번호를 갖지 않는다").isNull();
        assertThat(notificationSettings(userId)).as("알림 설정 기본행이 만들어진다").isNotEmpty();
        assertThat(linkedProviderUserId(userId, OAuthProvider.GOOGLE))
                .isEqualTo(profile.providerUserId());

        mockMvc.perform(
                        get(MEMBER_PATH)
                                .header(
                                        "Authorization",
                                        "Bearer " + stringField(result, "accessToken")))
                .andExpect(status().isOk())
                .andExpect(content().string(String.valueOf(userId)));
    }

    @Test
    @DisplayName("위조된 signupToken은 401 SIGNUP_TOKEN_INVALID다")
    void forgedSignupTokenIsRejected() throws Exception {
        oauthSignup("eyJhbGciOiJIUzI1NiJ9.forged.signature", "소셜가입")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNUP_TOKEN_INVALID"))
                .andExpect(cookie().doesNotExist(COOKIE_NAME));
    }

    @Test
    @DisplayName("Access Token은 signupToken 자리에 쓸 수 없다")
    void accessTokenIsNotAcceptedAsSignupToken(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        MvcResult login = login(account.email(), account.password()).andReturn();

        oauthSignup(stringField(login, "accessToken"), "소셜가입")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNUP_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("닉네임 규칙을 어기면 400 VALIDATION_FAILED다")
    void invalidNicknameIsRejected() throws Exception {
        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.GOOGLE).willReturn(profile);
        MvcResult login =
                oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI).andReturn();

        oauthSignup(stringField(login, "signupToken"), "a")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("이미 쓰는 닉네임이면 대소문자가 달라도 409 NICKNAME_DUPLICATED다")
    void duplicatedNicknameIsRejected(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);

        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.KAKAO).willReturn(profile);
        MvcResult login =
                oauthLogin(OAuthProvider.KAKAO, VALID_CODE, ALLOWED_REDIRECT_URI).andReturn();

        oauthSignup(stringField(login, "signupToken"), account.nickname().toUpperCase(Locale.ROOT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    @Test
    @DisplayName("이메일이 기존 계정과 같으면 통합하지 않고 409 EMAIL_DUPLICATED다 (D-1)")
    void duplicatedEmailIsRejectedInsteadOfMerging(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        long emailUserId = signupActive(account, output);

        fake(OAuthProvider.GOOGLE)
                .willReturn(
                        new OAuthUserProfile("provider-user-same-email", account.email(), "소셜"));
        MvcResult login =
                oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI).andReturn();

        oauthSignup(stringField(login, "signupToken"), "소셜중복")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));

        assertThat(linkedProviderCount(emailUserId)).as("거절된 가입이 기존 계정에 소셜 수단을 연결하면 안 된다").isZero();
    }

    @Test
    @DisplayName("이메일 계정이 미인증 상태로 24시간 지났으면 정리하고 소셜 가입을 허용한다 (API-001과 동일 규칙)")
    void staleUnverifiedEmailIsReapedAndSignupSucceeds() throws Exception {
        TestAccount account = newAccount();
        ageUser(signupPending(account), Duration.ofHours(25));

        fake(OAuthProvider.GOOGLE)
                .willReturn(
                        new OAuthUserProfile("provider-user-stale-email", account.email(), "소셜"));
        MvcResult login =
                oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI).andReturn();

        oauthSignup(stringField(login, "signupToken"), "소셜정리")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.nickname").value("소셜정리"));
    }

    @Test
    @DisplayName("같은 signupToken으로 두 번 가입하면 409 OAUTH_ACCOUNT_ALREADY_LINKED다")
    void reusedSignupTokenIsRejectedAfterLinking() throws Exception {
        OAuthUserProfile profile = newProfile();
        fake(OAuthProvider.GOOGLE).willReturn(profile);
        MvcResult login =
                oauthLogin(OAuthProvider.GOOGLE, VALID_CODE, ALLOWED_REDIRECT_URI).andReturn();
        String signupToken = stringField(login, "signupToken");

        oauthSignup(signupToken, profile.nickname()).andExpect(status().isCreated());

        oauthSignup(signupToken, "소셜재시도")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_ACCOUNT_ALREADY_LINKED"));
    }
}
