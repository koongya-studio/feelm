package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * API-012 비밀번호 변경.
 *
 * <p>현재 비밀번호에는 형식 규칙을 걸지 않는다. 정책이 바뀌기 전에 만든 비밀번호도 대조는 통과해야 하고, 형식 오류(400)와 불일치(401)를 나누면 저장된 비밀번호의
 * 형태를 추측할 여지를 준다.
 */
public record PasswordChangeRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.") String currentPassword,
        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
                @Pattern(
                        regexp = SignupRequest.PASSWORD_PATTERN,
                        message = "비밀번호는 8~64자이며 영문·숫자·특수문자를 각각 1자 이상 포함해야 합니다.")
                String newPassword) {}
