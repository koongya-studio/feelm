package com.feelm.backend.domain.auth.dto.response;

import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.entity.UserStatus;

/** API-001 이메일 회원가입 응답. */
public record SignupResponse(Long userId, String email, String nickname, UserStatus status) {

    public static SignupResponse from(User user) {
        return new SignupResponse(
                user.getId(), user.getEmail(), user.getNickname(), user.getStatus());
    }
}
