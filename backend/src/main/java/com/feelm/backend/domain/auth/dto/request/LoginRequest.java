package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * API-004 이메일 로그인 요청.
 *
 * <p>이메일에 형식 검증을 걸지 않는다. 가입 규칙이 바뀌어도 기존 계정이 로그인할 수 있어야 하고, 자격 증명 오류는 어느 쪽이 틀렸는지 구분하지 않고 모두 {@code
 * INVALID_CREDENTIALS}로 응답해야 하기 때문이다.
 */
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.") String email,
        @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {}
