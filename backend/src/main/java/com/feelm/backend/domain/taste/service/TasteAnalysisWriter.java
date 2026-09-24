package com.feelm.backend.domain.taste.service;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import com.feelm.backend.domain.taste.repository.TasteAnalysisAggregateRepository;
import com.feelm.backend.domain.taste.repository.TasteAnalysisAggregateRepository.Aggregate;
import com.feelm.backend.domain.taste.repository.TasteAnalysisRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가가 바뀔 때 {@code taste_analyses}를 다시 계산한다(S15P21E106-775).
 *
 * <p>호출자의 평가 저장 트랜잭션 안에서 돈다. 계약이 "평가 추가·수정·삭제 트랜잭션에서 캐시를 갱신한다"로 시점을 고정했고 (`docs/api/taste.md`
 * API-040), 같은 패키지의 {@link FrameWriter}가 같은 모양이다. 전파를 {@code MANDATORY}로 두어 트랜잭션 없이 불리면 즉시 실패한다.
 *
 * <p>유스케이스마다 <b>한 번</b>만 부른다. 평가를 만드는 지점({@code RatingStateWriter.create})에 두면 온보딩 한 번 제출에 최대 10번
 * 재계산된다.
 *
 * <p>키워드도 여기서 채운다. 규칙과 산출 코드는 S15P21E106-834가 {@link TasteKeywordSelector}로 확정했고 부르는 자리가 없었다. 그 계산은
 * DB·네트워크 접근이 없고 예외를 던지지 않아 트랜잭션 길이와 실패 위험을 늘리지 않는다.
 */
@Service
public class TasteAnalysisWriter {

    private final TasteAnalysisAggregateRepository aggregateRepository;
    private final TasteAnalysisRepository tasteAnalysisRepository;
    private final TasteKeywordSelector keywordSelector;

    public TasteAnalysisWriter(
            TasteAnalysisAggregateRepository aggregateRepository,
            TasteAnalysisRepository tasteAnalysisRepository,
            TasteKeywordSelector keywordSelector) {
        this.aggregateRepository = aggregateRepository;
        this.tasteAnalysisRepository = tasteAnalysisRepository;
        this.keywordSelector = keywordSelector;
    }

    /**
     * 그 사용자의 평가 전체를 다시 집계해 캐시를 만들거나 고친다. 평가가 하나도 남지 않으면 행을 지운다 — 계약이 "평가가 하나도 없으면 행을 만들지 않는다"로 정했고,
     * 행이 없으면 조회가 빈 응답을 돌려준다.
     *
     * <p><b>호출자는 이 사용자의 {@code users} 행을 먼저 잠가야 한다.</b> 업서트가 {@code findByUserId} 뒤 {@code save}라
     * 잠금 없이 같은 사용자로 동시에 부르면 {@code uk_taste_analyses_user_id} 위반으로 평가 쓰기까지 롤백된다. 현재 네 호출자는 모두 앞서 그
     * 행을 잠근다.
     *
     * @param userId 대상 사용자. 호출자가 이미 이 사용자 행을 잠근 상태라 같은 사용자끼리는 직렬화된다
     * @param analyzedAt 분석 시각. 호출자가 평가에 쓴 값을 그대로 넘겨 한 트랜잭션에 시각이 하나만 있게 한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(long userId, Instant analyzedAt) {
        Objects.requireNonNull(analyzedAt, "분석 시각은 필수입니다.");
        Aggregate aggregate = aggregateRepository.aggregate(userId);
        if (aggregate.isEmpty()) {
            tasteAnalysisRepository.deleteByUserId(userId);
            return;
        }

        List<TasteAnalysis.Keyword> keywords =
                keywordSelector.select(
                        aggregate.ratingCount(), aggregate.averageScore(), aggregate.analysis());
        Instant at = analyzedAt.truncatedTo(ChronoUnit.MICROS);
        tasteAnalysisRepository
                .findByUserId(userId)
                .ifPresentOrElse(
                        existing ->
                                existing.refresh(
                                        aggregate.analysis(),
                                        keywords,
                                        aggregate.ratingCount(),
                                        at),
                        () ->
                                tasteAnalysisRepository.save(
                                        TasteAnalysis.of(
                                                userId,
                                                aggregate.analysis(),
                                                keywords,
                                                aggregate.ratingCount(),
                                                at)));
    }
}
