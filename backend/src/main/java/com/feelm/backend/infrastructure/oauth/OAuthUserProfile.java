package com.feelm.backend.infrastructure.oauth;

/**
 * 제공자에게서 받은 사용자 정보.
 *
 * <p>{@code providerUserId}만 항상 존재한다. 이메일과 닉네임은 제공자와 사용자의 동의 범위에 따라 없을 수 있어(D-2) 가입 경로에서 이메일이 없으면
 * 거절하고, 연결 경로(API-009)는 이메일 없이도 진행한다.
 */
public record OAuthUserProfile(String providerUserId, String email, String nickname) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
