package com.feelm.backend.domain.party.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.feelm.backend.domain.party.dto.response.PartyListItemResponse;
import com.feelm.backend.domain.party.dto.response.PartyListItemResponse.PendingInvitation;
import com.feelm.backend.domain.party.dto.response.PartyResponse;
import com.feelm.backend.domain.party.dto.response.PartyResponse.Member;
import com.feelm.backend.domain.party.dto.response.PartyStateResponse;
import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.entity.PartyMember;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.repository.PartyInvitationRepository;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyQueryRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartyService {
    private final PartyRepository partyRepository;
    private final PartyMemberRepository memberRepository;
    private final PartyInvitationRepository invitationRepository;
    private final PartyQueryRepository queryRepository;

    /** 파티와 생성자의 OWNER 멤버 정보를 함께 저장하고 생성 결과를 반환한다. */
    @Transactional
    public PartyResponse create(long userId, String name) {
        var owner =
                queryRepository
                        .findUser(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Party party = partyRepository.save(Party.create(name, owner));
        PartyMember member = memberRepository.save(PartyMember.ownerOf(party));
        return PartyResponse.from(party, List.of(Member.from(member)));
    }

    /**
     * 내가 속한 파티를 상태별·최신순으로 페이지 조회한다.
     *
     * <p>기본 상태는 ACTIVE, 페이지 크기는 기본 20·최대 50이며 멤버와 대기 초대를 함께 반환한다.
     */
    public CursorPageResponse<PartyListItemResponse> list(
            long userId, String requestedStatus, String encodedCursor, Integer requestedSize) {
        int size = requestedSize == null ? 20 : requestedSize;
        if (size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PartyStatus status;
        try {
            status =
                    requestedStatus == null
                            ? PartyStatus.ACTIVE
                            : PartyStatus.valueOf(requestedStatus);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PartyCursor cursor = PartyCursor.decode(encodedCursor);
        List<Party> parties =
                queryRepository.findParties(
                        userId,
                        status,
                        cursor == null ? null : cursor.createdAt(),
                        cursor == null ? 0 : cursor.partyId(),
                        size + 1);
        boolean hasNext = parties.size() > size;
        List<Party> page = hasNext ? parties.subList(0, size) : parties;
        if (page.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }
        List<Long> ids = page.stream().map(Party::getId).toList();
        var members =
                queryRepository.findMembers(ids).stream()
                        .collect(
                                groupingBy(
                                        m -> m.getParty().getId(),
                                        mapping(Member::from, toList())));
        var invitations =
                queryRepository.findPendingInvitations(ids).stream()
                        .collect(
                                groupingBy(
                                        i -> i.getParty().getId(),
                                        mapping(PendingInvitation::from, toList())));
        var items =
                page.stream()
                        .map(
                                p ->
                                        PartyListItemResponse.from(
                                                p,
                                                members.getOrDefault(p.getId(), List.of()),
                                                invitations.getOrDefault(p.getId(), List.of())))
                        .toList();
        Party last = page.getLast();
        String nextCursor =
                hasNext ? new PartyCursor(last.getCreatedAt(), last.getId()).encode() : null;
        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    /** 현재 파티장만 다른 현재 멤버를 강퇴하고, 남은 인원에 따라 파티를 종료한다. */
    @Transactional
    public PartyStateResponse kick(long requesterId, long partyId, long targetUserId) {
        Party party =
                partyRepository
                        .findAccessibleByIdForUpdate(partyId, requesterId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (party.getOwner().getId() != requesterId) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (party.getStatus() == PartyStatus.CLOSED) {
            throw new BusinessException(ErrorCode.PARTY_CLOSED);
        }
        if (targetUserId == requesterId) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        PartyMember target =
                memberRepository
                        .findByPartyIdAndUserId(partyId, targetUserId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        memberRepository.delete(target);
        memberRepository.flush();

        if (memberRepository.countByPartyId(partyId) == 1) {
            Instant respondedAt = Instant.now();
            party.closeForInsufficientMembers(respondedAt);
            invitationRepository
                    .findAllByPartyIdAndStatus(partyId, PartyInvitation.Status.PENDING)
                    .forEach(invitation -> invitation.decline(respondedAt));
        }
        return PartyStateResponse.from(party);
    }
}
