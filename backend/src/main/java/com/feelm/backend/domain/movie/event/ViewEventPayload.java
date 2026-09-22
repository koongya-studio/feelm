package com.feelm.backend.domain.movie.event;

/** 회원의 정상 영화 상세 조회를 급상승 집계 입력으로 전달하는 VIEW payload. */
public record ViewEventPayload(long movieId) {

    public ViewEventPayload {
        if (movieId <= 0) {
            throw new IllegalArgumentException("VIEW movie ID must be positive");
        }
    }
}
