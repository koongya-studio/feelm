package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidate;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository;
import com.feelm.backend.domain.recommendation.repository.RecommendationBufferRepository.RecommendationBufferItem;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import com.feelm.backend.domain.recommendation.repository.RecommendationServingRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
    private final RecommendationServingRepository servingRepository;
    private final RecommendationCandidateReader candidateReader;
    private final RecommendationReasonService reasonService;
    private final Clock clock;

    /**
     * 후보 묶음을 최대 20편의 추천 버퍼로 원자적으로 저장한다.
     *
     * @throws BusinessException 사용자가 유효하지 않거나 Redis Candidate를 사용할 수 없는 경우
     * @throws NoEligibleRecommendationCandidatesException 필터 후 저장할 영화가 없는 경우
     */
    @Transactional
    public RecommendationBufferResult writeCurrent(long userId) {
        return writeCurrent(userId, false);
    }

    @Transactional
    public RecommendationBufferResult writeCurrentFromNotice(long userId) {
        return writeCurrent(userId, true);
    }

    /**
     * 소진된 세트 뒤에 같은 generation의 다음 회차를 저장한다.
     *
     * <p>후보는 호출자가 잠금 밖에서 읽어 넘긴다. 사용자 행 잠금을 쥔 채 Redis를 기다리면 같은 사용자의 Candidate 알림 처리가 그 잠금에서 막힌다.
     *
     * <p>잠금을 잡은 뒤 두 가지를 다시 확인한다. 서빙 대상이 여전히 {@code exhaustedSetId}인지(먼저 도착한 요청이 이미 회차를 만들었는가)와 그
     * 세트가 여전히 소진 상태인지(판정 뒤 평가 철회 등으로 카드가 되살아났는가). 어느 쪽이든 회차를 만들지 않고 기존 세트를 돌려준다.
     *
     * @throws NoEligibleRecommendationCandidatesException 이전 회차와 사용자 상태를 제외하고 남은 후보가 없는 경우
     */
    @Transactional
    public RecommendationBufferResult writeNextBatch(
            long userId, long exhaustedSetId, RecommendationCandidateBatch batch) {
        // 같은 사용자의 조회·알림 경로를 이 잠금 하나로 직렬화한다. 비활성 계정은 여기서 끝낸다.
        if (!repository.lockActiveUserIfExists(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        OptionalLong latestSetId = repository.findLatestSetId(userId);
        // 소진이 아니라 아직 버퍼가 없는 상태다. 회차를 이어 만들 대상이 없다.
        if (latestSetId.isEmpty()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
        // 다른 요청이 이미 새로운 버퍼를 만든 경우
        if (latestSetId.getAsLong() != exhaustedSetId) {
            return new RecommendationBufferResult(latestSetId.getAsLong(), false);
        }
        // 판정이 낡은 경우. 소진 판정 뒤 평가 철회로 카드가 되살아났으면 회차를 만들지 않는다.
        // 만들면 다음 조회가 새 회차를 집어 되살아난 카드가 영구 유실된다.
        if (servingRepository.hasValidItems(userId, exhaustedSetId)) {
            return new RecommendationBufferResult(exhaustedSetId, false);
        }

        // 같은 generation에 회차가 있으면 그다음 번호, Redis가 새 generation으로 바뀌었으면 첫 회차(0)다.
        OptionalInt latestBatchNo = repository.findLatestBatchNo(userId, batch.generationId());
        int nextBatchNo = latestBatchNo.isPresent() ? latestBatchNo.getAsInt() + 1 : 0;
        // 이전 회차가 저장한 영화를 빼야 "같은 20편"이 아니라 "다음 20편"이 된다.
        Set<Long> storedMovieIds =
                latestBatchNo.isPresent()
                        ? repository.findStoredMovieIdsInGeneration(userId, batch.generationId())
                        : Set.of();

        List<RecommendationBufferItem> items = prepare(userId, batch, storedMovieIds);
        // 평가·관심없음·이전 회차를 빼고 남은 후보가 없다. 호출자가 503으로 바꾼다.
        if (items.isEmpty()) {
            throw new NoEligibleRecommendationCandidatesException();
        }
        long setId =
                repository.insertSet(
                        userId,
                        REDIS_CANDIDATE_INPUT_VERSION,
                        batch.modelVersion(),
                        batch.candidateVersion(),
                        batch.generationId(),
                        nextBatchNo,
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        repository.insertItems(setId, withInitialReason(items));
        return new RecommendationBufferResult(setId);
    }

    private RecommendationBufferResult writeCurrent(long userId, boolean initialReason) {
        if (!repository.lockActiveUserIfExists(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        RecommendationCandidateBatch batch = candidateReader.read(userId);

        var existing = repository.findExistingSet(userId, batch.generationId());
        if (existing.isPresent()) {
            return new RecommendationBufferResult(existing.getAsLong(), false);
        }

        List<RecommendationBufferItem> items = prepare(userId, batch, Set.of());
        if (items.isEmpty()) {
            throw new NoEligibleRecommendationCandidatesException();
        }
        if (initialReason) {
            items = withInitialReason(items);
        }

        long setId =
                repository.insertSet(
                        userId,
                        REDIS_CANDIDATE_INPUT_VERSION,
                        batch.modelVersion(),
                        batch.candidateVersion(),
                        batch.generationId(),
                        0,
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        repository.insertItems(setId, items);
        return new RecommendationBufferResult(setId);
    }

    /** 첫 노출 전에 확인된 근거만으로 문구를 확정한다. 이유 보강이 늦거나 실패해도 중립 문구로 고착되지 않는다. */
    private List<RecommendationBufferItem> withInitialReason(List<RecommendationBufferItem> items) {
        return items.stream()
                .map(
                        item ->
                                new RecommendationBufferItem(
                                        item.movieId(),
                                        item.type(),
                                        item.flavorCode(),
                                        reasonService.initialReason(item.reason()),
                                        item.position()))
                .toList();
    }

    private List<RecommendationBufferItem> prepare(
            long userId, RecommendationCandidateBatch batch, Set<Long> excludedMovieIds) {
        Set<Long> eligibleMovieIds = repository.findEligibleMovieIds(userId, batch.items());
        List<RecommendationCandidate> taste =
                rank(batch, userId, eligibleMovieIds, excludedMovieIds, "TASTE");
        List<RecommendationCandidate> discovery =
                rank(batch, userId, eligibleMovieIds, excludedMovieIds, "DISCOVERY");
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
            Set<Long> excludedMovieIds,
            String type) {
        List<RecommendationCandidate> ranked =
                new ArrayList<>(
                        batch.items().stream()
                                .filter(candidate -> candidate.type().equals(type))
                                .filter(candidate -> eligibleMovieIds.contains(candidate.movieId()))
                                .filter(
                                        candidate ->
                                                !excludedMovieIds.contains(candidate.movieId()))
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
