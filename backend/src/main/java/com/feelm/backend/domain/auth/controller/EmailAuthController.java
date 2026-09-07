package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.auth.dto.request.EmailResendRequest;
import com.feelm.backend.domain.auth.dto.request.EmailVerificationRequest;
import com.feelm.backend.domain.auth.dto.request.LoginRequest;
import com.feelm.backend.domain.auth.dto.request.SignupRequest;
import com.feelm.backend.domain.auth.dto.response.EmailVerificationResponse;
import com.feelm.backend.domain.auth.dto.response.LoginResponse;
import com.feelm.backend.domain.auth.dto.response.SignupResponse;
import com.feelm.backend.domain.auth.service.EmailVerificationService;
import com.feelm.backend.domain.auth.service.LoginService;
import com.feelm.backend.domain.auth.service.SignupService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.security.RefreshTokenCookieFactory;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/**
 * 이메일 계정 엔드포인트(API-001~005).
 *
 * <p>토큰 수명 관리(API-006)는 {@link com.feelm.backend.domain.auth.AuthController}에 남긴다. 그 컨트롤러는 재발급 실패 시
 * 쿠키를 지우는 예외 처리를 갖고 있어, 자격 증명 오류에 쿠키 삭제 헤더가 실리면 안 되는 이 엔드포인트들과 예외 처리 정책이 다르다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class EmailAuthController {

    private final SignupService signupService;
    private final EmailVerificationService emailVerificationService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory cookieFactory;

    /** API-001 이메일 회원가입. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SignupResponse signup(@RequestBody @Valid SignupRequest request) {
        return signupService.signup(request);
    }

    /** API-002 이메일 인증 확인. */
    @PostMapping("/email/verify")
    EmailVerificationResponse verifyEmail(@RequestBody @Valid EmailVerificationRequest request) {
        return emailVerificationService.verify(request.token());
    }

    /** API-003 인증 메일 재발송. 계정 존재 여부를 노출하지 않도록 본문 없이 202로 응답한다. */
    @PostMapping("/email/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerificationMail(@RequestBody @Valid EmailResendRequest request) {
        emailVerificationService.resend(request.email());
    }

    /** API-004 이메일 로그인. Refresh Token은 본문이 아니라 쿠키로만 전달한다. */
    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        LoginService.LoginResult result = loginService.login(request.email(), request.password());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieFactory.create(result.refreshToken()).toString())
                .body(LoginResponse.from(result));
    }

    /**
     * API-005 로그아웃.
     *
     * <p>쿠키가 없거나 이미 폐기된 토큰이어도 204로 응답한다(플랜 결정 12). 로그아웃은 멱등해야 하고, 실패를 알려도 클라이언트가 할 수 있는 일이 없다. 쿠키
     * 삭제 헤더는 어느 경우에도 내려준다.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        revokeRefreshTokenIfPresent(request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expire().toString())
                .build();
    }

    private void revokeRefreshTokenIfPresent(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, cookieFactory.cookieName());
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return;
        }
        try {
            refreshTokenService.revoke(cookie.getValue());
        } catch (BusinessException exception) {
            log.debug("로그아웃 요청의 Refresh Token이 이미 유효하지 않습니다. 코드: {}", exception.getErrorCode());
        }
    }
}
