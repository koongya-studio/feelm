package com.feelm.backend.domain.feedback.service;

import com.feelm.backend.domain.feedback.dto.FeedbackRequest.Satisfaction;

public record FeedbackSubmitted(
        long id,
        String category,
        Satisfaction satisfaction,
        String comment,
        String screenPath,
        Long movieId,
        String recommendationType) {}
