package com.feelm.backend.infrastructure.oauth;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * OAuth 2.0 Authorization Code 흐름의 공통 절차다.
 *
 * <p>제공자마다 다른 것은 엔드포인트 주소와 사용자 정보 응답의 필드 이름뿐이라, 인가 코드 교환과 오류 변환은 여기에 두고 구현체는 두 가지만 채운다.
 *
 * <p>오류는 두 갈래로 나눈다. 토큰 교환의 4xx는 코드가 이미 쓰였거나 만료된 경우가 대부분이라 {@code OAUTH_CODE_INVALID}(400)로 사용자에게 다시
 * 시도할 여지를 알리고, 5xx·통신 실패·형식이 어긋난 응답은 우리 쪽에서 할 수 있는 일이 없으므로 {@code OAUTH_PROVIDER_ERROR}(502)로 올린다.
 * 사용자 정보 조회의 4xx는 방금 발급받은 토큰이 거부된 것이므로 제공자 오류로 본다.
 *
 * <p>인가 코드와 접근 토큰은 로그에 남기지 않는다(컨벤션 12장).
 */
abstract class HttpOAuthClient implements OAuthClient {

    private final RestClient restClient;
    private final OAuthProperties.Registration registration;

    protected HttpOAuthClient(RestClient restClient, OAuthProperties.Registration registration) {
        this.restClient = restClient;
        this.registration = registration;
    }

    /** 인가 코드를 접근 토큰으로 바꾸는 엔드포인트. */
    protected abstract String tokenUri();

    /** 접근 토큰으로 사용자 정보를 조회하는 엔드포인트. */
    protected abstract String userInfoUri();

    /** 제공자별 사용자 정보 응답을 공통 형식으로 옮긴다. */
    protected abstract OAuthUserProfile toProfile(JsonNode userInfo);

    @Override
    public OAuthUserProfile fetchProfile(String code, String redirectUri) {
        OAuthUserProfile profile = toProfile(requestUserInfo(exchangeCode(code, redirectUri)));
        if (profile.providerUserId() == null || profile.providerUserId().isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
        return profile;
    }

    private String exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", registration.clientId());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        if (registration.clientSecret() != null && !registration.clientSecret().isBlank()) {
            form.add("client_secret", registration.clientSecret());
        }

        JsonNode response;
        try {
            response =
                    restClient
                            .post()
                            .uri(tokenUri())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(form)
                            .retrieve()
                            .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw new BusinessException(
                    exception.getStatusCode().is4xxClientError()
                            ? ErrorCode.OAUTH_CODE_INVALID
                            : ErrorCode.OAUTH_PROVIDER_ERROR);
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }

        String accessToken = textOf(response, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
        return accessToken;
    }

    private JsonNode requestUserInfo(String accessToken) {
        try {
            JsonNode response =
                    restClient
                            .get()
                            .uri(userInfoUri())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) {
                throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
            }
            return response;
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
    }

    /** 중첩 경로를 따라 문자열 값을 읽는다. 없거나 null이면 {@code null}을 돌려준다. */
    static String textOf(JsonNode node, String... path) {
        JsonNode current = node;
        if (current == null) {
            return null;
        }
        for (String key : path) {
            current = current.path(key);
        }
        if (current.isMissingNode() || current.isNull()) {
            return null;
        }
        String value = current.asText();
        return value.isBlank() ? null : value;
    }

    /** 중첩 경로를 따라 불리언 값을 읽는다. 없거나 null이면 {@code false}로 본다. */
    static boolean boolOf(JsonNode node, String... path) {
        JsonNode current = node;
        if (current == null) {
            return false;
        }
        for (String key : path) {
            current = current.path(key);
        }
        return current.asBoolean(false);
    }
}
