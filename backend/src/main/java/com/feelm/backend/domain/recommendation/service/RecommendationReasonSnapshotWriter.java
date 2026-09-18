package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.repository.RecommendationReasonRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 외부 호출이 끝난 뒤 아직 노출되지 않은 이유만 한 트랜잭션에서 확정한다. */
@Component
@RequiredArgsConstructor
class RecommendationReasonSnapshotWriter {

    private final RecommendationReasonRepository repository;

    @Transactional
    public void complete(List<ReasonSnapshot> snapshots) {
        for (ReasonSnapshot snapshot : snapshots) {
            repository.completeIfUnexposed(
                    snapshot.itemId(), snapshot.summary(), snapshot.factorsJson());
        }
    }

    record ReasonSnapshot(long itemId, String summary, String factorsJson) {}
}
