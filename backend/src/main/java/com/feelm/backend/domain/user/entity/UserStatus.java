package com.feelm.backend.domain.user.entity;

/**
 * 계정 생명주기 상태. {@code users.status} CHECK 제약과 값이 일치해야 한다.
 *
 * <p>인증 대기 상태({@code PENDING_VERIFICATION})는 없앴다(S15P21E106-604). 가입 전에 인증번호로 이메일 소유를 확인하므로 계정은
 * 만들어지는 순간 {@code ACTIVE}이고, 그 값은 마이그레이션이 CHECK 제약과 잔존 행에서 함께 지웠다.
 */
public enum UserStatus {
    ACTIVE,
    WITHDRAWN
}
