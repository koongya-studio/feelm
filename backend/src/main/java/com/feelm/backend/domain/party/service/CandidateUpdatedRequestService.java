package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyRefreshRequestRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandidateUpdatedRequestService {
    private final PartyMemberRepository memberRepository;
    private final PartyRepository partyRepository;
    private final PartyRefreshRequestRepository requestRepository;

    @Transactional
    public void requestCurrentParties(long userId) {
        for (long partyId : memberRepository.findActivePartyIdsByUserId(userId)) {
            partyRepository
                    .findByIdForUpdate(partyId)
                    .filter(party -> party.getStatus() == PartyStatus.ACTIVE)
                    .filter(party -> memberRepository.existsByPartyIdAndUserId(partyId, userId))
                    .ifPresent(party -> requestRepository.request(partyId));
        }
    }
}
