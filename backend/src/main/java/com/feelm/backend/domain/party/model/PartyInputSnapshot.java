package com.feelm.backend.domain.party.model;

import java.util.List;

/** 한 번의 Redis 읽기로 얻은 파티원별 동일 세대 입력. */
public record PartyInputSnapshot(String modelVersion, List<Member> members) {
    public record Member(
            long userId, String generationId, List<Double> vector, List<Long> candidateMovieIds) {}
}
