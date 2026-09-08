package com.feelm.backend.domain.auth.entity;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.Locale;

/**
 * 지원하는 소셜 로그인 제공자(API-007 상태값).
 *
 * <p>이름은 {@code user_auth_providers.provider}의 CHECK 제약과 같은 값이다. 새 제공자를 추가하려면 DB 제약도 함께 바꿔야 한다.
 */
public enum OAuthProvider {
    GOOGLE,
    KAKAO,
    NAVER;

    /**
     * 경로 변수의 제공자 이름을 변환한다. 명세의 경로 변수는 소문자이지만 대소문자를 가리지 않는다.
     *
     * @throws BusinessException 목록에 없는 이름은 {@code UNSUPPORTED_PROVIDER}
     */
    public static OAuthProvider from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
        }
    }
}
