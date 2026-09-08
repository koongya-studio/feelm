package com.feelm.backend.domain.auth.dto.response;

import com.feelm.backend.domain.auth.service.OAuthLoginResult;

/**
 * 연결된 사용자가 없는 소셜 계정에 돌려주는 응답(API-007).
 *
 * <p>{@code signupRequired}를 항상 {@code true}로 내려 로그인 응답과 구분한다. 프론트는 이 값으로 화면을 나눈다.
 */
public record OAuthSignupRequiredResponse(
        boolean signupRequired, String signupToken, String suggestedNickname) {

    public static OAuthSignupRequiredResponse from(OAuthLoginResult result) {
        return new OAuthSignupRequiredResponse(
                true, result.signupToken(), result.suggestedNickname());
    }
}
