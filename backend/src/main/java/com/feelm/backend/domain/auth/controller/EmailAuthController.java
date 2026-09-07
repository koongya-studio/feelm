package com.feelm.backend.domain.auth.controller;

import com.feelm.backend.domain.auth.dto.request.EmailResendRequest;
import com.feelm.backend.domain.auth.dto.request.EmailVerificationRequest;
import com.feelm.backend.domain.auth.dto.request.SignupRequest;
import com.feelm.backend.domain.auth.dto.response.EmailVerificationResponse;
import com.feelm.backend.domain.auth.dto.response.SignupResponse;
import com.feelm.backend.domain.auth.service.EmailVerificationService;
import com.feelm.backend.domain.auth.service.SignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일 계정 엔드포인트(API-001~003). 로그인·로그아웃(API-004~005)은 후속 조각에서 이 컨트롤러에 추가한다.
 *
 * <p>토큰 수명 관리(API-006)는 {@link com.feelm.backend.domain.auth.AuthController}에 남긴다. 그 컨트롤러는 재발급 실패 시
 * 쿠키를 지우는 예외 처리를 갖고 있어, 자격 증명 오류에 쿠키 삭제 헤더가 실리면 안 되는 이 엔드포인트들과 예외 처리 정책이 다르다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class EmailAuthController {

    private final SignupService signupService;
    private final EmailVerificationService emailVerificationService;

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
}
