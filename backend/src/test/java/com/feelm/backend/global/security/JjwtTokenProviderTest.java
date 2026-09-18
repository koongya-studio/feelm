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
        return provider(secret, accessTtl, Duration.ofMinutes(10));
    }

    private static JjwtTokenProvider provider(
            String secret, Duration accessTtl, Duration signupTtl) {
        return provider(secret, accessTtl, signupTtl, Duration.ofMinutes(10));
    }

    private static JjwtTokenProvider provider(
            String secret, Duration accessTtl, Duration signupTtl, Duration emailVerificationTtl) {
        return new JjwtTokenProvider(
                new JwtProperties(
                        secret,
                        accessTtl,
                        Duration.ofDays(14),
                        signupTtl,
                        emailVerificationTtl,
                        Duration.ofSeconds(10),
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

    @Test
    @DisplayName("발급한 소셜 가입 토큰에서 provider 정보를 되읽는다 (API-007·008)")
    void parsesClaimsFromIssuedSignupToken() {
        TokenProvider.SignupTokenClaims claims =
                new TokenProvider.SignupTokenClaims(
                        "GOOGLE", "provider-user-1", "social@example.com", "소셜사용자");

        assertThat(provider.parseSignupToken(provider.createSignupToken(claims))).isEqualTo(claims);
    }

    @Test
    @DisplayName("이메일이 없는 소셜 가입 토큰도 그대로 되읽는다")
    void parsesSignupTokenWithoutEmail() {
        TokenProvider.SignupTokenClaims claims =
                new TokenProvider.SignupTokenClaims("KAKAO", "provider-user-2", null, null);

        assertThat(provider.parseSignupToken(provider.createSignupToken(claims))).isEqualTo(claims);
    }

    @Test
    @DisplayName("만료된 소셜 가입 토큰은 SIGNUP_TOKEN_INVALID로 거절한다")
    void expiredSignupTokenIsRejected() {
        JjwtTokenProvider expiring =
                provider(SECRET, Duration.ofMinutes(30), Duration.ofSeconds(-1));
        String token =
                expiring.createSignupToken(
                        new TokenProvider.SignupTokenClaims(
                                "GOOGLE", "provider-user-3", "social@example.com", "소셜사용자"));

        assertThatThrownBy(() -> provider.parseSignupToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SIGNUP_TOKEN_INVALID);
    }

    @Test
    @DisplayName("Access Token을 소셜 가입 토큰 자리에 쓸 수 없다")
    void accessTokenIsNotAcceptedAsSignupToken() {
        String accessToken = provider.createAccessToken(42L);

        assertThatThrownBy(() -> provider.parseSignupToken(accessToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SIGNUP_TOKEN_INVALID);
    }

    @Test
    @DisplayName("소셜 가입 토큰을 Access Token 자리에 쓸 수 없다")
    void signupTokenIsNotAcceptedAsAccessToken() {
        String signupToken =
                provider.createSignupToken(
                        new TokenProvider.SignupTokenClaims(
                                "GOOGLE", "provider-user-4", "social@example.com", "소셜사용자"));

        assertThatThrownBy(() -> provider.parseAccessToken(signupToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("발급한 이메일 인증 토큰에서 이메일과 식별자를 되읽는다")
    void parsesEmailVerificationToken() {
        String token =
                provider.createEmailVerificationToken(
                        new TokenProvider.EmailVerificationTokenClaims(
                                "signup@example.com", "token-id-1"));

        TokenProvider.EmailVerificationTokenClaims claims =
                provider.parseEmailVerificationToken(token);

        assertThat(claims.email()).isEqualTo("signup@example.com");
        assertThat(claims.tokenId()).isEqualTo("token-id-1");
    }

    @Test
    @DisplayName("만료된 이메일 인증 토큰은 EMAIL_VERIFICATION_TOKEN_INVALID로 거절한다")
    void expiredEmailVerificationTokenIsRejected() {
        JjwtTokenProvider expiring =
                provider(
                        SECRET,
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(-1));
        String token =
                expiring.createEmailVerificationToken(
                        new TokenProvider.EmailVerificationTokenClaims(
                                "signup@example.com", "token-id-2"));

        assertThatThrownBy(() -> provider.parseEmailVerificationToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    /** 한 종류가 다른 자리에서 통하면 소셜 가입 토큰으로 이메일 계정을 만들거나 그 반대가 가능해진다(API-001 처리 규칙). */
    @Test
    @DisplayName("소셜 가입 토큰을 이메일 인증 토큰 자리에 쓸 수 없다")
    void signupTokenIsNotAcceptedAsEmailVerificationToken() {
        String signupToken =
                provider.createSignupToken(
                        new TokenProvider.SignupTokenClaims(
                                "GOOGLE", "provider-user-5", "social@example.com", "소셜사용자"));

        assertThatThrownBy(() -> provider.parseEmailVerificationToken(signupToken))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    @Test
    @DisplayName("이메일 인증 토큰을 소셜 가입 토큰 자리에 쓸 수 없다")
    void emailVerificationTokenIsNotAcceptedAsSignupToken() {
        String token =
                provider.createEmailVerificationToken(
                        new TokenProvider.EmailVerificationTokenClaims(
                                "signup@example.com", "token-id-3"));

        assertThatThrownBy(() -> provider.parseSignupToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SIGNUP_TOKEN_INVALID);
    }

    private static String headerOf(String token) {
        byte[] header = Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.')));
        return new String(header, StandardCharsets.UTF_8);
    }
}
