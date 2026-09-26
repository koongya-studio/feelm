package com.feelm.backend.domain.feedback.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class FeedbackMattermostNotifier {

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${MATTERMOST_BASE_URL:}")
    private String baseUrl;

    @Value("${MATTERMOST_LOGIN_ID:}")
    private String loginId;

    @Value("${MATTERMOST_PASSWORD:}")
    private String password;

    @Value("${MATTERMOST_CHANNEL_ID:}")
    private String channelId;

    @Value("${MATTERMOST_TEAM_NAME:}")
    private String teamName;

    @Value("${MATTERMOST_CHANNEL_NAME:}")
    private String channelName;

    private boolean warnedMissingConfig;

    public FeedbackMattermostNotifier(ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 10, fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void notifyPending() {
        if (baseUrl.isBlank() || loginId.isBlank() || password.isBlank()) {
            if (!warnedMissingConfig) {
                log.warn("Feedback Mattermost notification disabled: configuration missing");
                warnedMissingConfig = true;
            }
            return;
        }
        List<FeedbackSubmitted> pending =
                jdbc.query(
                        """
                        SELECT id, category, satisfaction, comment, screen_path, movie_id, recommendation_type
                        FROM user_feedback WHERE notification_sent_at IS NULL ORDER BY id LIMIT 20
                        """,
                        (rs, row) ->
                                new FeedbackSubmitted(
                                        rs.getLong("id"),
                                        rs.getString("category"),
                                        rs.getString("satisfaction") == null
                                                ? null
                                                : com.feelm.backend.domain.feedback.dto
                                                        .FeedbackRequest.Satisfaction.valueOf(
                                                        rs.getString("satisfaction")),
                                        rs.getString("comment"),
                                        rs.getString("screen_path"),
                                        rs.getObject("movie_id", Long.class),
                                        rs.getString("recommendation_type")));
        for (FeedbackSubmitted feedback : pending) {
            if (deliver(feedback)) {
                jdbc.update(
                        "UPDATE user_feedback SET notification_sent_at = now() WHERE id = ? AND notification_sent_at IS NULL",
                        feedback.id());
            }
        }
    }

    private boolean deliver(FeedbackSubmitted feedback) {
        String token = null;
        try {
            HttpResponse<String> login =
                    send(
                            "POST",
                            "/api/v4/users/login",
                            null,
                            mapper.writeValueAsString(
                                    Map.of("login_id", loginId, "password", password)));
            if (login.statusCode() != 200) throw new IllegalStateException("login failed");
            token = login.headers().firstValue("Token").orElseThrow();
            String target = channelId;
            if (target.isBlank()) {
                if (teamName.isBlank() || channelName.isBlank())
                    throw new IllegalStateException("channel missing");
                JsonNode team =
                        mapper.readTree(
                                requireOk(
                                        send(
                                                "GET",
                                                "/api/v4/teams/name/" + teamName,
                                                token,
                                                null)));
                JsonNode channel =
                        mapper.readTree(
                                requireOk(
                                        send(
                                                "GET",
                                                "/api/v4/teams/"
                                                        + team.path("id").asText()
                                                        + "/channels/name/"
                                                        + channelName,
                                                token,
                                                null)));
                target = channel.path("id").asText();
            }
            String message = message(feedback);
            requireOk(
                    send(
                            "POST",
                            "/api/v4/posts",
                            token,
                            mapper.writeValueAsString(
                                    Map.of("channel_id", target, "message", message))));
            return true;
        } catch (Exception error) {
            log.warn(
                    "Feedback {} saved but Mattermost notification failed: {}",
                    feedback.id(),
                    error.getClass().getSimpleName());
            return false;
        } finally {
            if (token != null) {
                try {
                    send("POST", "/api/v4/users/logout", token, "");
                } catch (Exception ignored) {
                    /* temporary session expires independently */
                }
            }
        }
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(4));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String requireOk(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Mattermost request failed");
        return response.body();
    }

    static String message(FeedbackSubmitted item) {
        String summary = item.comment() == null ? "" : item.comment().replaceAll("[\\r\\n]+", " ");
        summary = summary.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[이메일 생략]");
        summary =
                summary.replaceAll(
                        "(?<![0-9])01[0-9][- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9])", "[전화번호 생략]");
        summary = summary.replaceAll("https?://[^\\s)]+", "[링크 생략]");
        summary = escapeMarkdown(summary);
        String subject =
                switch (item.category()) {
                    case "RECOMMENDATION" ->
                            "PARTY".equals(item.recommendationType())
                                    ? "파티 추천"
                                    : "TODAY".equals(item.recommendationType())
                                            ? "오늘의 추천"
                                            : "추천 의견";
                    default -> "서비스 의견";
                };
        String satisfaction =
                item.satisfaction() == null
                        ? "미선택"
                        : switch (item.satisfaction()) {
                            case GOOD -> "마음에 들어요";
                            case MIXED -> "애매해요";
                            case BAD -> "아쉬워요";
                        };
        StringBuilder message =
                new StringBuilder("## 💬 [FEEDBACK] 사용자 의견\n> 📩 접수 · ")
                        .append(subject)
                        .append(" · #")
                        .append(item.id())
                        .append("\n\n**🔎 의견 정보**\n- 만족도: ")
                        .append(satisfaction)
                        .append("\n- 작성 화면: `")
                        .append(item.screenPath())
                        .append('`');
        if (item.movieId() != null) message.append("\n- 영화 ID: ").append(item.movieId());
        message.append("\n- 의견: ").append(summary.isBlank() ? "없음" : summary);
        return message.toString();
    }

    private static String escapeMarkdown(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '@') character = '＠';
            if (character == '`') character = '\'';
            if (character == '<') character = '‹';
            if (character == '>') character = '›';
            if ("\\*_[]()~#!|".indexOf(character) >= 0) escaped.append('\\');
            escaped.append(character);
        }
        return escaped.toString();
    }
}
