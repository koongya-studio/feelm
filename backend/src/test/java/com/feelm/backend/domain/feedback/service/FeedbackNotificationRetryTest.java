package com.feelm.backend.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class FeedbackNotificationRetryTest {

    @Test
    void retriesFailedNotificationFromStoredFeedback() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v4/users/login",
                exchange -> {
                    exchange.getResponseHeaders().add("Token", "test-token");
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/v4/posts",
                exchange -> {
                    int status = posts.incrementAndGet() == 1 ? 503 : 201;
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/api/v4/users/logout",
                exchange -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();
        try {
            JdbcTemplate jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    "jdbc:h2:mem:feedback_retry;DB_CLOSE_DELAY=-1", "sa", ""));
            jdbc.execute("DROP TABLE IF EXISTS user_feedback");
            jdbc.execute(
                    """
                    CREATE TABLE user_feedback (
                        id bigint PRIMARY KEY, category varchar(20), satisfaction varchar(20),
                        comment varchar(1000), screen_path varchar(300), movie_id bigint,
                        recommendation_type varchar(30), notification_sent_at timestamp with time zone
                    )
                    """);
            jdbc.update(
                    """
                    INSERT INTO user_feedback(id,category,comment,screen_path)
                    VALUES (17,'GENERAL','의견','/app/search')
                    """);
            FeedbackMattermostNotifier notifier =
                    new FeedbackMattermostNotifier(new ObjectMapper(), jdbc);
            ReflectionTestUtils.setField(
                    notifier, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(notifier, "loginId", "test-user");
            ReflectionTestUtils.setField(notifier, "password", "test-password");
            ReflectionTestUtils.setField(notifier, "channelId", "test-channel");
            ReflectionTestUtils.setField(notifier, "teamName", "");
            ReflectionTestUtils.setField(notifier, "channelName", "");

            notifier.notifyPending();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT notification_sent_at FROM user_feedback WHERE id=17",
                                    Object.class))
                    .isNull();
            notifier.notifyPending();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT notification_sent_at FROM user_feedback WHERE id=17",
                                    Object.class))
                    .isNotNull();
            assertThat(posts).hasValue(2);
        } finally {
            server.stop(0);
        }
    }
}
