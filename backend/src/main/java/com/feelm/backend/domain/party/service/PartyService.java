package com.feelm.backend.domain.party.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
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
import com.feelm.backend.domain.party.repository.PartyRefreshRequestRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.domain.user.service.AvatarStorage;
import com.feelm.backend.domain.user.service.UserBlockFilter;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartyService {
    private final PartyRepository partyRepository;
    private final PartyRefreshRequestRepository refreshRequestRepository;
    private final NotificationCommandService notificationCommandService;
    private final PartyMemberRepository memberRepository;
    private final PartyInvitationRepository invitationRepository;
    private final PartyQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final UserBlockFilter userBlockFilter;
    private final AvatarStorage avatarStorage;

    /** 파티와 생성자의 OWNER 멤버 정보를 함께 저장하고 생성 결과를 반환한다. */
    @Transactional
    public PartyResponse create(long userId, String name, Integer maxMembers) {
        var owner =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(user -> user.isActive())
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (memberRepository.countActivePartiesByUserId(userId)
                >= Party.MAX_ACTIVE_PARTIES_PER_USER) {
            throw new BusinessException(ErrorCode.ACTIVE_PARTY_LIMIT_REACHED);
        }
        Party party =
                partyRepository.save(
                        Party.create(
                                name, owner, maxMembers == null ? Party.MAX_MEMBERS : maxMembers));
        PartyMember member = memberRepository.save(PartyMember.ownerOf(party));
        refreshRequestRepository.request(party.getId());
        return partyResponse(party, List.of(memberResponse(member)));
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
                                        mapping(this::memberResponse, toList())));
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
                                                avatarUrl(p.getOwner()),
                                                members.getOrDefault(p.getId(), List.of()),
                                                invitations.getOrDefault(p.getId(), List.of())))
                        .toList();
        Party last = page.getLast();
        String nextCursor =
                hasNext ? new PartyCursor(last.getCreatedAt(), last.getId()).encode() : null;
        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    /**
     * 현재 파티원이 사용자를 초대하고 PENDING 초대를 반환한다.
     *
     * <p>초대 대상의 계정 행은 {@link UserRepository#findByIdForUpdate}로 <b>잠근 뒤</b> 활성 여부를
     * 본다(S15P21E106-758). 잠금 없이 읽으면 탈퇴(API-013)와 교차한다 — 탈퇴의 파티 정리({@link PartyWithdrawalService})는
     * 대기 초대 목록을 한 번 읽고 정리하므로, 목록을 읽은 뒤 커밋된 초대는 정리에 걸리지 않고 응답할 주체 없이 정원 한 자리를 영구히 차지한다. 탈퇴
     * 1단계({@code WithdrawnAccountWriter.withdrawAccount})가 같은 행을 잠그므로 두 경우만 남는다. 이 초대가 먼저 잠그면 탈퇴는 이
     * 커밋을 기다린 뒤 진행해 목록 읽기가 이 초대를 포함하고, 탈퇴가 먼저면 여기서 {@code WITHDRAWN}을 읽어 거절한다.
     *
     * <p>잠금 순서는 <b>사용자 → 파티</b>다. 초대 수락({@link #acceptInvitation})이 초대받은 사용자 행을 먼저 잠그고 파티 행을 잠그므로,
     * 여기서 반대로 잡으면 A가 P의 초대를 수락하는 동안 P의 멤버가 A를 다시 초대할 때 교착한다. 그래서 대상 계정 잠금이 파티 잠금보다 앞에 있고, 대상이 없거나
     * 탈퇴했다는 오류는 계약의 판정 순서(파티 → 요청자 → 종료 → 대상)를 지키기 위해 잠금 뒤로 미뤄 던진다. 대상 Entity를 이 잠금보다 먼저 읽으면 영속성
     * 컨텍스트가 낡은 인스턴스를 돌려주므로({@code UserRepository#findByIdForUpdate} 주석) 대상은 여기서 한 번만 읽는다. 요청자와 대상이
     * 같으면 요청자 검사가 먼저 읽은 인스턴스를 다시 받지만, 그 경우는 요청자가 멤버여야 하므로 어차피 {@code ALREADY_MEMBER}다.
     *
     * <p>요청자는 잠그지 않고 상태만 본다({@link #requireActiveRequester}). 탈퇴한 요청자가 만든 초대가 남지 않는 것은 이 검사가 아니라 파티
     * 행 잠금과 탈퇴 정리가 보장한다 — 정리는 파티 행을 잠그고 멤버십을 지운 뒤 탈퇴자가 <b>보낸</b> 대기 초대까지 {@code DECLINED}로 처리하므로,
     * 초대가 그 나가기보다 먼저 커밋되면 목록에 걸리고 뒤면 멤버십이 없어 {@code FORBIDDEN}이다.
     */
    @Transactional
    public PartyInvitationResponse invite(long requesterId, long partyId, long inviteeId) {
        requireActiveRequester(requesterId);
        Optional<User> invitee = userRepository.findByIdForUpdate(inviteeId).filter(User::isActive);
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
        User target =
                invitee.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
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
        if (reserved >= party.getMaxMembers()) {
            throw new BusinessException(ErrorCode.PARTY_FULL);
        }
        PartyInvitation invitation =
                invitationRepository.saveAndFlush(
                        PartyInvitation.create(party, requesterId, target));
        notificationCommandService.createPartyInvitation(
                inviteeId, invitation.getId(), invitation.getCreatedAt());
        return PartyInvitationResponse.from(invitation);
    }

    /**
     * 요청자가 활성 계정인지 본다. 탈퇴한 계정의 Access Token은 잔여 수명 안이라도 {@code 401 UNAUTHORIZED}로 거절한다는 사용자 도메인
     * 규칙(API-012·013)을 따른다 — Access Token은 계정 상태를 담지 않는다. 파티 존재보다 먼저 판정해 탈퇴한 토큰에 파티 존재를 알리지 않는다.
     *
     * <p>잠그지 않는다. 정상 경로에서 이 검사는 잔여 토큰의 조기 거절이고, 유령 초대를 막는 역할은 {@link #invite} 주석의 파티 잠금과 탈퇴 정리가
     * 맡는다. 다만 탈퇴 4단계의 파티 정리가 실패해 멤버십이 남은 계정(API-013이 수동 복구로 둔 상태)에서는 정리가 다시 돌지 않으므로, 그 잔여 토큰이 새 초대를
     * 만드는 경로를 막는 유일한 장치가 이 검사다(리뷰 B-6). 요청자와 대상이 다르면 요청자 행은 이 트랜잭션에서 다시 잠가 읽지 않으므로 잠금 없는 Entity 읽기가
     * 낡은 인스턴스 문제를 만들지 않는다.
     */
    private void requireActiveRequester(long requesterId) {
        if (queryRepository.findUser(requesterId).filter(User::isActive).isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
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
        if (memberRepository.countByPartyId(partyId) >= party.getMaxMembers()) {
            throw new BusinessException(ErrorCode.PARTY_FULL);
        }

        Instant respondedAt = Instant.now();
        memberRepository.saveAndFlush(PartyMember.memberOf(party, invitee, respondedAt));
        invitation.accept(respondedAt);
        notificationCommandService.closePartyInvitation(userId, invitationId, respondedAt);
        notificationCommandService.createPartyInvitationAccepted(
                party.getOwner().getId(),
                invitationId,
                partyId,
                invitee.getNickname(),
                respondedAt);
        refreshRequestRepository.request(partyId);
        List<Member> members =
                queryRepository.findMembers(List.of(partyId)).stream()
                        .map(this::memberResponse)
                        .toList();
        return partyResponse(party, members);
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
        Instant respondedAt = Instant.now();
        invitation.decline(respondedAt);
        notificationCommandService.closePartyInvitation(userId, invitationId, respondedAt);
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

    /**
     * 현재 파티에서 나가고, 필요하면 오너를 승계하거나 마지막 멤버가 나간 파티를 종료한다.
     *
     * <p>판정은 <b>파티 상태와 멤버십</b>만 본다. 요청자의 계정 상태는 보지 않으며, 이는 회원 탈퇴(API-013)가 이 경로를 재사용하기 위한 전제다 — 탈퇴
     * 정리는 계정이 이미 {@code WITHDRAWN}으로 커밋된 뒤에 돌기 때문이다({@link PartyWithdrawalService}). 여기에 계정 상태 검사를
     * 더하려면 그 경로를 함께 고쳐야 한다.
     */
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
            refreshRequestRepository.delete(partyId);
        } else if (ownerLeaves) {
            PartyMember successor =
                    memberRepository
                            .findFirstByPartyIdOrderByJoinedAtAscUserIdAsc(partyId)
                            .orElseThrow(
                                    () -> new IllegalStateException("Party successor is missing"));
            successor.promoteToOwner();
            party.transferOwnership(successor.getUser());
        }
        if (party.getStatus() == PartyStatus.ACTIVE) {
            refreshRequestRepository.request(partyId);
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
        refreshRequestRepository.delete(partyId);
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
        refreshRequestRepository.request(partyId);

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
                .forEach(
                        invitation -> {
                            invitation.decline(respondedAt);
                            notificationCommandService.closePartyInvitation(
                                    invitation.getInvitee().getId(),
                                    invitation.getId(),
                                    respondedAt);
                        });
    }

    private PartyResponse partyResponse(Party party, List<Member> members) {
        return PartyResponse.from(party, avatarUrl(party.getOwner()), members);
    }

    private Member memberResponse(PartyMember member) {
        return Member.from(member, avatarUrl(member.getUser()));
    }

    private String avatarUrl(User user) {
        return avatarStorage.publicUrl(user.getAvatarStorageKey());
    }
}
