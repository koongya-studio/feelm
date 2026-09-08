package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API-010 비밀번호 재설정 요청.
 *
 * <p>검증은 형식만 본다. 가입 여부는 응답에 드러나지 않아야 하므로(API-010 처리 규칙) 존재 확인은 Service가 하고 오류로 만들지 않는다.
 */
public record PasswordResetMailRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
                @Email(message = "이메일 형식이 올바르지 않습니다.")
                @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
                String email) {}
