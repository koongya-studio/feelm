package com.feelm.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JjwtTokenProvider — 토큰 발급과 검증")
class JjwtTokenProviderTest {

    private static final String SECRET = "feelm-test-signing-key-for-hs256-at-least-32-bytes";
    private static final String OTHER_SECRET = "another-signing-key-for-hs256-at-least-32-bytes!!";

    /** 64바이트 Secret. 알고리즘을 고정하지 않으면 jjwt가 HS512를 고른다. */
    private static final String LONG_SECRET =
            "feelm-test-signing-key-that-is-exactly-sixty-four-bytes-long-123";

    private final JjwtTokenProvider provider = provider(SECRET, Duration.ofMinutes(30));

    private static JjwtTokenProvider provider(String secret, Duration accessTtl) {
        return new JjwtTokenProvider(
                new JwtProperties(
                        secret,
                        accessTtl,
                        Duration.ofDays(14),
                        new JwtProperties.RefreshCookie(
                                "refresh_token", "/api/v1/auth", true, "Lax")));
    }

    @Test
    @DisplayName("발급한 Access Token에서 사용자 식별자를 되읽는다")
    void parsesUserIdFromIssuedAccessToken() {
        String token = provider.createAccessToken(42L);

        assertThat(provider.parseAccessToken(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("만료된 Access Token은 TOKEN_EXPIRED로 거절한다")
    void expiredAccessTokenIsRejectedAsExpired() {
        JjwtTokenProvider expiring = provider(SECRET, Duration.ofSeconds(-1));
        String token = expiring.createAccessToken(42L);

        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("다른 키로 서명된 Access Token은 UNAUTHORIZED로 거절한다")
    void tamperedAccessTokenIsRejectedAsUnauthorized() {
        String forged = provider(OTHER_SECRET, Duration.ofMinutes(30)).createAccessToken(42L);

        assertThatThrownBy(() -> provider.parseAccessToken(forged))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("형식이 깨진 문자열은 UNAUTHORIZED로 거절한다")
    void malformedTokenIsRejectedAsUnauthorized() {
        assertThatThrownBy(() -> provider.parseAccessToken("not-a-jwt"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Refresh Token에서 사용자 식별자와 토큰 식별자를 되읽는다")
    void parsesClaimsFromIssuedRefreshToken() {
        String token = provider.createRefreshToken(42L, "token-id");

        TokenProvider.RefreshTokenClaims claims = provider.parseRefreshToken(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.tokenId()).isEqualTo("token-id");
    }

    @Test
    @DisplayName("서명이 유효해도 Access Token을 Refresh Token 자리에 쓸 수 없다")
    void accessTokenIsNotAcceptedAsRefreshToken() {
        String accessToken = provider.createAccessToken(42L);

        assertThatThrownBy(() -> provider.parseRefreshToken(accessToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    @DisplayName("Refresh Token을 Access Token 자리에 쓸 수 없다")
    void refreshTokenIsNotAcceptedAsAccessToken() {
        String refreshToken = provider.createRefreshToken(42L, "token-id");

        assertThatThrownBy(() -> provider.parseAccessToken(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Secret이 길어도 서명 알고리즘은 HS256으로 고정된다")
    void signsWithHs256RegardlessOfSecretLength() {
        String token = provider(LONG_SECRET, Duration.ofMinutes(30)).createAccessToken(42L);

        assertThat(headerOf(token)).contains("\"alg\":\"HS256\"");
    }

    @Test
    @DisplayName("HS256에 못 미치는 짧은 Secret은 기동 시점에 거절한다")
    void shortSecretIsRejectedOnStartup() {
        assertThatThrownBy(() -> provider("too-short", Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String headerOf(String token) {
        byte[] header = Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.')));
        return new String(header, StandardCharsets.UTF_8);
    }
}
