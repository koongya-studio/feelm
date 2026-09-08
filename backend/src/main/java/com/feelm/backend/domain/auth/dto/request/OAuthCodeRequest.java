package com.feelm.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 소셜 로그인(API-007)과 계정 연결(API-009)의 요청 본문.
 *
 * <p>두 API는 목적이 다르지만 본문 계약이 같고, 인가 코드 교환이라는 같은 의미를 담고 있어 하나의 DTO를 쓴다.
 *
 * <p>인가 코드는 백엔드가 제공자와 교환한다(D-3). {@code redirectUri}는 코드 발급 때 사용한 값과 같아야 하며, 허용 목록 검증은 Service가 한다.
 */
public record OAuthCodeRequest(
        @NotBlank(message = "인가 코드가 필요합니다.") String code,
        @NotBlank(message = "redirectUri가 필요합니다.") String redirectUri) {}
