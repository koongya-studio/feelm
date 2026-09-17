package com.feelm.backend.domain.recommendation.model;

import java.math.BigDecimal;
import java.util.List;

/** OTT 집계에 필요한 사용자별 TASTE 후보의 최소 정보다. */
public record OttCandidateSnapshot(List<ScoredMovie> taste) {

    public record ScoredMovie(long movieId, BigDecimal score) {}
}
