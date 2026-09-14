package com.feelm.backend.domain.onboarding.controller;

import com.feelm.backend.domain.onboarding.dto.request.OnboardingCardCriteria;
import com.feelm.backend.domain.onboarding.dto.request.OnboardingSubmitRequest;
import com.feelm.backend.domain.onboarding.dto.response.OnboardingCardListResponse;
import com.feelm.backend.domain.onboarding.dto.response.OnboardingSubmitResponse;
import com.feelm.backend.domain.onboarding.service.OnboardingCardService;
import com.feelm.backend.domain.onboarding.service.OnboardingSubmitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 온보딩 초기 취향 수집(API-023·024). 구독 OTT 설정(API-026·027)은 온보딩 밖에서도 쓰이므로 {@code ott} 도메인에 있다. */
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingCardService onboardingCardService;
    private final OnboardingSubmitService onboardingSubmitService;

    /** API-023 초기 취향 카드 목록. */
    @GetMapping("/cards")
    OnboardingCardListResponse getCards(
            @AuthenticationPrincipal Long userId,
            @Valid @ModelAttribute OnboardingCardCriteria criteria) {
        return onboardingCardService.getCards(userId, criteria);
    }

    /** API-024 초기 취향 제출. */
    @PostMapping("/preferences")
    OnboardingSubmitResponse submit(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid OnboardingSubmitRequest request) {
        return onboardingSubmitService.submit(userId, request);
    }
}
