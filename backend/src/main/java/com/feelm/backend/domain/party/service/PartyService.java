package com.feelm.backend.domain.party.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.feelm.backend.domain.party.dto.response.PartyInvitationResponse;
import com.feelm.backend.domain.party.dto.response.PartyInvitationStatusResponse;
import com.feelm.backend.domain.party.dto.response.PartyListItemResponse;
import com.feelm.backend.domain.party.dto.response.PartyListItemResponse.PendingInvitation;
import com.feelm.backend.domain.party.dto.response.PartyResponse;
import com.feelm.backend.domain.party.dto.response.PartyResponse.Member;
import com.feelm.backend.domain.party.dto.response.PartyStateResponse;
import com.feelm.backend.domain.party.dto.response.ReceivedPartyInvitationResponse;
import com.feelm.backend.domain.party.entity.Party;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.entity.PartyMember;
import com.feelm.backend.domain.party.entity.PartyStatus;
import com.feelm.backend.domain.party.repository.PartyInvitationRepository;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.domain.party.repository.PartyQueryRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.domain.user.service.UserBlockFilter;
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
    private final UserRepository userRepository;
    private final UserBlockFilter userBlockFilter;

    /** 파티와 생성자의 OWNER 멤버 정보를 함께 저장하고 생성 결과를 반환한다. */
    @Transactional
    public PartyResponse create(long userId, String name) {
        var owner =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(user -> user.isActive())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (memberRepository.countActivePartiesByUserId(userId)
                >= Party.MAX_ACTIVE_PARTIES_PER_USER) {
            throw new BusinessException(ErrorCode.ACTIVE_PARTY_LIMIT_REACHED);
        }
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

    /** 현재 파티원이 사용자를 초대하고 PENDING 초대를 반환한다. */
    @Transactional
    public PartyInvitationResponse invite(long requesterId, long partyId, long inviteeId) {
        Party party =
                partyRepository
                        .findByIdForUpdate(partyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!memberRepository.existsByPartyIdAndUserId(partyId, requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (party.getStatus() == PartyStatus.CLOSED) {
            throw new BusinessException(ErrorCode.PARTY_CLOSED);
        }
        var invitee =
                queryRepository
                        .findUser(inviteeId)
                        .filter(user -> user.isActive())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (memberRepository.existsByPartyIdAndUserId(partyId, inviteeId)) {
            throw new BusinessException(ErrorCode.ALREADY_MEMBER);
        }
        if (hasBlockBetweenInviteeAndPartyMembers(partyId, inviteeId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (invitationRepository.existsByPartyIdAndInviteeIdAndStatus(
                partyId, inviteeId, PartyInvitation.Status.PENDING)) {
            throw new BusinessException(ErrorCode.INVITATION_PENDING);
        }
        long reserved =
                memberRepository.countByPartyId(partyId)
                        + invitationRepository.countByPartyIdAndStatus(
                                partyId, PartyInvitation.Status.PENDING);
        if (reserved >= Party.MAX_MEMBERS) {
            throw new BusinessException(ErrorCode.PARTY_FULL);
        }
        PartyInvitation invitation =
                invitationRepository.saveAndFlush(
                        PartyInvitation.create(party, requesterId, invitee));
        return PartyInvitationResponse.from(invitation);
    }

    /** 내가 받은 초대를 상태별·최신순으로 페이지 조회한다. */
    public CursorPageResponse<ReceivedPartyInvitationResponse> listInvitations(
            long userId, String requestedStatus, String encodedCursor, Integer requestedSize) {
        int size = requestedSize == null ? 20 : requestedSize;
        if (size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PartyInvitation.Status status;
        try {
            status =
                    requestedStatus == null
                            ? PartyInvitation.Status.PENDING
                            : PartyInvitation.Status.valueOf(requestedStatus);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PartyInvitationCursor cursor = PartyInvitationCursor.decode(encodedCursor);
        List<PartyInvitation> invitations =
                queryRepository.findReceivedInvitations(
                        userId,
                        status,
                        cursor == null ? null : cursor.createdAt(),
                        cursor == null ? 0 : cursor.invitationId(),
                        size + 1);
        boolean hasNext = invitations.size() > size;
        List<PartyInvitation> page = hasNext ? invitations.subList(0, size) : invitations;
        if (page.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }
        var memberCounts =
                queryRepository.countMembers(
                        page.stream().map(invitation -> invitation.getParty().getId()).toList());
        var items =
                page.stream()
                        .map(
                                invitation ->
                                        ReceivedPartyInvitationResponse.from(
                                                invitation,
                                                memberCounts.getOrDefault(
                                                        invitation.getParty().getId(), 0L)))
                        .toList();
        PartyInvitation last = page.getLast();
        String nextCursor =
                hasNext
                        ? new PartyInvitationCursor(last.getCreatedAt(), last.getId()).encode()
                        : null;
        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    /** 받은 대기 초대를 수락하고 파티의 일반 멤버로 참여한다. */
    @Transactional
    public PartyResponse acceptInvitation(long userId, long invitationId) {
        var invitee =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(user -> user.isActive())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        long partyId =
                invitationRepository
                        .findPartyIdByIdAndInviteeId(invitationId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Party party =
                partyRepository
                        .findByIdForUpdate(partyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        PartyInvitation invitation = lockedInvitation(invitationId, userId);

        requirePending(invitation);
        if (party.getStatus() == PartyStatus.CLOSED) {
            throw new BusinessException(ErrorCode.PARTY_CLOSED);
        }
        if (hasBlockBetweenInviteeAndPartyMembers(partyId, userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (memberRepository.countActivePartiesByUserId(userId)
                >= Party.MAX_ACTIVE_PARTIES_PER_USER) {
            throw new BusinessException(ErrorCode.ACTIVE_PARTY_LIMIT_REACHED);
        }
        if (memberRepository.countByPartyId(partyId) >= Party.MAX_MEMBERS) {
            throw new BusinessException(ErrorCode.PARTY_FULL);
        }

        Instant respondedAt = Instant.now();
        memberRepository.saveAndFlush(PartyMember.memberOf(party, invitee, respondedAt));
        invitation.accept(respondedAt);
        List<Member> members =
                queryRepository.findMembers(List.of(partyId)).stream().map(Member::from).toList();
        return PartyResponse.from(party, members);
    }

    /** 받은 대기 초대를 거절하고 응답 상태를 반환한다. */
    @Transactional
    public PartyInvitationStatusResponse declineInvitation(long userId, long invitationId) {
        userRepository
                .findByIdForUpdate(userId)
                .filter(user -> user.isActive())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        PartyInvitation invitation = lockedInvitation(invitationId, userId);
        requirePending(invitation);
        invitation.decline(Instant.now());
        return PartyInvitationStatusResponse.from(invitation);
    }

    private PartyInvitation lockedInvitation(long invitationId, long inviteeId) {
        return invitationRepository
                .findByIdAndInviteeIdForUpdate(invitationId, inviteeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requirePending(PartyInvitation invitation) {
        if (invitation.getStatus() != PartyInvitation.Status.PENDING) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_PENDING);
        }
    }

    private boolean hasBlockBetweenInviteeAndPartyMembers(long partyId, long inviteeId) {
        List<Long> memberIds =
                queryRepository.findMembers(List.of(partyId)).stream()
                        .map(member -> member.getUser().getId())
                        .toList();
        return !userBlockFilter.blockedAmong(inviteeId, memberIds).isEmpty();
    }

    /** 현재 파티에서 나가고, 필요하면 오너를 승계하거나 마지막 멤버가 나간 파티를 종료한다. */
    @Transactional
    public PartyStateResponse leave(long userId, long partyId) {
        Party party = findAccessiblePartyForUpdate(partyId, userId);
        requireActive(party);
        PartyMember leavingMember =
                memberRepository
                        .findByPartyIdAndUserId(partyId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        boolean ownerLeaves = party.getOwner().getId() == userId;

        memberRepository.delete(leavingMember);
        memberRepository.flush();
        if (memberRepository.countByPartyId(partyId) == 0) {
            Instant closedAt = Instant.now();
            party.closeForInsufficientMembers(closedAt);
            declinePendingInvitations(partyId, closedAt);
        } else if (ownerLeaves) {
            PartyMember successor =
                    memberRepository
                            .findFirstByPartyIdOrderByJoinedAtAscUserIdAsc(partyId)
                            .orElseThrow(
                                    () -> new IllegalStateException("Party successor is missing"));
            successor.promoteToOwner();
            party.transferOwnership(successor.getUser());
        }
        return PartyStateResponse.from(party);
    }

    /** 현재 파티장만 활성 파티를 종료하고 대기 중인 초대를 무효화한다. */
    @Transactional
    public PartyStateResponse close(long userId, long partyId) {
        Party party = findAccessiblePartyForUpdate(partyId, userId);
        if (party.getOwner().getId() != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireActive(party);
        Instant closedAt = Instant.now();
        party.closeByOwner(closedAt);
        declinePendingInvitations(partyId, closedAt);
        return PartyStateResponse.from(party);
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

        return PartyStateResponse.from(party);
    }

    private Party findAccessiblePartyForUpdate(long partyId, long userId) {
        Party party =
                partyRepository
                        .findByIdForUpdate(partyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!memberRepository.existsByPartyIdAndUserId(partyId, userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return party;
    }

    private void requireActive(Party party) {
        if (party.getStatus() == PartyStatus.CLOSED) {
            throw new BusinessException(ErrorCode.PARTY_CLOSED);
        }
    }

    private void declinePendingInvitations(long partyId, Instant respondedAt) {
        invitationRepository
                .findAllByPartyIdAndStatus(partyId, PartyInvitation.Status.PENDING)
                .forEach(invitation -> invitation.decline(respondedAt));
    }
}
