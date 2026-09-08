package com.feelm.backend.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.infrastructure.oauth.FakeOAuthClient;
import com.feelm.backend.infrastructure.oauth.FakeOAuthClients;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * 소셜 계정 API(API-007~009) 계약 테스트의 공통 골격이다.
 *
 * <p>이메일 계정 API의 골격({@link EmailAuthApiTestSupport})을 그대로 쓴다. 소셜 로그인 결과가 이메일 로그인과 같은 응답·쿠키 계약을 따르고,
 * 계정 연결·이메일 중복 검증에 이메일 계정이 필요하기 때문이다.
 *
 * <p>provider 호출만 {@link FakeOAuthClients}로 대체한다. 인가 코드 교환은 외부 시스템 호출이라 테스트가 재현할 수 없고, 이 업무의 계약은 교환
 * 결과를 어떻게 처리하는지에 있다. 실제 provider 연동은 자격 증명 발급 후 수동으로 확인한다.
 */
@Import(FakeOAuthClients.Registrar.class)
abstract class OAuthApiTestSupport extends EmailAuthApiTestSupport {

    static final String OAUTH_LOGIN_PATH = "/api/v1/auth/oauth/";
    static final String OAUTH_SIGNUP_PATH = "/api/v1/auth/oauth/signup";
    static final String OAUTH_LINK_PATH = "/api/v1/users/me/oauth/";

    /** 테스트 설정(`application.yml`)의 허용 목록에 있는 값(D-4). */
    static final String ALLOWED_REDIRECT_URI = "https://feelm.app/oauth/callback";

    static final String ALLOWED_LINK_REDIRECT_URI = "https://feelm.app/oauth/link-callback";

    static final String VALID_CODE = "4/0AX4XfWh-test-code";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired protected FakeOAuthClients oauthClients;

    @BeforeEach
    void resetOAuthClients() {
        oauthClients.reset();
    }

    FakeOAuthClient fake(OAuthProvider provider) {
        return oauthClients.of(provider);
    }

    /**
     * 매번 새로운 provider 사용자.
     *
     * <p>닉네임은 그대로 가입에 쓸 수 있도록 닉네임 규칙(2~10자)을 만족하는 {@code soc} + 일련번호로 만든다.
     */
    static OAuthUserProfile newProfile() {
        String suffix = String.format("%06d", SEQUENCE.incrementAndGet());
        return new OAuthUserProfile(
                "provider-user-" + suffix, "social" + suffix + "@example.com", "soc" + suffix);
    }

    ResultActions oauthLogin(OAuthProvider provider, String code, String redirectUri)
            throws Exception {
        return oauthLogin(provider.name().toLowerCase(Locale.ROOT), code, redirectUri);
    }

    ResultActions oauthLogin(String provider, String code, String redirectUri) throws Exception {
        return postJson(OAUTH_LOGIN_PATH + provider, oauthCodeBody(code, redirectUri));
    }

    ResultActions oauthSignup(String signupToken, String nickname) throws Exception {
        return postJson(OAUTH_SIGNUP_PATH, oauthSignupBody(signupToken, nickname));
    }

    ResultActions oauthLink(
            OAuthProvider provider, String accessToken, String code, String redirectUri)
            throws Exception {
        return mockMvc.perform(
                post(OAUTH_LINK_PATH + provider.name().toLowerCase(Locale.ROOT))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oauthCodeBody(code, redirectUri)));
    }

    static String oauthCodeBody(String code, String redirectUri) {
        return """
                {"code":"%s","redirectUri":"%s"}
                """
                .formatted(code, redirectUri);
    }

    static String oauthSignupBody(String signupToken, String nickname) {
        return """
                {"signupToken":"%s","nickname":"%s","birthDate":"%s","gender":"%s"}
                """
                .formatted(signupToken, nickname, VALID_BIRTH_DATE, VALID_GENDER);
    }

    /**
     * provider 사용자로 가입까지 마치고 응답을 돌려준다.
     *
     * <p>사전 상태도 되도록 API 호출로 만든다는 골격의 방침을 따라 API-007 → API-008을 그대로 거친다.
     */
    MvcResult socialSignup(OAuthProvider provider, OAuthUserProfile profile) throws Exception {
        return socialSignup(provider, profile, profile.nickname());
    }

    MvcResult socialSignup(OAuthProvider provider, OAuthUserProfile profile, String nickname)
            throws Exception {
        fake(provider).willReturn(profile);
        MvcResult login = oauthLogin(provider, VALID_CODE, ALLOWED_REDIRECT_URI).andReturn();
        return oauthSignup(stringField(login, "signupToken"), nickname).andReturn();
    }

    /** 로그인·가입 응답의 사용자 식별자. 본문은 API-004와 같은 구조라 {@code user} 아래에 있다. */
    long userIdOf(MvcResult result) throws Exception {
        return json(result).get("user").get("userId").asLong();
    }

    long linkedProviderCount(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from user_auth_providers where user_id = ?", Long.class, userId);
    }

    String linkedProviderUserId(long userId, OAuthProvider provider) {
        return jdbcTemplate.queryForObject(
                "select provider_user_id from user_auth_providers where user_id = ? and provider = ?",
                String.class,
                userId,
                provider.name());
    }

    String passwordHash(long userId) {
        return jdbcTemplate.queryForObject(
                "select password_hash from users where id = ?", String.class, userId);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
