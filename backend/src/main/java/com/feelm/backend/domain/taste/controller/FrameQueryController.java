package com.feelm.backend.domain.taste.controller;

import com.feelm.backend.domain.taste.dto.response.FrameDetailResponse;
import com.feelm.backend.domain.taste.dto.response.FrameListResponse;
import com.feelm.backend.domain.taste.service.FrameQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FrameQueryController {

    private final FrameQueryService frameQueryService;

    public FrameQueryController(FrameQueryService frameQueryService) {
        this.frameQueryService = frameQueryService;
    }

    @GetMapping("/users/me/frames")
    public FrameListResponse getMyFrames(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String flavorCode,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return frameQueryService.getMyFrames(userId, flavorCode, cursor, size);
    }

    @GetMapping("/frames/{frameId}")
    public FrameDetailResponse getFrameDetail(
            @AuthenticationPrincipal Long userId, @PathVariable long frameId) {
        return frameQueryService.getFrameDetail(userId, frameId);
    }
}
