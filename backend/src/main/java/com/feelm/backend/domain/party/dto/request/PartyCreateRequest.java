package com.feelm.backend.domain.party.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PartyCreateRequest(@NotBlank @Size(max = 20) String name) {}
