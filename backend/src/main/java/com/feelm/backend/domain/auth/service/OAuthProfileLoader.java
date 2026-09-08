package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.oauth.OAuthClient;
import com.feelm.backend.infrastructure.oauth.OAuthClients;
import com.feelm.backend.infrastructure.oauth.OAuthProperties;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 인가 코드를 사용자 정보로 바꾸는 공통 지점이다. 소셜 로그인(API-007)과 계정 연결(API-009)이 함께 쓴다.
 *
 * <p>제공자를 호출하기 전에 {@code redirectUri}가 허용 목록에 있는지 확인한다(D-4). 허용하지 않은 주소로 코드를 교환해 주면, 공격자가 자신이 통제하는
 * 화면으로 인가 코드를 돌려받아 다른 사용자의 소셜 계정으로 로그인할 수 있다.
 *
 * <p>제공자 호출은 외부 HTTP 통신이므로 호출자는 이 지점을 DB 트랜잭션 밖에서 사용한다(컨벤션 8장).
 */
@Component
@RequiredArgsConstructor
class OAuthProfileLoader {

    private final OAuthClients oAuthClients;
    private final OAuthProperties oAuthProperties;

    OAuthUserProfile load(OAuthProvider provider, String code, String redirectUri) {
        if (!oAuthProperties.isAllowedRedirectUri(redirectUri)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        OAuthClient client = oAuthClients.get(provider);
        return client.fetchProfile(code, redirectUri);
    }
}
