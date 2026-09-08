package com.feelm.backend.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 제공자별 인가 코드 교환과 응답 해석을 검증한다.
 *
 * <p>계약 테스트는 이 지점을 대역으로 바꾸므로 제공자마다 다른 응답 형식은 여기서만 확인할 수 있다. 필드 이름을 잘못 읽으면 실제 로그인에서만 드러나기 때문에 각 제공자의
 * 실제 응답 모양을 그대로 흉내 낸다.
 */
@DisplayName("제공자 연동 — 인가 코드 교환과 사용자 정보 해석")
class HttpOAuthClientTest {

    private static final String CODE = "authorization-code";
    private static final String REDIRECT_URI = "https://feelm.app/oauth/callback";

    private static final OAuthProperties.Registration REGISTRATION =
            new OAuthProperties.Registration("client-id", "client-secret");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient restClient = builder.build();

    @Test
    @DisplayName("Google: sub·email·name을 읽고 교환 요청에 인가 코드와 redirectUri를 담는다")
    void readsGoogleProfile() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedTokenForm()))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\"provider-token\"}",
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer provider-token"))
                .andRespond(
                        withSuccess(
                                """
                                {"sub":"114","email":"user@gmail.com","email_verified":true,
                                 "name":"필름"}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(new GoogleOAuthClient(restClient, REGISTRATION).fetchProfile(CODE, REDIRECT_URI))
                .isEqualTo(new OAuthUserProfile("114", "user@gmail.com", "필름"));
        server.verify();
    }

    @Test
    @DisplayName("Google: email_verified가 아니면 확인되지 않은 이메일이므로 없는 값으로 돌려준다")
    void readsGoogleProfileWithUnverifiedEmail() {
        givenToken("https://oauth2.googleapis.com/token");
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
                .andRespond(
                        withSuccess(
                                """
                                {"sub":"114","email":"user@gmail.com","email_verified":false,
                                 "name":"필름"}
                                """,
                                MediaType.APPLICATION_JSON));

        OAuthUserProfile profile =
                new GoogleOAuthClient(restClient, REGISTRATION).fetchProfile(CODE, REDIRECT_URI);

        assertThat(profile.hasEmail()).isFalse();
        assertThat(profile.providerUserId()).isEqualTo("114");
    }

    @Test
    @DisplayName("Kakao: id와 kakao_account 아래의 이메일·닉네임을 읽는다")
    void readsKakaoProfile() {
        givenToken("https://kauth.kakao.com/oauth/token");
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(
                        withSuccess(
                                """
                                {"id":1234567890,
                                 "kakao_account":{"email":"user@kakao.com","is_email_verified":true,
                                                  "profile":{"nickname":"팝콘러버"}}}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(new KakaoOAuthClient(restClient, REGISTRATION).fetchProfile(CODE, REDIRECT_URI))
                .isEqualTo(new OAuthUserProfile("1234567890", "user@kakao.com", "팝콘러버"));
        server.verify();
    }

    @Test
    @DisplayName("Kakao: 이메일 동의를 받지 못하면 이메일 없이 돌려준다 (D-2)")
    void readsKakaoProfileWithoutEmail() {
        givenToken("https://kauth.kakao.com/oauth/token");
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(
                        withSuccess(
                                "{\"id\":1234567890,\"kakao_account\":{\"profile\":{\"nickname\":\"팝콘러버\"}}}",
                                MediaType.APPLICATION_JSON));

        OAuthUserProfile profile =
                new KakaoOAuthClient(restClient, REGISTRATION).fetchProfile(CODE, REDIRECT_URI);

        assertThat(profile.hasEmail()).isFalse();
        assertThat(profile.providerUserId()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("Kakao: is_email_verified가 아니면 확인되지 않은 이메일이므로 없는 값으로 돌려준다")
    void readsKakaoProfileWithUnverifiedEmail() {
        givenToken("https://kauth.kakao.com/oauth/token");
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(
                        withSuccess(
                                """
                                {"id":1234567890,
                                 "kakao_account":{"email":"user@kakao.com","is_email_verified":false,
                                                  "profile":{"nickname":"팝콘러버"}}}
                                """,
                                MediaType.APPLICATION_JSON));

        OAuthUserProfile profile =
                new KakaoOAuthClient(restClient, REGISTRATION).fetchProfile(CODE, REDIRECT_URI);

        assertThat(profile.hasEmail()).isFalse();
        assertThat(profile.providerUserId()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("Naver: response 아래의 id·email·nickname을 읽는다")
    void readsNaverProfile() {
        givenToken("https://nid.naver.com/oauth2.0/token");
        server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
                .andRespond(
                        withSuccess(
                                """
                                {"resultcode":"00","message":"success",
                                 "response":{"id":"abc-123","email":"user@naver.com","nickname":"필름"}}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(new NaverOAuthClient(restClient, REGISTRATION).fetchProfile(CODE, REDIRECT_URI))
                .isEqualTo(new OAuthUserProfile("abc-123", "user@naver.com", "필름"));
        server.verify();
    }

    @Test
    @DisplayName("코드 교환이 4xx면 OAUTH_CODE_INVALID다")
    void rejectedCodeIsCodeInvalid() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(
                        () ->
                                new GoogleOAuthClient(restClient, REGISTRATION)
                                        .fetchProfile(CODE, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_CODE_INVALID);
    }

    @Test
    @DisplayName("코드 교환이 5xx면 OAUTH_PROVIDER_ERROR다")
    void providerServerErrorIsProviderError() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withServerError());

        assertThatThrownBy(
                        () ->
                                new GoogleOAuthClient(restClient, REGISTRATION)
                                        .fetchProfile(CODE, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("사용자 정보 조회가 실패하면 코드 문제가 아니므로 OAUTH_PROVIDER_ERROR다")
    void userInfoFailureIsProviderError() {
        givenToken("https://oauth2.googleapis.com/token");
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(
                        () ->
                                new GoogleOAuthClient(restClient, REGISTRATION)
                                        .fetchProfile(CODE, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("접근 토큰이 없는 응답은 OAUTH_PROVIDER_ERROR다")
    void tokenResponseWithoutAccessTokenIsProviderError() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new GoogleOAuthClient(restClient, REGISTRATION)
                                        .fetchProfile(CODE, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("사용자 식별자가 없는 응답은 OAUTH_PROVIDER_ERROR다")
    void userInfoWithoutIdIsProviderError() {
        givenToken("https://oauth2.googleapis.com/token");
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
                .andRespond(
                        withSuccess("{\"email\":\"user@gmail.com\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new GoogleOAuthClient(restClient, REGISTRATION)
                                        .fetchProfile(CODE, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    private void givenToken(String tokenUri) {
        server.expect(requestTo(tokenUri))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\"provider-token\"}",
                                MediaType.APPLICATION_JSON));
    }

    private MultiValueMap<String, String> expectedTokenForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", "client-id");
        form.add("code", CODE);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("client_secret", "client-secret");
        return form;
    }
}
