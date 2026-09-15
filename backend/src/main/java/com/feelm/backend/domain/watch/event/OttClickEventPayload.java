package com.feelm.backend.domain.watch.event;

/** 보러가기 클릭을 행동 Feature로 집계하기 위한 OTT_CLICK payload. */
public record OttClickEventPayload(long movieId, long ottId, long watchRecordId) {

    public OttClickEventPayload {
        if (movieId <= 0 || ottId <= 0 || watchRecordId <= 0) {
            throw new IllegalArgumentException("OTT_CLICK identifiers must be positive");
        }
    }
}
