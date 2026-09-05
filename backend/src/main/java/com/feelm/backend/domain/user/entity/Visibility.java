package com.feelm.backend.domain.user.entity;

/** 프로필 공개 범위. 가입 시 기본값은 {@code PRIVATE}이며 변경은 API-015가 담당한다. */
public enum Visibility {
    PUBLIC,
    PRIVATE
}
