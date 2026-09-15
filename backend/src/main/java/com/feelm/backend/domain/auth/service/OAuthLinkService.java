package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.entity.UserAuthProvider;
import com.feelm.backend.infrastructure.oauth.OAuthUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * API-009 소셜 계정 연결. 로그인한 사용자의 계정에 소셜 로그인 수단을 추가한다.
 *
 * <p>이메일은 필요하지 않다. 연결은 이미 있는 계정에 로그인 수단만 더하는 일이고, 제공자가 이메일을 주지 않을 수 있기 때문이다(D-2).
 *
 * <p>제공자 호출은 트랜잭션 밖에서 한다(컨벤션 8장). 호출이 끝난 뒤 {@link OAuthLinkWriter}가 계정 행을 잠그고 삽입까지 한 트랜잭션으로 끝낸다 —
 * 확인과 삽입 사이에 탈퇴(API-013)가 끼어들면 연결이 되살아나 그 소셜 계정으로 재가입할 수 없게 되기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class OAuthLinkService {

    private final OAuthProfileLoader profileLoader;
    private final OAuthLinkWriter writer;

    public UserAuthProvider link(
            long userId, OAuthProvider provider, String code, String redirectUri) {
        OAuthUserProfile profile = profileLoader.load(provider, code, redirectUri);
        return writer.insert(userId, provider, profile.providerUserId());
    }
}
