package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationResponse;
import com.feelm.backend.domain.recommendation.service.RecommendationServingService;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationServingService service;

    @GetMapping
    public RecommendationResponse getRecommendations(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) String size) {
        return service.getRecommendations(userId, parseSizeOrDefault(size));
    }

    private int parseSizeOrDefault(String size) {
        if (size == null) {
            return 3;
        }
        try {
            return Integer.parseInt(size);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
