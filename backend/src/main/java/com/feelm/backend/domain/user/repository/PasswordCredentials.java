package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.UserStatus;

/**
 * 비밀번호 대조에 필요한 값만 담은 조회 결과.
 *
 * <p>Entity가 아니라 이 형태로 읽는 자리가 있다. 같은 트랜잭션에서 {@code User}를 한 번 Entity로 읽어 두면 뒤따르는 잠금 조회가 행을 다시 읽고도
 * 먼저 읽어 둔 인스턴스를 돌려주므로(JPA 1차 캐시), 그 사이 다른 트랜잭션이 바꾼 열이 보이지 않는다. 자세한 사정은 {@link
 * UserRepository#findPasswordCredentialsById}에 있다.
 */
public record PasswordCredentials(UserStatus status, String passwordHash) {

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
