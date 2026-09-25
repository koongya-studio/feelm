package com.feelm.backend.domain.feedback.dto;

import java.time.OffsetDateTime;

public record FeedbackResponse(long id, OffsetDateTime createdAt) {}
