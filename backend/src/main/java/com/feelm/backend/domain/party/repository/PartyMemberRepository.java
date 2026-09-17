package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.PartyMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyMemberRepository extends JpaRepository<PartyMember, Long> {
    @Query(
            "select m.party.id from PartyMember m where m.user.id = :userId"
                    + " and m.party.status = com.feelm.backend.domain.party.entity.PartyStatus.ACTIVE"
                    + " order by m.party.id")
    List<Long> findActivePartyIdsByUserId(@Param("userId") long userId);

    @Query("select m.user.id from PartyMember m where m.party.id = :partyId order by m.user.id")
    List<Long> findUserIdsByPartyId(@Param("partyId") long partyId);

    Optional<PartyMember> findByPartyIdAndUserId(long partyId, long userId);

    Optional<PartyMember> findFirstByPartyIdOrderByJoinedAtAscUserIdAsc(long partyId);

    boolean existsByPartyIdAndUserId(long partyId, long userId);

    long countByPartyId(long partyId);

    @Query(
            "select count(m) from PartyMember m"
                    + " where m.user.id = :userId"
                    + " and m.party.status = com.feelm.backend.domain.party.entity.PartyStatus.ACTIVE")
    long countActivePartiesByUserId(@Param("userId") long userId);
}
