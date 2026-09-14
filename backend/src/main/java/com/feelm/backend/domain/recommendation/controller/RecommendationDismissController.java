package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.request.RecommendationDismissRequest;
import com.feelm.backend.domain.recommendation.dto.response.RecommendationDismissResponse;
import com.feelm.backend.domain.recommendation.service.RecommendationDismissService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class RecommendationDismissController {

    private final RecommendationDismissService recommendationDismissService;

    @PostMapping("/{movieId}/dismiss")
    public RecommendationDismissResponse dismiss(
            @AuthenticationPrincipal Long userId,
            @PathVariable long movieId,
            @RequestBody(required = false) RecommendationDismissRequest request) {
        Long recommendationItemId = request == null ? null : request.recommendationItemId();
        return recommendationDismissService.dismiss(userId, movieId, recommendationItemId);
    }
}
