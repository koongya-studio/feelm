package com.feelm.backend.infrastructure.oauth;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Kakao 계정 연동.
 *
 * <p>이메일과 닉네임은 사용자가 동의한 경우에만 {@code kakao_account} 아래에 담겨 온다. 없을 수 있는 값이므로 그대로 비워 전달하고, 가입에 필요한
 * 이메일이 없을 때의 처리는 도메인이 결정한다(D-2).
 *
 * <p>{@code is_email_verified}가 아닌 이메일은 본인 소유가 확인되지 않았으므로 없는 값과 같게 취급한다.
 */
class KakaoOAuthClient extends HttpOAuthClient {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    KakaoOAuthClient(RestClient restClient, OAuthProperties.Registration registration) {
        super(restClient, registration);
    }

    @Override
    protected String tokenUri() {
        return TOKEN_URI;
    }

    @Override
    protected String userInfoUri() {
        return USER_INFO_URI;
    }

    @Override
    protected OAuthUserProfile toProfile(JsonNode userInfo) {
        String email =
                boolOf(userInfo, "kakao_account", "is_email_verified")
                        ? textOf(userInfo, "kakao_account", "email")
                        : null;
        return new OAuthUserProfile(
                textOf(userInfo, "id"),
                email,
                textOf(userInfo, "kakao_account", "profile", "nickname"));
    }
}
