package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.PartyMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyMemberRepository extends JpaRepository<PartyMember, Long> {
    Optional<PartyMember> findByPartyIdAndUserId(long partyId, long userId);

    long countByPartyId(long partyId);
}
