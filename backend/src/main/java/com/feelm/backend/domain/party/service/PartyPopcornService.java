package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse;
import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse.MemberPopcorn;
import com.feelm.backend.domain.party.repository.PartyPopcornQueryRepository;
import com.feelm.backend.domain.party.repository.PartyPopcornQueryRepository.MemberSummary;
import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import com.feelm.backend.domain.taste.service.TasteQueryService;
import com.feelm.backend.domain.user.service.AvatarStorage;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartyPopcornService {
    private final PartyPopcornQueryRepository partyPopcornQueryRepository;
    private final TasteQueryService tasteQueryService;
    private final AvatarStorage avatarStorage;

    public PartyPopcornResponse getPartyPopcorn(long partyId, long requesterId) {
        List<MemberSummary> members =
                partyPopcornQueryRepository.findAccessibleActivePartyMembers(partyId, requesterId);
        if (members.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<Long, PopcornBucketResponse> buckets =
                tasteQueryService.getPopcornBuckets(
                        members.stream().map(MemberSummary::userId).toList());
        return new PartyPopcornResponse(
                partyId,
                members.stream()
                        .map(
                                member ->
                                        MemberPopcorn.from(
                                                member.userId(),
                                                member.nickname(),
                                                avatarStorage.publicUrl(member.avatarStorageKey()),
                                                buckets.get(member.userId())))
                        .toList());
    }
}
