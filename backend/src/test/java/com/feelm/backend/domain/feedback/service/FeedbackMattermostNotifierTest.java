package com.feelm.backend.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.feedback.dto.FeedbackRequest.Satisfaction;
import org.junit.jupiter.api.Test;

class FeedbackMattermostNotifierTest {

    @Test
    void usesGenericLabelForUnspecifiedRecommendationType() {
        for (String type : new String[] {null, "OTHER"}) {
            String message =
                    FeedbackMattermostNotifier.message(
                            new FeedbackSubmitted(
                                    20,
                                    "RECOMMENDATION",
                                    null,
                                    "의견",
                                    "/app/recommendations",
                                    null,
                                    type));
            assertThat(message)
                    .contains("📩 접수 · 추천 의견 · #20", "- 만족도: 미선택")
                    .doesNotContain("오늘의 추천", "파티 추천");
        }
    }

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
        assertThat(message)
                .startsWith("## 💬 [FEEDBACK] 사용자 의견\n> 📩 접수 · 서비스 의견 · #17")
                .contains("**🔎 의견 정보**", "- 만족도: 아쉬워요", "`/app/search`", "이메일 생략", "전화번호 생략")
                .doesNotContain("- 영화 ID:", "- 추천 유형:");
        assertThat(message)
                .contains("링크 생략")
                .doesNotContain(
                        "help@example.com",
                        "010-1234-5678",
                        "@channel",
                        "https://evil.example",
                        "*문제*");
    }

    @Test
    void distinguishesTodayAndPartyRecommendations() {
        String today =
                FeedbackMattermostNotifier.message(
                        new FeedbackSubmitted(
                                18,
                                "RECOMMENDATION",
                                Satisfaction.GOOD,
                                null,
                                "/app/recommendations",
                                null,
                                "TODAY"));
        String party =
                FeedbackMattermostNotifier.message(
                        new FeedbackSubmitted(
                                19,
                                "RECOMMENDATION",
                                Satisfaction.MIXED,
                                "조금 애매해요",
                                "/app/party/9001/recommendations",
                                42L,
                                "PARTY"));

        assertThat(today)
                .contains("📩 접수 · 오늘의 추천 · #18", "- 만족도: 마음에 들어요", "- 의견: 없음")
                .doesNotContain("- 영화 ID:");
        assertThat(party)
                .contains("📩 접수 · 파티 추천 · #19", "- 만족도: 애매해요", "- 영화 ID: 42", "- 의견: 조금 애매해요");
    }
}
