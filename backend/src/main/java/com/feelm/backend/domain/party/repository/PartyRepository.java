package com.feelm.backend.domain.party.repository;

import com.feelm.backend.domain.party.entity.Party;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, Long> {}
