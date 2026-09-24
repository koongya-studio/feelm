package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.repository.RecommendationReasonRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 외부 호출이 끝난 뒤 아직 노출되지 않은 이유를 <b>항목마다 별도 커밋</b>으로 확정한다.
 *
 * <p>세트 단위 한 트랜잭션으로 묶으면 안 된다. 이 쓰기는 사용자 행 잠금을 잡지 않으므로 같은 세트를 서빙하는 조회({@code
 * RecommendationServingReader})와 동시에 돌 수 있고, 조회는 항목을 DISCOVERY·TASTE의 {@code position} 순으로 {@code
 * FOR UPDATE} 잠그는 반면 이 쓰기는 {@code id} 순으로 UPDATE한다. 두 순서가 반대라 한 트랜잭션에서 여러 항목을 잠그면 서로 상대가 잠근 행을 기다리는
 * 교착이 난다(S15P21E106-804). 항목 하나를 UPDATE하고 바로 커밋하면 이 쓰기는 잠금을 한 번에 하나만 들어 어떤 상대·어떤 잠금 순서와도 교착이 성립하지
 * 않는다.
 *
 * <p>각 UPDATE는 {@code exposed_at IS NULL}이고 문구가 미확정인 항목만 바꾸므로 항목별로 독립적이고 다시 실행해도 같다. 일부만 반영된 채 끝나도
 * 남은 항목은 다음 보강이 이어받는다. 상위 트랜잭션 안에서 호출되면 항목 단위 커밋이 깨지므로 즉시 실패한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RecommendationReasonSnapshotWriter {

    private final RecommendationReasonRepository repository;

    public void complete(List<ReasonSnapshot> snapshots) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 호출자(dispatcher, createBuffer)가 RuntimeException을 삼키고 클래스 이름만 남기므로 원인은 여기서 직접 기록한다.
            log.error("추천 이유 확정이 트랜잭션 안에서 호출되었습니다. 항목 단위 커밋이 깨져 서빙 조회와 교착할 수 있어 중단합니다.");
            throw new IllegalStateException("추천 이유 확정은 항목 단위로 커밋해야 하므로 트랜잭션 안에서 호출할 수 없습니다.");
        }
        for (ReasonSnapshot snapshot : snapshots) {
            // 트랜잭션 밖의 JdbcTemplate 호출은 문장마다 autocommit되어 항목 하나의 잠금만 잠깐 든다.
            if ("[]".equals(snapshot.usedEvidenceIdsJson())) {
                repository.completeIfUnexposed(
                        snapshot.itemId(), snapshot.summary(), snapshot.factorsJson());
            } else {
                repository.completeIfUnexposed(
                        snapshot.itemId(),
                        snapshot.summary(),
                        snapshot.factorsJson(),
                        snapshot.usedEvidenceIdsJson());
            }
        }
    }

    record ReasonSnapshot(
            long itemId, String summary, String factorsJson, String usedEvidenceIdsJson) {

        ReasonSnapshot(long itemId, String summary, String factorsJson) {
            this(itemId, summary, factorsJson, "[]");
        }
    }
}
