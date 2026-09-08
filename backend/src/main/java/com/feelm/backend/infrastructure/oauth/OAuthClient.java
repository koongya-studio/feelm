package com.feelm.backend.infrastructure.oauth;

import com.feelm.backend.global.error.BusinessException;

/**
 * 제공자별 인가 코드 교환 경계다.
 *
 * <p>도메인은 이 인터페이스만 알고 제공자마다 다른 엔드포인트·응답 형식은 구현이 감춘다. 테스트는 이 지점을 대역으로 바꿔 외부 호출 없이 계약을 검증한다.
 */
public interface OAuthClient {

    /**
     * 인가 코드를 사용자 정보로 바꾼다.
     *
     * @throws BusinessException 코드 교환 실패는 {@code OAUTH_CODE_INVALID}, 제공자 응답 오류·통신 실패는 {@code
     *     OAUTH_PROVIDER_ERROR}
     */
    OAuthUserProfile fetchProfile(String code, String redirectUri);
}
