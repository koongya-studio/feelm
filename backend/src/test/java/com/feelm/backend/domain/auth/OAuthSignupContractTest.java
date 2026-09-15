package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.global.security.JjwtTokenProvider;
import com.feelm.backend.global.security.JwtProperties;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired private JwtProperties jwtProperties;

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

    /**
     * 만료 처리는 {@code parseSignupToken}이 {@code ExpiredJwtException}을 {@code SIGNUP_TOKEN_INVALID}로
     * 옮기며 이뤄진다. 그 매핑이 사라지거나 다른 코드로 갈리면 명세(만료와 위조를 같은 코드로 응답)가 깨지는데, 위조 케이스만으로는 드러나지 않는다.
     *
     * <p>토큰은 같은 Secret과 <b>음수 TTL</b>로 발급기를 따로 만들어 얻는다. 프로퍼티를 덮어쓴 컨텍스트를 하나 더 띄우거나 실제 유효기간(10분)을
     * 기다리지 않기 위해서다. 정상 흐름(API-007 → API-008)은 위의 가입 테스트가 검증한다.
     */
    @Test
    @DisplayName("만료된 signupToken은 401 SIGNUP_TOKEN_INVALID다")
    void expiredSignupTokenIsRejected() throws Exception {
        OAuthUserProfile profile = newProfile();
        TokenProvider expiredTokenProvider =
                new JjwtTokenProvider(
                        new JwtProperties(
                                jwtProperties.secret(),
                                jwtProperties.accessTokenTtl(),
                                jwtProperties.refreshTokenTtl(),
                                Duration.ofMinutes(-1),
                                jwtProperties.refreshCookie()));
        String expired =
                expiredTokenProvider.createSignupToken(
                        new TokenProvider.SignupTokenClaims(
                                OAuthProvider.GOOGLE.name(),
                                profile.providerUserId(),
                                profile.email(),
                                profile.nickname()));

        oauthSignup(expired, "소셜가입")
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
