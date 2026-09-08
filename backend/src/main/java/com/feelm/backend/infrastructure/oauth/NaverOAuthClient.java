package com.feelm.backend.infrastructure.oauth;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Naver 계정 연동. 사용자 정보는 {@code response} 객체 아래에 담겨 온다. */
class NaverOAuthClient extends HttpOAuthClient {

    private static final String TOKEN_URI = "https://nid.naver.com/oauth2.0/token";
    private static final String USER_INFO_URI = "https://openapi.naver.com/v1/nid/me";

    NaverOAuthClient(RestClient restClient, OAuthProperties.Registration registration) {
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
        return new OAuthUserProfile(
                textOf(userInfo, "response", "id"),
                textOf(userInfo, "response", "email"),
                textOf(userInfo, "response", "nickname"));
    }
}
