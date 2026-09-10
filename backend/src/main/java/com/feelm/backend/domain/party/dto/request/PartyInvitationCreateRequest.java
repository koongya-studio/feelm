package com.feelm.backend.domain.party.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PartyInvitationCreateRequest(@NotNull @Positive Long userId) {}
