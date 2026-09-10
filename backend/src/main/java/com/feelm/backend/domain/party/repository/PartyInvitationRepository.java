package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.PartyInvitation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyInvitationRepository extends JpaRepository<PartyInvitation, Long> {
    List<PartyInvitation> findAllByPartyIdAndStatus(long partyId, PartyInvitation.Status status);

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
