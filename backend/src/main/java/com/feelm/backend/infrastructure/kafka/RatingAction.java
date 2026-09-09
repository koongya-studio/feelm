package com.feelm.backend.infrastructure.kafka;

/** 평가 생성·수정·삭제 사건을 구분하는 계약 값이다. */
public enum RatingAction {
    CREATE,
    UPDATE,
    DELETE
}
