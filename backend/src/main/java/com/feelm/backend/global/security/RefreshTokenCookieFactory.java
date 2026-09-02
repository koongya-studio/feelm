package com.feelm.backend.global.security;

import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 쿠키를 만드는 단일 지점이다.
 *
 * <p>{@code HttpOnly}는 JavaScript의 접근을 막아 XSS로 토큰이 유출되는 경로를 끊고, {@code SameSite=Lax}는 다른 사이트에서 시작된
 * 요청에 쿠키가 실려 나가는 CSRF를 막는다(ADR-0009). {@code Path}를 인증 경로로 좁혀 두어 나머지 API 요청에는 아예 실리지 않는다.
 */
@Component
public class RefreshTokenCookieFactory {

    private final JwtProperties.RefreshCookie policy;
    private final Duration ttl;

    public RefreshTokenCookieFactory(JwtProperties properties) {
        this.policy = properties.refreshCookie();
        this.ttl = properties.refreshTokenTtl();
    }

    public String cookieName() {
        return policy.name();
    }

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken).maxAge(ttl).build();
    }

    /** 로그아웃처럼 쿠키를 지워야 할 때 쓴다. */
    public ResponseCookie expire() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(policy.name(), value)
                .httpOnly(true)
                .secure(policy.secure())
                .sameSite(policy.sameSite())
                .path(policy.path());
    }
}
