package com.feelm.backend.domain.user.entity;

/** 계정 생명주기 상태. {@code users.status} CHECK 제약과 값이 일치해야 한다. */
public enum UserStatus {
    /** 가입했으나 이메일 인증을 마치지 않은 상태. 로그인할 수 없다(API-004). */
    PENDING_VERIFICATION,
    ACTIVE,
    WITHDRAWN
}
