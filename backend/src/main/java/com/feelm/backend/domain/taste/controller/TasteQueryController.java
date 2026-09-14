package com.feelm.backend.domain.taste.controller;

import com.feelm.backend.domain.taste.dto.response.PopcornBucketResponse;
import com.feelm.backend.domain.taste.dto.response.PopcornFlavorListResponse;
import com.feelm.backend.domain.taste.dto.response.TasteAnalysisResponse;
import com.feelm.backend.domain.taste.service.TasteQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TasteQueryController {

    private final TasteQueryService tasteQueryService;

    public TasteQueryController(TasteQueryService tasteQueryService) {
        this.tasteQueryService = tasteQueryService;
    }

    @GetMapping("/users/me/taste")
    public TasteAnalysisResponse getMyTasteAnalysis(@AuthenticationPrincipal Long userId) {
        return tasteQueryService.getTasteAnalysis(userId);
    }

    @GetMapping("/users/me/popcorn")
    public PopcornBucketResponse getMyPopcornBucket(@AuthenticationPrincipal Long userId) {
        return tasteQueryService.getPopcornBucket(userId);
    }

    @GetMapping("/popcorn-flavors")
    public PopcornFlavorListResponse getPopcornFlavors() {
        return tasteQueryService.getPopcornFlavors();
    }
}
