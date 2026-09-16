package com.feelm.backend.domain.recommendation.service;

import com.feelm.backend.domain.recommendation.model.RecommendationBufferResult;
import com.feelm.backend.domain.recommendation.model.RecommendationCandidateBatch;
import com.feelm.backend.domain.recommendation.repository.RecommendationCandidateReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Redis의 최신 추천 후보를 읽어 PostgreSQL 추천 버퍼 생성을 요청하는 진입점이다. */
@Service
@RequiredArgsConstructor
public class RecommendationBufferService {

    private final RecommendationCandidateReader candidateReader;
    private final RecommendationBufferWriter writer;

    /** 사용자의 최신 Redis 후보로 추천 버퍼를 생성하거나 동일 버전의 기존 버퍼를 반환한다. */
    public RecommendationBufferResult createBuffer(long userId) {
        RecommendationCandidateBatch candidates = candidateReader.read(userId);
        return writer.write(userId, candidates);
    }
}
