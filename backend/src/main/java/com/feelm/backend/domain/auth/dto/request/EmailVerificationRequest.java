package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** API-002 이메일 인증 확인 요청. 토큰은 불투명 문자열이므로 형식은 검사하지 않는다(플랜 결정 8). */
public record EmailVerificationRequest(@NotBlank(message = "인증 토큰이 필요합니다.") String token) {}
