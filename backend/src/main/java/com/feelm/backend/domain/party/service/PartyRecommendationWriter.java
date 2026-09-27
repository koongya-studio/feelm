package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.dto.response.PartyRecommendationItemResponse;
import com.feelm.backend.domain.party.dto.response.PartyRecommendationResponse;
import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.model.PartyCandidateSnapshot;
import com.feelm.backend.domain.party.model.PartyInputId;
import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository.NewItem;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository.RecommendationSet;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository.StoredItem;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationMovieResponse;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파티 잠금 이후 최신성을 다시 확인하고 추천 세트·아이템을 원자적으로 저장한다.
 *
 * <p>노출 커서는 항목의 {@code exposed_at}이다. 한 요청이 노출한 항목은 같은 시각을 받으므로 "가장 최근 시각과 같은 항목"이 현재 묶음이고, 세트가 파티
 * 단위라 커서는 파티원 사이에 공유된다. 일반 조회는 그 묶음을 그대로 돌려주고, 다시 받기만 미노출 후보로 새 묶음을 만든다. 묶음 조회·노출 기록·{@code
 * hasNext} 계산은 모두 파티 행 잠금 안에서 수행해 동시 다시 받기가 직렬화된다.
 *
 * <p>전제: 격리 수준은 기본 READ COMMITTED다. 잠금을 얻은 뒤의 문장이 직전 트랜잭션의 커밋(노출)을 봐야 다음 묶음을 고를 수 있다. REPEATABLE
 * READ로 올리면 잠금 대기 전에 잡힌 스냅샷이 유지되어 동시 다시 받기가 같은 묶음을 응답한다(계약 테스트 T12가 실패한다).
 */
@Component
@RequiredArgsConstructor
class PartyRecommendationWriter {
    /** 세트당 저장하는 후보 수. Redis 스냅샷(최대 500)에서 이만큼 저장해 다시 받기로 소진한다. */
    private static final int STORED_CANDIDATE_LIMIT = 50;

    private final PartyRepository partyRepository;
    private final PartyMemberRepository memberRepository;
    private final PartyRecommendationRepository recommendationRepository;
    private final Clock clock;
    private final PartyPopcornSnapshots popcornSnapshots;

    @Transactional
    public PartyRecommendationResponse write(
            long userId,
            long partyId,
            List<Long> expectedMemberIds,
            PartyInputSnapshot input,
            PartyCandidateSnapshot candidates,
            int size,
            boolean refresh) {
        Party party =
                partyRepository
                        .findAccessibleByIdForUpdate(partyId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (party.getStatus() != PartyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PARTY_CLOSED);
        }
        List<Long> currentMemberIds = memberRepository.findUserIdsByPartyId(partyId);
        if (!currentMemberIds.equals(expectedMemberIds)) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
        verifyCurrent(partyId, input, candidates);

        RecommendationSet set =
                recommendationRepository
                        .findExistingSet(partyId, candidates.inputId(), candidates.modelVersion())
                        .orElseGet(() -> createSet(partyId, currentMemberIds, candidates));
        List<StoredItem> items =
                serve(partyId, set.id(), currentMemberIds, size, refresh, set.recommendationMode());
        boolean hasNext = recommendationRepository.hasUnexposedEligible(set.id(), currentMemberIds);
        return response(partyId, set, items, hasNext);
    }

    /**
     * 노출 이력이 없는 세트는 첫 묶음을 만들고, 다시 받기는 미노출 유효 후보로 다음 묶음을 만든다. 그 밖의 조회와 후보를 다 쓴 다시 받기는 현재 묶음을 저장된 그대로
     * 돌려준다.
     */
    private List<StoredItem> serve(
            long partyId,
            long setId,
            List<Long> memberIds,
            int size,
            boolean refresh,
            String mode) {
        Optional<OffsetDateTime> bundleExposedAt =
                recommendationRepository.findLatestExposedAt(setId);
        if (refresh || bundleExposedAt.isEmpty()) {
            List<StoredItem> next =
                    recommendationRepository.findUnexposedEligible(setId, memberIds, size);
            if (!next.isEmpty()) {
                Optional<OffsetDateTime> latestParty =
                        recommendationRepository.findLatestPartyExposedAt(partyId);
                Optional<OffsetDateTime> latest =
                        latestParty.isPresent()
                                        && (bundleExposedAt.isEmpty()
                                                || latestParty.get().isAfter(bundleExposedAt.get()))
                                ? latestParty
                                : bundleExposedAt;
                OffsetDateTime exposedAt = nextExposureTime(latest);
                recommendationRepository.expose(partyId, next, exposedAt);
                popcornSnapshots.capture(partyId, setId, exposedAt, memberIds, mode);
                return next;
            }
            if (bundleExposedAt.isEmpty()) {
                // 노출한 적 없는 세트에 유효 후보가 하나도 없다. 새로 만든 세트는 유효 후보만 저장하므로 기존 세트만 해당한다.
                throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
            }
        }
        return recommendationRepository.findCurrentBundle(setId, size);
    }

    /**
     * 새 묶음의 노출 시각. 묶음 구분이 시각 순서에 의존하므로 현재 묶음 시각보다 크지 않으면(고정 Clock, 같은 마이크로초) 1µs를 더한다. PostgreSQL이
     * 마이크로초까지 저장하므로 비교 전에 같은 정밀도로 맞춘다.
     */
    private OffsetDateTime nextExposureTime(Optional<OffsetDateTime> currentBundleExposedAt) {
        OffsetDateTime now = now().truncatedTo(ChronoUnit.MICROS);
        if (currentBundleExposedAt.isPresent() && !now.isAfter(currentBundleExposedAt.get())) {
            return currentBundleExposedAt
                    .get()
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .plus(1, ChronoUnit.MICROS);
        }
        return now;
    }

    private void verifyCurrent(
            long partyId, PartyInputSnapshot input, PartyCandidateSnapshot candidates) {
        String currentInputId = PartyInputId.from(partyId, input);
        if (!input.modelVersion().equals(candidates.modelVersion())
                || !currentInputId.equals(candidates.inputId())) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
    }

    private RecommendationSet createSet(
            long partyId, List<Long> memberIds, PartyCandidateSnapshot snapshot) {
        List<Long> movieIds =
                snapshot.candidates().stream()
                        .map(PartyCandidateSnapshot.Candidate::movieId)
                        .toList();
        Set<Long> eligibleMovieIds =
                recommendationRepository.findEligibleMovieIds(memberIds, movieIds);
        List<PartyCandidateSnapshot.Candidate> eligible =
                snapshot.candidates().stream()
                        .filter(candidate -> eligibleMovieIds.contains(candidate.movieId()))
                        .limit(STORED_CANDIDATE_LIMIT)
                        .toList();
        if (eligible.isEmpty()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }

        OffsetDateTime generatedAt = snapshot.generatedAt().atOffset(ZoneOffset.UTC);
        long setId =
                recommendationRepository.insertSet(
                        partyId, snapshot.inputId(), snapshot.modelVersion(), generatedAt);
        List<NewItem> items = new ArrayList<>(eligible.size());
        for (int index = 0; index < eligible.size(); index++) {
            items.add(new NewItem(eligible.get(index).movieId(), index + 1));
        }
        recommendationRepository.insertItems(setId, items);
        recommendationRepository.saveMode(setId, snapshot.recommendationMode());
        return new RecommendationSet(setId, generatedAt, snapshot.recommendationMode());
    }

    private PartyRecommendationResponse response(
            long partyId, RecommendationSet set, List<StoredItem> items, boolean hasNext) {
        List<Long> movieIds = items.stream().map(StoredItem::movieId).toList();
        Map<Long, List<String>> genres = recommendationRepository.findGenreNames(movieIds);
        Map<Long, List<String>> otts = recommendationRepository.findOttCodes(movieIds);
        List<PartyRecommendationItemResponse> responses =
                items.stream().map(item -> response(item, genres, otts)).toList();
        return new PartyRecommendationResponse(
                set.id(),
                partyId,
                set.generatedAt(),
                responses,
                hasNext,
                recommendationRepository
                        .findLatestExposedAt(set.id())
                        .map(at -> popcornSnapshots.current(partyId, set.id(), at))
                        .orElse(null),
                set.recommendationMode());
    }

    private PartyRecommendationItemResponse response(
            StoredItem item, Map<Long, List<String>> genres, Map<Long, List<String>> otts) {
        RecommendationMovieResponse movie =
                new RecommendationMovieResponse(
                        item.movieId(),
                        item.title(),
                        item.posterUrl(),
                        item.releaseYear(),
                        genres.getOrDefault(item.movieId(), List.of()),
                        item.averageRating(),
                        otts.getOrDefault(item.movieId(), List.of()));
        return new PartyRecommendationItemResponse(item.id(), item.type(), movie);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
