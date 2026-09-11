package com.feelm.backend.domain.ott.entity;

import java.io.Serializable;
import java.util.Objects;

/** {@link UserOtt}의 복합 기본키. */
public class UserOttId implements Serializable {

    private Long userId;
    private Long ottId;

    /** JPA 명세가 {@code @IdClass}에 public 기본 생성자를 요구한다. */
    public UserOttId() {}

    public UserOttId(Long userId, Long ottId) {
        this.userId = userId;
        this.ottId = ottId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserOttId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(ottId, that.ottId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, ottId);
    }
}
