package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.Party;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyRepository extends JpaRepository<Party, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Party p where p.id = :partyId")
    Optional<Party> findByIdForUpdate(@Param("partyId") long partyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select p from Party p join fetch p.owner"
                    + " where p.id = :partyId"
                    + " and exists (select m.id from PartyMember m"
                    + " where m.party = p and m.user.id = :requesterId)")
    Optional<Party> findAccessibleByIdForUpdate(
            @Param("partyId") long partyId, @Param("requesterId") long requesterId);
}
