package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.dto.request.OAuthCodeRequest;
import com.feelm.backend.domain.auth.dto.request.OAuthSignupRequest;
import com.feelm.backend.domain.auth.dto.response.LoginResponse;
import com.feelm.backend.domain.auth.dto.response.OAuthSignupRequiredResponse;
import com.feelm.backend.domain.auth.entity.OAuthProvider;
import com.feelm.backend.domain.auth.service.LoginService;
import com.feelm.backend.domain.auth.service.OAuthLoginResult;
import com.feelm.backend.domain.auth.service.OAuthLoginService;
import com.feelm.backend.domain.auth.service.OAuthSignupService;
import com.feelm.backend.global.security.RefreshTokenCookieFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 소셜 로그인·가입 엔드포인트(API-007~008).
 *
 * <p>계정 연결(API-009)은 회원 전용이라 경로도 {@code /users/me} 아래에 있고 인증 정책이 달라 {@link OAuthLinkController}에
 * 둔다.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthLoginService oAuthLoginService;
    private final OAuthSignupService oAuthSignupService;
    private final RefreshTokenCookieFactory cookieFactory;

    /**
     * API-007 소셜 로그인.
     *
     * <p>연결된 사용자가 있으면 이메일 로그인과 같은 본문·쿠키를, 없으면 가입 토큰을 돌려준다. 명세가 두 경우를 모두 200으로 정의하고 본문 구조로 구분한다.
     */
    @PostMapping("/{provider}")
    ResponseEntity<Object> login(
            @PathVariable String provider, @RequestBody @Valid OAuthCodeRequest request) {
        OAuthLoginResult result =
                oAuthLoginService.login(
                        OAuthProvider.from(provider), request.code(), request.redirectUri());
        if (result.isSignupRequired()) {
            return ResponseEntity.ok(OAuthSignupRequiredResponse.from(result));
        }
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieFactory.create(result.login().refreshToken()).toString())
                .body(LoginResponse.from(result.login()));
    }

    /** API-008 소셜 회원가입 완료. 가입과 동시에 로그인되므로 응답은 로그인과 같은 본문·쿠키다. */
    @PostMapping("/signup")
    ResponseEntity<LoginResponse> signup(@RequestBody @Valid OAuthSignupRequest request) {
        LoginService.LoginResult result = oAuthSignupService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieFactory.create(result.refreshToken()).toString())
                .body(LoginResponse.from(result));
    }
}
