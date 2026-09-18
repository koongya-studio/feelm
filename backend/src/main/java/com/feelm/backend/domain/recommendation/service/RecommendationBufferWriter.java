package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository.RecommendationBufferItem;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 계약 검증을 통과한 후보를 사용자 상태에 맞게 필터링·재정렬하고 PostgreSQL에 저장한다.
 *
 * <p>세트와 아이템은 한 트랜잭션에서 저장하며, 같은 generation ID의 반복 요청은 기존 세트를 재사용한다.
 */
@Component
@RequiredArgsConstructor
class RecommendationBufferWriter {

    private static final int BUFFER_SIZE = 20;
    private static final String REDIS_CANDIDATE_INPUT_VERSION = "redis-candidate";

    private final RecommendationBufferRepository repository;
    private final Clock clock;

    /**
     * 후보 묶음을 최대 20편의 추천 버퍼로 원자적으로 저장한다.
     *
     * @throws BusinessException 사용자가 유효하지 않거나 저장할 후보가 없는 경우
     */
    @Transactional
    public RecommendationBufferResult write(long userId, RecommendationCandidateBatch batch) {
        return writeIfEligible(userId, batch)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE));
    }

    /** 적격 후보가 없으면 기존 추천 세트를 유지할 수 있도록 빈 결과를 반환한다. */
    @Transactional
    public Optional<RecommendationBufferResult> writeIfEligible(
            long userId, RecommendationCandidateBatch batch) {
        if (!repository.lockActiveUserIfExists(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        var existing = repository.findExistingSet(batch.generationId());
        if (existing.isPresent()) {
            return Optional.of(new RecommendationBufferResult(existing.getAsLong()));
        }

        List<RecommendationBufferItem> items = prepare(userId, batch);
        if (items.isEmpty()) {
            return Optional.empty();
        }

        long setId =
                repository.insertSet(
                        userId,
                        REDIS_CANDIDATE_INPUT_VERSION,
                        batch.modelVersion(),
                        batch.candidateVersion(),
                        batch.generationId(),
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        repository.insertItems(setId, items);
        return Optional.of(new RecommendationBufferResult(setId));
    }

    private List<RecommendationBufferItem> prepare(
            long userId, RecommendationCandidateBatch batch) {
        Set<Long> eligibleMovieIds = repository.findEligibleMovieIds(userId, batch.items());
        List<RecommendationCandidate> taste = rank(batch, userId, eligibleMovieIds, "TASTE");
        List<RecommendationCandidate> discovery =
                rank(batch, userId, eligibleMovieIds, "DISCOVERY");
        List<RecommendationBufferItem> items = new ArrayList<>();
        append(items, taste);
        Set<Long> storedMovieIds =
                items.stream().map(RecommendationBufferItem::movieId).collect(Collectors.toSet());
        append(
                items,
                discovery.stream()
                        .filter(candidate -> !storedMovieIds.contains(candidate.movieId()))
                        .toList());
        return List.copyOf(items);
    }

    private void append(
            List<RecommendationBufferItem> items, List<RecommendationCandidate> candidates) {
        for (int index = 0; index < Math.min(BUFFER_SIZE, candidates.size()); index++) {
            RecommendationCandidate candidate = candidates.get(index);
            items.add(
                    new RecommendationBufferItem(
                            candidate.movieId(),
                            candidate.type(),
                            candidate.flavorCode(),
                            candidate.reason(),
                            index + 1));
        }
    }

    private List<RecommendationCandidate> rank(
            RecommendationCandidateBatch batch,
            long userId,
            Set<Long> eligibleMovieIds,
            String type) {
        List<RecommendationCandidate> ranked =
                new ArrayList<>(
                        batch.items().stream()
                                .filter(candidate -> candidate.type().equals(type))
                                .filter(candidate -> eligibleMovieIds.contains(candidate.movieId()))
                                .sorted(
                                        Comparator.comparing(RecommendationCandidate::score)
                                                .reversed())
                                .toList());
        int start = 0;
        while (start < ranked.size()) {
            int end = start + 1;
            while (end < ranked.size()
                    && ranked.get(start).score().compareTo(ranked.get(end).score()) == 0) {
                end++;
            }
            Collections.shuffle(
                    ranked.subList(start, end),
                    new Random(seed(userId, batch.generationId(), type, ranked.get(start))));
            start = end;
        }
        return ranked;
    }

    private long seed(
            long userId, String generationId, String type, RecommendationCandidate candidate) {
        String source = userId + ":" + generationId + ":" + type + ":" + candidate.score();
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < source.length(); index++) {
            hash ^= source.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
