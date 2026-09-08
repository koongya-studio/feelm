package com.feelm.backend.domain.auth.event;

/**
 * 비밀번호 재설정 메일을 보내야 한다는 사실을 알린다. 재설정 요청(API-010)이 발행한다.
 *
 * <p>토큰 원문은 발급 시점에만 알 수 있어 이벤트에 실어 넘긴다. 이벤트는 프로세스 안에서만 전달되며 저장·직렬화하지 않는다.
 */
public record PasswordResetMailRequestedEvent(
        Long userId, String email, String passwordResetToken) {}
