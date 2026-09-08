package com.feelm.backend.infrastructure.oauth;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Google 계정 연동. 사용자 식별자는 OpenID Connect의 {@code sub}다.
 *
 * <p>{@code email_verified}가 아닌 이메일은 본인 소유가 확인되지 않았으므로 비운 값으로 취급한다. 검증되지 않은 이메일을 그대로 신뢰하면 계정 충돌·중복
 * 판정에 남의 이메일이 쓰일 수 있다.
 */
class GoogleOAuthClient extends HttpOAuthClient {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USER_INFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";

    GoogleOAuthClient(RestClient restClient, OAuthProperties.Registration registration) {
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
        String email = boolOf(userInfo, "email_verified") ? textOf(userInfo, "email") : null;
        return new OAuthUserProfile(textOf(userInfo, "sub"), email, textOf(userInfo, "name"));
    }
}
