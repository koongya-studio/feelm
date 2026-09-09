package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.PartyMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyMemberRepository extends JpaRepository<PartyMember, Long> {}
