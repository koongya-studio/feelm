package com.feelm.backend.domain.feedback.controller;

import com.feelm.backend.domain.feedback.dto.FeedbackRequest;
import com.feelm.backend.domain.feedback.dto.FeedbackResponse;
import com.feelm.backend.domain.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse submit(
            @AuthenticationPrincipal Long userId, @RequestBody @Valid FeedbackRequest request) {
        return feedbackService.submit(userId, request);
    }
}
