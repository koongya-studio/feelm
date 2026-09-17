package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.response.OttRecommendationResponse;
import com.feelm.backend.domain.recommendation.service.OttRecommendationService;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API-068 OTT 구독 추천. */
@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class OttRecommendationController {

    private final OttRecommendationService service;

    @GetMapping("/otts")
    public OttRecommendationResponse getOttRecommendations(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return service.getRecommendations(userId);
    }
}
