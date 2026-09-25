package com.feelm.backend.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.feedback.dto.FeedbackRequest.Satisfaction;
import org.junit.jupiter.api.Test;

class FeedbackMattermostNotifierTest {

    @Test
    void removesContactDetailsAndMentionsFromChannelMessage() {
        String message =
                FeedbackMattermostNotifier.message(
                        new FeedbackSubmitted(
                                17,
                                "GENERAL",
                                Satisfaction.BAD,
                                "help@example.com, 010-1234-5678 @channel ![포스터](https://evil.example/x) *문제*",
                                "/app/search",
                                null,
                                null));
        assertThat(message).contains("[사용자 피드백] #17", "/app/search", "이메일 생략", "전화번호 생략");
        assertThat(message)
                .contains("링크 생략")
                .doesNotContain(
                        "help@example.com",
                        "010-1234-5678",
                        "@channel",
                        "https://evil.example",
                        "*문제*");
    }
}
