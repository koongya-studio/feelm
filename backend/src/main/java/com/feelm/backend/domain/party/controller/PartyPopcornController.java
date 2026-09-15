package com.feelm.backend.domain.party.controller;

import com.feelm.backend.domain.party.dto.response.PartyPopcornResponse;
import com.feelm.backend.domain.party.service.PartyPopcornService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
@RequiredArgsConstructor
public class PartyPopcornController {
    private final PartyPopcornService partyPopcornService;

    @GetMapping("/{partyId}/popcorn")
    public PartyPopcornResponse getPartyPopcorn(
            @AuthenticationPrincipal Long userId, @PathVariable long partyId) {
        return partyPopcornService.getPartyPopcorn(partyId, userId);
    }
}
