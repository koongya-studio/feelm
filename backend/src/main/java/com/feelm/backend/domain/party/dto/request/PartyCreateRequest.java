package com.feelm.backend.domain.party.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PartyCreateRequest(
        @NotBlank @Size(max = 20) String name, @Min(2) @Max(4) Integer maxMembers) {}
