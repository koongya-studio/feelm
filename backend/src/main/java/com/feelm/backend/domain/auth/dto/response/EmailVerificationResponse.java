package com.feelm.backend.domain.auth.dto.response;

import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.entity.UserStatus;

/** API-002 이메일 인증 확인 응답. */
public record EmailVerificationResponse(Long userId, UserStatus status) {

    public static EmailVerificationResponse from(User user) {
        return new EmailVerificationResponse(user.getId(), user.getStatus());
    }
}
