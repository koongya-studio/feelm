package com.feelm.backend.domain.party.dto.response;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationMovieResponse;

public record PartyRecommendationItemResponse(
        long recommendationItemId, String type, RecommendationMovieResponse movie) {}
