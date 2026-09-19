package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-078 이메일 인증번호 발송 요청.
 *
 * <p>검증은 형식만 본다. 이미 가입된 이메일인지는 Service가 보고 {@code 409}로 응답한다(API-078 처리 규칙).
 */
public record EmailVerificationCodeRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
                @Email(message = "이메일 형식이 올바르지 않습니다.")
                @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
                String email) {}
