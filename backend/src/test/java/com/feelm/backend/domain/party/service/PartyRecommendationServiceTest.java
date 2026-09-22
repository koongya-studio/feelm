package com.feelm.backend.domain.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.model.PartyCandidateSnapshot;
import com.feelm.backend.domain.party.model.PartyInputId;
import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository.RecommendationSet;
import com.feelm.backend.domain.party.repository.PartyRecommendationRepository.StoredItem;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.redis.RedisPartyCandidateReader;
import com.feelm.backend.infrastructure.redis.RedisPartyInputReader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PartyRecommendationServiceTest {
    private static final long PARTY_ID = 9001;
    private static final OffsetDateTime GENERATED_AT = OffsetDateTime.parse("2026-09-17T05:21:00Z");
    private static final OffsetDateTime CLOCK_NOW = OffsetDateTime.parse("2026-09-18T00:00:00Z");

    private final PartyRepository parties = mock(PartyRepository.class);
    private final PartyMemberRepository members = mock(PartyMemberRepository.class);
    private final PartyRecommendationRepository recommendations =
            mock(PartyRecommendationRepository.class);
    private final RedisPartyInputReader inputReader = mock(RedisPartyInputReader.class);
    private final RedisPartyCandidateReader candidateReader = mock(RedisPartyCandidateReader.class);
    private final PartyRecommendationWriter writer =
            new PartyRecommendationWriter(
                    parties,
                    members,
                    recommendations,
                    Clock.fixed(CLOCK_NOW.toInstant(), ZoneOffset.UTC));
    private final PartyRecommendationService service =
            new PartyRecommendationService(parties, members, inputReader, candidateReader, writer);

    @Test
    void reusesCurrentSetAndMapsMovie() {
        PartyInputSnapshot input = accessible();
        String inputId = PartyInputId.from(PARTY_ID, input);
        when(candidateReader.read(PARTY_ID)).thenReturn(snapshot("v1", inputId));
        when(recommendations.findExistingSet(PARTY_ID, inputId, "v1"))
                .thenReturn(Optional.of(new RecommendationSet(71, GENERATED_AT)));
        when(recommendations.findLatestExposedAt(71)).thenReturn(Optional.of(GENERATED_AT));
        when(recommendations.findCurrentBundle(71, 3)).thenReturn(List.of(storedItem()));
        when(recommendations.hasUnexposedEligible(71, List.of(10L, 20L))).thenReturn(true);
        when(recommendations.findGenreNames(List.of(101L)))
                .thenReturn(Map.of(101L, List.of("드라마")));
        when(recommendations.findOttCodes(List.of(101L)))
                .thenReturn(Map.of(101L, List.of("NETFLIX")));

        var response = service.getRecommendations(10, PARTY_ID, 3, false);

        assertThat(response.recommendationSetId()).isEqualTo(71);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.items().getFirst().movie().movieId()).isEqualTo(101);
        verify(recommendations, never())
                .insertSet(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsModelAndInputMismatchWithoutSaving() {
        PartyInputSnapshot input = accessible();
        when(candidateReader.read(PARTY_ID)).thenReturn(snapshot("old", "sha256:old"));

        assertThatThrownBy(() -> service.getRecommendations(10, PARTY_ID, 3, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exception ->
                                assertThat(((BusinessException) exception).getErrorCode())
                                        .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        verify(recommendations, never()).findEligibleMovieIds(anyList(), anyList());
    }

    @Test
    void rejectsWhenEveryCandidateIsExcludedWithoutLeavingASet() {
        PartyInputSnapshot input = accessible();
        String inputId = PartyInputId.from(PARTY_ID, input);
        when(candidateReader.read(PARTY_ID)).thenReturn(snapshot("v1", inputId));
        when(recommendations.findExistingSet(PARTY_ID, inputId, "v1")).thenReturn(Optional.empty());
        when(recommendations.findEligibleMovieIds(List.of(10L, 20L), List.of(101L)))
                .thenReturn(Set.of());

        assertThatThrownBy(() -> service.getRecommendations(10, PARTY_ID, 3, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        exception ->
                                assertThat(((BusinessException) exception).getErrorCode())
                                        .isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE));
        verify(recommendations, never())
                .insertSet(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshBumpsExposureTimeByOneMicroWhenClockTiesCurrentBundle() {
        PartyInputSnapshot input = accessible();
        String inputId = PartyInputId.from(PARTY_ID, input);
        when(candidateReader.read(PARTY_ID)).thenReturn(snapshot("v1", inputId));
        when(recommendations.findExistingSet(PARTY_ID, inputId, "v1"))
                .thenReturn(Optional.of(new RecommendationSet(71, GENERATED_AT)));
        // 현재 묶음 시각이 고정 Clock과 같은 순간이고 offset만 다르다(DB 드라이버가 돌려주는 offset은 보장되지 않는다).
        OffsetDateTime sameInstantSeoul = CLOCK_NOW.withOffsetSameInstant(ZoneOffset.ofHours(9));
        when(recommendations.findLatestExposedAt(71)).thenReturn(Optional.of(sameInstantSeoul));
        when(recommendations.findUnexposedEligible(71, List.of(10L, 20L), 3))
                .thenReturn(List.of(storedItem()));

        service.getRecommendations(10, PARTY_ID, 3, true);

        verify(recommendations).expose(List.of(storedItem()), CLOCK_NOW.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void refreshUsesClockTimeWhenCurrentBundleIsOlder() {
        PartyInputSnapshot input = accessible();
        String inputId = PartyInputId.from(PARTY_ID, input);
        when(candidateReader.read(PARTY_ID)).thenReturn(snapshot("v1", inputId));
        when(recommendations.findExistingSet(PARTY_ID, inputId, "v1"))
                .thenReturn(Optional.of(new RecommendationSet(71, GENERATED_AT)));
        when(recommendations.findLatestExposedAt(71))
                .thenReturn(Optional.of(CLOCK_NOW.minusSeconds(1)));
        when(recommendations.findUnexposedEligible(71, List.of(10L, 20L), 3))
                .thenReturn(List.of(storedItem()));

        service.getRecommendations(10, PARTY_ID, 3, true);

        verify(recommendations).expose(List.of(storedItem()), CLOCK_NOW);
    }

    private PartyInputSnapshot accessible() {
        Party party = mock(Party.class);
        when(party.getStatus()).thenReturn(PartyStatus.ACTIVE);
        when(parties.findAccessibleById(PARTY_ID, 10)).thenReturn(Optional.of(party));
        when(parties.findAccessibleByIdForUpdate(PARTY_ID, 10)).thenReturn(Optional.of(party));
        when(members.findUserIdsByPartyId(PARTY_ID)).thenReturn(List.of(10L, 20L));
        PartyInputSnapshot input =
                new PartyInputSnapshot(
                        "v1",
                        List.of(
                                new PartyInputSnapshot.Member(10, "g1", List.of(), List.of(101L)),
                                new PartyInputSnapshot.Member(20, "g2", List.of(), List.of(101L))));
        when(inputReader.read(List.of(10L, 20L))).thenReturn(input);
        return input;
    }

    private PartyCandidateSnapshot snapshot(String model, String inputId) {
        return new PartyCandidateSnapshot(
                PARTY_ID,
                model,
                "party-mean-fit-v2",
                inputId,
                GENERATED_AT.toInstant(),
                List.of(new PartyCandidateSnapshot.Candidate(101)));
    }

    private StoredItem storedItem() {
        return new StoredItem(1, 101, "PARTY", "movie", null, 2020, new BigDecimal("4.2"));
    }
}
