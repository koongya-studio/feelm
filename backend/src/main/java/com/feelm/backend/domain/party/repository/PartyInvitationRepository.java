package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.PartyInvitation;
import com.feelm.backend.domain.party.model.PendingInvitationRef;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyInvitationRepository extends JpaRepository<PartyInvitation, Long> {
    List<PartyInvitation> findAllByPartyIdAndStatus(long partyId, PartyInvitation.Status status);

    /**
     * 탈퇴자와 얽힌 대기 초대의 식별자(API-013). 보낸 것과 받은 것을 함께 찾는다 — 초대한 사람이 사라지면 상대가 수락할 대상이 없고, 받는 사람이 사라지면
     * 응답할 주체가 없다.
     *
     * <p>Entity가 아니라 식별자만 돌려주고 정렬을 <b>질의에 고정</b>한다. 정리는 초대마다 파티를 잠근 뒤 행을 다시 읽으므로 여기서 읽은 Entity는 쓸 수
     * 없고, 여러 초대를 순서 없이 잠그면 같은 파티들을 건드리는 다른 요청과 교착할 수 있다.
     */
    @Query(
            "select new com.feelm.backend.domain.party.model.PendingInvitationRef("
                    + "i.party.id, i.id, i.invitee.id) from PartyInvitation i"
                    + " where i.status = :status"
                    + " and (i.inviterId = :userId or i.invitee.id = :userId)"
                    + " order by i.party.id, i.id")
    List<PendingInvitationRef> findPendingRefsForUser(
            @Param("userId") long userId, @Param("status") PartyInvitation.Status status);

    boolean existsByPartyIdAndInviteeIdAndStatus(
            long partyId, long inviteeId, PartyInvitation.Status status);

    long countByPartyIdAndStatus(long partyId, PartyInvitation.Status status);

    @Query(
            "select i.party.id from PartyInvitation i"
                    + " where i.id = :invitationId and i.invitee.id = :inviteeId")
    Optional<Long> findPartyIdByIdAndInviteeId(
            @Param("invitationId") long invitationId, @Param("inviteeId") long inviteeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select i from PartyInvitation i join fetch i.invitee"
                    + " where i.id = :invitationId and i.invitee.id = :inviteeId")
    Optional<PartyInvitation> findByIdAndInviteeIdForUpdate(
            @Param("invitationId") long invitationId, @Param("inviteeId") long inviteeId);
}
