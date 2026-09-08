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

    /**
     * 소셜 가입 토큰을 발급한다(API-007). 아직 계정이 없는 상태라 사용자 식별자 대신 제공자 정보를 담는다.
     *
     * <p>Access·Refresh Token과 종류를 구분해 서로의 자리에 쓸 수 없게 한다. 셋 다 같은 키로 서명하므로 서명만으로는 구분되지 않는다.
     */
    String createSignupToken(SignupTokenClaims claims);

    /**
     * 소셜 가입 토큰을 검증하고 담긴 제공자 정보를 반환한다.
     *
     * @throws BusinessException 서명 불일치·형식 오류·만료 모두 {@code SIGNUP_TOKEN_INVALID}. 명세(API-008)가 두 경우를
     *     같은 코드로 응답한다.
     */
    SignupTokenClaims parseSignupToken(String token);

    /** Refresh Token에서 폐기 판단에 필요한 값만 추린 결과다. */
    record RefreshTokenClaims(long userId, String tokenId) {}

    /**
     * 소셜 가입을 마치는 데 필요한 값이다.
     *
     * <p>{@code provider}는 {@code OAuthProvider} 이름이지만 문자열로 둔다. {@code global}은 특정 도메인 타입에 기대지 않는다는
     * 컨벤션 3장의 경계를 지키기 위해서다.
     *
     * <p>{@code email}과 {@code nickname}은 제공자가 주지 않으면 {@code null}이다.
     */
    record SignupTokenClaims(
            String provider, String providerUserId, String email, String nickname) {}
}
