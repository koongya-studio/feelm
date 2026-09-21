package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.notification.service.NotificationCommandService;
import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.model.PendingInvitationRef;
import com.feelm.backend.domain.party.repository.PartyInvitationRepository;
import com.feelm.backend.domain.party.repository.PartyRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴한 사용자와 얽힌 대기 초대를 정리한다(API-013).
 *
 * <p>{@link PartyWithdrawalService}에서 분리한 이유는 트랜잭션 경계다. 같은 클래스의 메서드를 직접 부르면 프록시를 거치지 않아
 * {@code @Transactional}이 붙지 않는다.
 *
 * <p>초대 <b>하나가 한 트랜잭션</b>이다. 여러 초대를 한 트랜잭션에 모으면 서로 다른 파티의 행을 동시에 붙잡게 되고, 같은 파티들을 건드리는 다른 요청과 교착할 수
 * 있다(리뷰 B-4).
 */
@Service
@RequiredArgsConstructor
class WithdrawnMemberInvitationWriter {

    private final PartyInvitationRepository invitationRepository;
    private final PartyRepository partyRepository;
    private final NotificationCommandService notificationCommandService;

    /**
     * 탈퇴자가 <b>보낸</b> 대기 초대와 <b>받은</b> 대기 초대를 찾는다. 순서는 질의가 파티 · 초대 식별자로 고정한다.
     *
     * <p>같은 초대가 두 방향에 함께 걸리는 일은 없다. 초대는 초대한 사람과 받은 사람이 서로 달라야 만들어진다({@code
     * PartyInvitation#create}).
     */
    List<PendingInvitationRef> findPendingInvitationsOf(long userId) {
        return invitationRepository.findPendingRefsForUser(userId, PartyInvitation.Status.PENDING);
    }

    /**
     * 초대가 아직 대기 중이면 거절 처리하고 초대 알림을 닫는다.
     *
     * <p>목록을 읽은 시점과 여기 사이에 상대가 그 초대를 수락·거절했을 수 있다. 그래서 <b>파티 행을 잠근 뒤 초대 행을 다시 잠가 읽고</b> 상태를 확인한다.
     * 잠금 없이 고치면 동시에 커밋된 수락(API-054)을 덮어써 멤버는 늘었는데 초대는 {@code DECLINED}인 상태가 된다(리뷰 A-1·B-1). 잠금 순서는
     * 저장소 전체와 같은 <b>파티 → 초대</b>다 — 반대로 잡는 경로가 없어야 교착하지 않는다.
     *
     * <p>파티가 그 사이 사라졌으면 할 일도 없다. 초대는 파티를 참조하므로 파티가 없으면 초대도 없다.
     *
     * <p>받은 초대의 알림은 이미 탈퇴 1단계가 지웠을 수 있다. 닫기는 대상이 없으면 아무 일도 하지 않으므로 순서를 신경 쓰지 않는다.
     */
    @Transactional
    void declineIfStillPending(PendingInvitationRef ref) {
        if (partyRepository.findByIdForUpdate(ref.partyId()).isEmpty()) {
            return;
        }
        invitationRepository
                .findByIdAndInviteeIdForUpdate(ref.invitationId(), ref.inviteeId())
                .filter(invitation -> invitation.getStatus() == PartyInvitation.Status.PENDING)
                .ifPresent(
                        invitation -> {
                            Instant respondedAt = Instant.now();
                            invitation.decline(respondedAt);
                            notificationCommandService.closePartyInvitation(
                                    ref.inviteeId(), ref.invitationId(), respondedAt);
                        });
    }
}
