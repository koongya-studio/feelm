package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * API-011 비밀번호 재설정.
 *
 * <p>새 비밀번호 규칙은 가입(API-001)과 같은 {@link SignupRequest#PASSWORD_PATTERN}을 쓴다. 경로마다 규칙이 갈리면 어느 경로로
 * 만들었는지에 따라 통과하는 비밀번호가 달라진다.
 */
public record PasswordResetRequest(
        @NotBlank(message = "재설정 토큰이 필요합니다.") String token,
        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
                @Pattern(
                        regexp = SignupRequest.PASSWORD_PATTERN,
                        message = "비밀번호는 8~64자이며 영문·숫자·특수문자를 각각 1자 이상 포함해야 합니다.")
                String newPassword) {}
