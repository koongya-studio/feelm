package com.feelm.backend.domain.party.model;

import java.util.List;

/** Spark에 전송하는 §4.3 전용 JSON. 세대와 모델 버전은 inputId에만 반영한다. */
public record PartyRefreshPayload(long partyId, String inputId, List<Member> members) {
    public record Member(long userId, List<Double> vector, List<Long> candidateMovieIds) {}
}
