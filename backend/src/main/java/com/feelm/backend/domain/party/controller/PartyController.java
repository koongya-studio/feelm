package com.feelm.backend.domain.party.controller;

import com.feelm.backend.domain.party.dto.request.PartyCreateRequest;
import com.feelm.backend.domain.party.dto.request.PartyInvitationCreateRequest;
import com.feelm.backend.domain.party.dto.response.PartyInvitationResponse;
import com.feelm.backend.domain.party.dto.response.PartyInvitationStatusResponse;
import com.feelm.backend.domain.party.dto.response.PartyListItemResponse;
import com.feelm.backend.domain.party.dto.response.PartyResponse;
import com.feelm.backend.domain.party.dto.response.PartyStateResponse;
import com.feelm.backend.domain.party.dto.response.ReceivedPartyInvitationResponse;
import com.feelm.backend.domain.party.service.PartyService;
import com.feelm.backend.global.response.CursorPageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
@RequiredArgsConstructor
public class PartyController {
    private final PartyService partyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PartyResponse create(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody PartyCreateRequest request) {
        return partyService.create(userId, request.name());
    }

    @GetMapping
    public CursorPageResponse<PartyListItemResponse> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return partyService.list(userId, status, cursor, size);
    }

    @PostMapping("/{partyId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyInvitationResponse invite(
            @AuthenticationPrincipal Long userId,
            @PathVariable long partyId,
            @Valid @RequestBody PartyInvitationCreateRequest request) {
        return partyService.invite(userId, partyId, request.userId());
    }

    @GetMapping("/invitations")
    public CursorPageResponse<ReceivedPartyInvitationResponse> listInvitations(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return partyService.listInvitations(userId, status, cursor, size);
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public PartyResponse acceptInvitation(
            @AuthenticationPrincipal Long userId, @PathVariable long invitationId) {
        return partyService.acceptInvitation(userId, invitationId);
    }

    @PostMapping("/invitations/{invitationId}/decline")
    public PartyInvitationStatusResponse declineInvitation(
            @AuthenticationPrincipal Long userId, @PathVariable long invitationId) {
        return partyService.declineInvitation(userId, invitationId);
    }

    @PostMapping("/{partyId}/leave")
    public PartyStateResponse leave(
            @AuthenticationPrincipal Long userId, @PathVariable long partyId) {
        return partyService.leave(userId, partyId);
    }

    @DeleteMapping("/{partyId}")
    public PartyStateResponse close(
            @AuthenticationPrincipal Long userId, @PathVariable long partyId) {
        return partyService.close(userId, partyId);
    }

    @DeleteMapping("/{partyId}/members/{userId}")
    public PartyStateResponse kick(
            @AuthenticationPrincipal Long requesterId,
            @PathVariable long partyId,
            @PathVariable long userId) {
        return partyService.kick(requesterId, partyId, userId);
    }
}
