package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API-079 이메일 인증번호 확인 요청.
 *
 * <p>6자리 숫자가 아닌 번호는 대조하지 않고 {@code 400 VALIDATION_FAILED}로 거절한다. 형식이 틀린 값까지 대조하면 시도 예산이 대입이 될 수 없는
 * 요청에도 소모된다.
 */
public record EmailVerificationCodeVerifyRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
                @Email(message = "이메일 형식이 올바르지 않습니다.")
                @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
                String email,
        @NotBlank(message = "인증번호를 입력해 주세요.")
                @Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 6자리 숫자입니다.")
                String code) {}
