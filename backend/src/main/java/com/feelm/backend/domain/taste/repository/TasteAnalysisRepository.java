package com.feelm.backend.domain.taste.repository;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TasteAnalysisRepository extends JpaRepository<TasteAnalysis, Long> {

    Optional<TasteAnalysis> findByUserId(long userId);

    /** 평가가 하나도 남지 않으면 행을 지운다. 계약이 "평가가 하나도 없으면 행을 만들지 않는다"로 정했다(API-040). */
    void deleteByUserId(long userId);
}
