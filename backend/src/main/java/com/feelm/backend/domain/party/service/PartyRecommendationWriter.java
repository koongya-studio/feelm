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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 파티 잠금 이후 최신성을 다시 확인하고 추천 세트·아이템을 원자적으로 저장한다. */
@Component
@RequiredArgsConstructor
class PartyRecommendationWriter {
    private static final int STORED_CANDIDATE_LIMIT = 10;

    private final PartyRepository partyRepository;
    private final PartyMemberRepository memberRepository;
    private final PartyRecommendationRepository recommendationRepository;
    private final Clock clock;

    @Transactional
    public PartyRecommendationResponse write(
            long userId,
            long partyId,
            List<Long> expectedMemberIds,
            PartyInputSnapshot input,
            PartyCandidateSnapshot candidates,
            int size) {
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
                recommendationRepository.findItems(set.id(), currentMemberIds, size);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
        recommendationRepository.expose(items, now());
        return response(partyId, set, items);
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
        return new RecommendationSet(setId, generatedAt);
    }

    private PartyRecommendationResponse response(
            long partyId, RecommendationSet set, List<StoredItem> items) {
        List<Long> movieIds = items.stream().map(StoredItem::movieId).toList();
        Map<Long, List<String>> genres = recommendationRepository.findGenreNames(movieIds);
        Map<Long, List<String>> otts = recommendationRepository.findOttCodes(movieIds);
        List<PartyRecommendationItemResponse> responses =
                items.stream().map(item -> response(item, genres, otts)).toList();
        return new PartyRecommendationResponse(set.id(), partyId, set.generatedAt(), responses);
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
