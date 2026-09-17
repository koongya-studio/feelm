package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.request.RecommendationReplenishRequest;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationReplenishResponse;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationResponse;
import com.feelm.backend.domain.recommendation.service.RecommendationServingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationServingService service;

    @GetMapping
    public RecommendationResponse getRecommendations(@AuthenticationPrincipal Long userId) {
        return service.getRecommendations(userId);
    }

    @PostMapping("/replenish")
    public RecommendationReplenishResponse replenish(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody RecommendationReplenishRequest request) {
        return service.replenish(
                userId, request.recommendationSetId(), request.recommendationItemId());
    }
}
