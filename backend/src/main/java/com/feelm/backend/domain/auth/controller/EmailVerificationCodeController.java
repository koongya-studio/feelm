package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.dto.request.EmailVerificationCodeRequest;
import com.feelm.backend.domain.auth.dto.request.EmailVerificationCodeVerifyRequest;
import com.feelm.backend.domain.auth.dto.response.EmailVerificationTokenResponse;
import com.feelm.backend.domain.auth.service.EmailVerificationCodeService;
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
 * 가입 전 이메일 인증번호 엔드포인트(API-078·076). 계정을 만들기 전에 쓰므로 둘 다 공개 구분이다.
 *
 * <p>이메일 계정 엔드포인트(API-001·004·005)와 컨트롤러를 나눈 이유는 생애 주기가 다르기 때문이다. 이 경로는 계정이 없는 단계에 있고, 메일 링크 인증(옛
 * API-002·003)은 S15P21E106-604가 걷어냈다.
 */
@RestController
@RequestMapping("/api/v1/auth/email/verification-code")
@RequiredArgsConstructor
public class EmailVerificationCodeController {

    private final EmailVerificationCodeService verificationCodeService;

    /**
     * API-078 이메일 인증번호 발송. 발송 여부와 무관하게 본문 없이 202로 응답한다.
     *
     * <p>클라이언트 IP는 IP 단위 발송 제한에 쓴다({@link com.feelm.backend.domain.auth.AuthRateLimiter}). {@code
     * getRemoteAddr()}는 신뢰 경계 안의 프록시가 보낸 {@code X-Real-IP}일 때만 그 값을 돌려준다(S15P21E106-288, ADR-0019).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    void sendVerificationCode(
            @RequestBody @Valid EmailVerificationCodeRequest request,
            HttpServletRequest httpRequest) {
        verificationCodeService.send(request.email(), httpRequest.getRemoteAddr());
    }

    /** API-079 이메일 인증번호 확인. 통과하면 가입(API-001)에 쓰는 단기 토큰을 발급한다. */
    @PostMapping("/verify")
    EmailVerificationTokenResponse verifyVerificationCode(
            @RequestBody @Valid EmailVerificationCodeVerifyRequest request) {
        return verificationCodeService.verify(request.email(), request.code());
    }
}
