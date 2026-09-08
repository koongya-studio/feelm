package com.feelm.backend.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 토큰과 Refresh 쿠키의 보안 설정값이다.
 *
 * <p>{@code signupTokenTtl}은 소셜 가입을 마칠 때까지만 유효한 토큰의 수명이다(API-007 처리 규칙의 10분).
 *
 * <p>{@code secret}은 기본값을 제공하지 않는다. 값이 없으면 기동 시점에 실패하며, 이는 {@code
 * docs/secret-environment-variables.md} 2장의 "애플리케이션은 Secret을 소스 코드의 기본값으로 제공하지 않는다" 규칙을 따른 것이다.
 */
@ConfigurationProperties(prefix = "feelm.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("30m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("10m") Duration signupTokenTtl,
        @DefaultValue RefreshCookie refreshCookie) {

    /** ADR-0009가 정한 Refresh Token 쿠키 속성이다. */
    public record RefreshCookie(
            @DefaultValue("refresh_token") String name,
            @DefaultValue("/api/v1/auth") String path,
            @DefaultValue("true") boolean secure,
            @DefaultValue("Lax") String sameSite) {}
}
