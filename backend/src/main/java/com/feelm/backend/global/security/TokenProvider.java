package com.feelm.backend.global.security;

import com.feelm.backend.global.error.BusinessException;

/**
 * 인증 토큰의 발급과 해석을 담당한다.
 *
 * <p>JWT 라이브러리(jjwt)에 대한 의존을 구현체 한 곳에 가두기 위한 경계다. 이 인터페이스는 {@code Claims}처럼 라이브러리에 종속된 타입을 노출하지
 * 않으므로, 이후 다른 구현으로 교체해도 호출 측은 영향을 받지 않는다.
 */
public interface TokenProvider {

    /** Access Token을 발급한다. */
    String createAccessToken(long userId);

    /** Refresh Token을 발급한다. {@code tokenId}는 폐기 판단에 쓰는 식별자(jti)다. */
    String createRefreshToken(long userId, String tokenId);

    /** Access Token의 남은 수명이 아니라 발급 시점 기준 총 수명을 초 단위로 반환한다. */
    long accessTokenTtlSeconds();

    /**
     * Access Token을 검증하고 사용자 식별자를 반환한다.
     *
     * @throws BusinessException 서명 불일치·형식 오류는 {@code UNAUTHORIZED}, 만료는 {@code TOKEN_EXPIRED}
     */
    long parseAccessToken(String token);

    /**
     * Refresh Token을 검증하고 사용자 식별자와 토큰 식별자를 반환한다.
     *
     * @throws BusinessException 서명 불일치·형식 오류는 {@code REFRESH_TOKEN_INVALID}, 만료는 {@code
     *     REFRESH_TOKEN_EXPIRED}
     */
    RefreshTokenClaims parseRefreshToken(String token);

    /** Refresh Token에서 폐기 판단에 필요한 값만 추린 결과다. */
    record RefreshTokenClaims(long userId, String tokenId) {}
}
