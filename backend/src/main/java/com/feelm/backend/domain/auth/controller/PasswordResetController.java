package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.dto.request.PasswordResetMailRequest;
import com.feelm.backend.domain.auth.dto.request.PasswordResetRequest;
import com.feelm.backend.domain.auth.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 재설정 엔드포인트(API-010~011). 비밀번호를 잊은 사용자가 쓰므로 둘 다 공개 구분이다.
 *
 * <p>Refresh 쿠키를 읽거나 내려주지 않는다. 재설정 성공은 모든 세션을 끊는 것이 계약이고(API-011), 클라이언트는 새 비밀번호로 다시 로그인한다.
 */
@RestController
@RequestMapping("/api/v1/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /**
     * API-010 비밀번호 재설정 요청. 계정 존재 여부를 노출하지 않도록 본문 없이 202로 응답한다.
     *
     * <p>클라이언트 IP는 IP 단위 발송 제한에 쓴다({@link com.feelm.backend.domain.auth.AuthRateLimiter}). {@code
     * getRemoteAddr()}는 신뢰 경계 안의 프록시가 보낸 {@code X-Real-IP}일 때만 그 값을 돌려준다(S15P21E106-288, {@code
     * server.tomcat.remoteip.internal-proxies}).
     */
    @PostMapping("/reset-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestPasswordReset(
            @RequestBody @Valid PasswordResetMailRequest request, HttpServletRequest httpRequest) {
        passwordResetService.request(request.email(), httpRequest.getRemoteAddr());
    }

    /** API-011 비밀번호 재설정. */
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@RequestBody @Valid PasswordResetRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
    }
}
