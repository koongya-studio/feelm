package com.feelm.backend.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 관심없음 신호 payload. 추천 외 화면에서는 추천 카드 식별자를 생략한다. */
public record DismissEventPayload(
        long movieId, @JsonInclude(JsonInclude.Include.NON_NULL) Long recommendationItemId) {}
