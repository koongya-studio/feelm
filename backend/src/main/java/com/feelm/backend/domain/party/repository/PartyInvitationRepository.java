package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.PartyInvitation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyInvitationRepository extends JpaRepository<PartyInvitation, Long> {
    List<PartyInvitation> findAllByPartyIdAndStatus(long partyId, PartyInvitation.Status status);
}
