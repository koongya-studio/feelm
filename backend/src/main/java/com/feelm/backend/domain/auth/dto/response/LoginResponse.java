package com.feelm.backend.domain.auth.dto.response;

import com.feelm.backend.domain.auth.service.LoginService;

/**
 * API-004 이메일 로그인 응답. Refresh Token은 쿠키로만 전달하므로 본문에 넣지 않는다.
 *
 * <p>{@code onboardingCompleted}가 {@code false}면 프론트가 초기 취향 설정 화면으로 보낸다(API-004 처리 규칙).
 */
public record LoginResponse(String accessToken, long expiresIn, UserSummary user) {

    public static LoginResponse from(LoginService.LoginResult result) {
        return new LoginResponse(
                result.accessToken(),
                result.expiresIn(),
                new UserSummary(result.userId(), result.nickname(), result.onboardingCompleted()));
    }

    /** 로그인 직후 화면 분기에 필요한 최소 사용자 정보. */
    public record UserSummary(Long userId, String nickname, boolean onboardingCompleted) {}
}
