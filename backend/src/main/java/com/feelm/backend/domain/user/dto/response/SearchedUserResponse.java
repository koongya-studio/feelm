package com.feelm.backend.domain.user.dto.response;

import com.feelm.backend.domain.user.entity.Visibility;

/**
 * 사용자 검색 결과 한 건(API-019).
 *
 * <p>{@code visibility}를 함께 내려보내는 이유는 {@code PRIVATE} 사용자도 결과에 포함하기 때문이다. 가입 기본값이 {@code
 * PRIVATE}이라(ERD 2.1) 제외하면 FR-20의 닉네임 초대가 사실상 막힌다. 대신 클라이언트가 이 값으로 초대 후보 카드의 노출 수위를 정한다.
 */
public record SearchedUserResponse(long userId, String nickname, Visibility visibility) {}
