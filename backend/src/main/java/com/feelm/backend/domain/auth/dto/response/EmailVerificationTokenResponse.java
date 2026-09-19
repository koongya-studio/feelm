package com.feelm.backend.domain.auth.dto.response;

/**
 * API-079 이메일 인증번호 확인 응답.
 *
 * @param emailVerificationToken 가입(API-001)에 1회만 쓸 수 있는 서명 토큰
 * @param expiresIn 토큰의 총 수명(초)
 */
public record EmailVerificationTokenResponse(String emailVerificationToken, long expiresIn) {}
