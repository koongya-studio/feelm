package com.feelm.backend.domain.recommendation.repository;

import com.feelm.backend.domain.recommendation.model.OttCandidateSnapshot;

/** OTT 집계용 Redis TASTE 후보 조회 경계다. */
public interface OttCandidateReader {

    OttCandidateSnapshot read(long userId);
}
