package com.feelm.backend.domain.auth.service;

/**
 * 소셜 로그인(API-007)의 두 갈래 결과.
 *
 * <p>연결된 사용자가 있으면 이메일 로그인과 같은 {@link LoginService.LoginResult}를, 없으면 가입을 이어갈 토큰과 추천 닉네임을 담는다. 두 경우의
 * 응답 본문이 다르므로 Controller가 이 구분을 보고 응답을 만든다.
 */
public record OAuthLoginResult(
        LoginService.LoginResult login, String signupToken, String suggestedNickname) {

    static OAuthLoginResult loggedIn(LoginService.LoginResult login) {
        return new OAuthLoginResult(login, null, null);
    }

    static OAuthLoginResult signupRequired(String signupToken, String suggestedNickname) {
        return new OAuthLoginResult(null, signupToken, suggestedNickname);
    }

    public boolean isSignupRequired() {
        return login == null;
    }
}
