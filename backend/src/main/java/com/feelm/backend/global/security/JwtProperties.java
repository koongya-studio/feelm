package com.feelm.backend.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 토큰과 Refresh 쿠키의 보안 설정값이다.
 *
 * <p>{@code signupTokenTtl}은 소셜 가입을 마칠 때까지만 유효한 토큰의 수명이다(API-007 처리 규칙의 10분).
 *
 * <p>{@code emailVerificationTokenTtl}은 이메일 인증번호 확인(API-079)이 발급하고 이메일 회원가입(API-001)이 쓰는 토큰의
 * 수명이다(API-079 처리 규칙의 10분).
 *
 * <p>{@code refreshRotationGrace}는 회전 직후 같은 Refresh Token이 한 번 더 도착해도 탈취로 보지 않는 시간이다(API-006 처리
 * 규칙). 응답을 받지 못한 채 끊긴 재발급이 다시 오는 정상 경우까지 전량 폐기로 처리하지 않기 위한 값이며, 길수록 탈취된 토큰이 통하는 창도 넓어진다.
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
        @DefaultValue("10m") Duration emailVerificationTokenTtl,
        @DefaultValue("10s") Duration refreshRotationGrace,
        @DefaultValue RefreshCookie refreshCookie) {

    /** ADR-0009가 정한 Refresh Token 쿠키 속성이다. */
    public record RefreshCookie(
            @DefaultValue("refresh_token") String name,
            @DefaultValue("/api/v1/auth") String path,
            @DefaultValue("true") boolean secure,
            @DefaultValue("Lax") String sameSite) {}
}
