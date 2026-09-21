package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 인가 코드를 다시 교환해 "지금 이 요청을 보낸 사람이 그 소셜 계정의 주인"임을 확인한다.
 *
 * <p>비밀번호가 없는 계정의 본인 확인 수단이다(S15P21E106-435 결정 D1). 되돌릴 수 없는 동작 앞에서 Access Token 하나만 보지 않는다는 점에서
 * 이메일 계정의 비밀번호 재입력과 같은 자리에 있다.
 *
 * <p>{@link OAuthProfileLoader}를 감싸기만 하는 얇은 층인데도 따로 두는 이유는 경계 때문이다. 교환 지점은 {@code auth} 도메인 안에 감춰
 * package-private로 두고, 탈퇴(API-013)처럼 다른 도메인이 재인증을 필요로 할 때는 이 공개 지점만 주입받는다(컨벤션 11장).
 *
 * <p>이 호출은 외부 HTTP 통신이므로 호출자가 DB 트랜잭션 밖에서 사용한다(컨벤션 8장). 교환 결과를 계정과 대조하는 일은 호출자의 몫이다 — 대조는 계정 행을 잠근
 * 트랜잭션 안에서 해야 그 사이에 연결이 바뀌지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SocialReauthenticator {

    private final OAuthProfileLoader profileLoader;

    /**
     * 인가 코드를 교환해 제공자가 발급한 식별자를 돌려준다.
     *
     * <p>이메일은 보지 않는다. 확인할 것은 "이 제공자 계정의 주인인가"뿐이고, 제공자가 이메일을 주지 않아도 연결 자체는 성립하기 때문이다(D-2, API-009와
     * 같은 이유).
     *
     * @throws com.feelm.backend.global.error.BusinessException 허용 목록 밖 {@code redirectUri}는 {@code
     *     VALIDATION_FAILED}, 코드 교환 실패는 {@code OAUTH_CODE_INVALID}, 제공자 오류는 {@code
     *     OAUTH_PROVIDER_ERROR}
     */
    public String authenticate(OAuthProvider provider, String code, String redirectUri) {
        return profileLoader.load(provider, code, redirectUri).providerUserId();
    }
}
