package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationResponse;
import com.feelm.backend.domain.recommendation.service.RecommendationServingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
}
