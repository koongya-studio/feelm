package com.feelm.backend.domain.taste.repository;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TasteAnalysisRepository extends JpaRepository<TasteAnalysis, Long> {

    Optional<TasteAnalysis> findByUserId(long userId);
}
