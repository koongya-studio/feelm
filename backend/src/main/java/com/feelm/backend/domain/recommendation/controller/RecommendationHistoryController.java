package com.feelm.backend.domain.recommendation.controller;

import com.feelm.backend.domain.recommendation.dto.response.RecommendationHistorySetResponse;
import com.feelm.backend.domain.recommendation.service.RecommendationHistoryService;
import com.feelm.backend.global.response.CursorPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationHistoryController {

    private final RecommendationHistoryService service;

    @GetMapping("/history")
    public CursorPageResponse<RecommendationHistorySetResponse> getHistory(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return service.getHistory(userId, cursor, size);
    }
}
