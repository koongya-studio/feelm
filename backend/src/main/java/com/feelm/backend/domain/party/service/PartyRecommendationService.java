package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.dto.response.PartyRecommendationResponse;
import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.model.PartyCandidateSnapshot;
import com.feelm.backend.domain.party.model.PartyInputSnapshot;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.redis.RedisPartyCandidateReader;
import com.feelm.backend.infrastructure.redis.RedisPartyInputReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Redis 조회를 트랜잭션 밖에서 수행하고 검증을 통과한 snapshot의 저장을 writer에 위임한다. */
@Service
@RequiredArgsConstructor
public class PartyRecommendationService {
    private final PartyRepository partyRepository;
    private final PartyMemberRepository memberRepository;
    private final RedisPartyInputReader inputReader;
    private final RedisPartyCandidateReader candidateReader;
    private final PartyRecommendationWriter writer;

    public PartyRecommendationResponse getRecommendations(long userId, long partyId, int size) {
        Party party =
                partyRepository
                        .findAccessibleById(partyId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (party.getStatus() != PartyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PARTY_CLOSED);
        }

        List<Long> memberIds = memberRepository.findUserIdsByPartyId(partyId);
        PartyInputSnapshot currentInput;
        PartyCandidateSnapshot candidateSnapshot;
        try {
            currentInput = inputReader.read(memberIds);
            candidateSnapshot = candidateReader.read(partyId);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE);
        }
        return writer.write(userId, partyId, memberIds, currentInput, candidateSnapshot, size);
    }
}
