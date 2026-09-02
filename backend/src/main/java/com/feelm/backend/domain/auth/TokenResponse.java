package com.feelm.backend.domain.auth;

/** API-006 토큰 재발급 응답 본문. Refresh Token은 쿠키로만 전달하므로 본문에 넣지 않는다. */
public record TokenResponse(String accessToken, long expiresIn) {

    static TokenResponse from(RefreshTokenService.IssuedTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.expiresIn());
    }
}
