package com.feelm.backend.domain.auth;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.error.ErrorResponse;
import com.feelm.backend.global.security.RefreshTokenCookieFactory;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/** 토큰 수명 관리 엔드포인트. 회원가입·로그인 등 계정 기능은 별도 업무(S15P21E106-149)에서 추가한다. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory cookieFactory;

    public AuthController(
            RefreshTokenService refreshTokenService, RefreshTokenCookieFactory cookieFactory) {
        this.refreshTokenService = refreshTokenService;
        this.cookieFactory = cookieFactory;
    }

    /** API-006 토큰 재발급. 쿠키의 Refresh Token을 회전하고 새 Access Token을 반환한다. */
    @PostMapping("/token/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        RefreshTokenService.IssuedTokens tokens =
                refreshTokenService.rotate(readRefreshToken(request));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieFactory.create(tokens.refreshToken()).toString())
                .body(TokenResponse.from(tokens));
    }

    /** 재발급 실패 시 브라우저가 폐기된 Refresh Token을 반복 전송하지 않도록 쿠키도 지운다. */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleRefreshFailure(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.expire().toString())
                .body(ErrorResponse.from(errorCode, exception.getDetails()));
    }

    /**
     * 쿠키 이름을 {@link RefreshTokenCookieFactory}에서만 관리하기 위해 {@code @CookieValue} 대신 직접 읽는다. 쿠키가 아예 없는
     * 경우도 명세상 {@code REFRESH_TOKEN_INVALID}이므로 검증 실패로 넘기지 않는다.
     */
    private String readRefreshToken(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, cookieFactory.cookieName());
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        return cookie.getValue();
    }
}
