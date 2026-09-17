package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-070~074: 인앱 알림 조회와 읽음·숨김 동기화 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
class NotificationApiContractTest extends SharedPostgresContractTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;

    private long nextSourceId;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(10, "ACTIVE");
        user(20, "ACTIVE");
        user(30, "WITHDRAWN");
        nextSourceId = 1_000;
    }

    @Test
    void requiresAuthenticationAndActiveAccount() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/notifications/1/read")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/notifications/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(30)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void listsVisibleNotificationsNewestFirstWithStableCursor() throws Exception {
        Instant now = Instant.now();
        long oldest = notification(10, "PARTY_INVITE", now.minusSeconds(30), now.plusSeconds(600));
        Instant tiedAt = now.minusSeconds(10);
        long middle = notification(10, "WATCH_CONFIRM", tiedAt, now.plusSeconds(600));
        long newest = notification(10, "PARTY_INVITE", tiedAt, now.plusSeconds(600));

        JsonNode first =
                body(
                        mvc.perform(
                                        get("/api/v1/notifications")
                                                .header("Authorization", bearer(10))
                                                .queryParam("size", "2"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(2))
                                .andExpect(jsonPath("$.items[0].notificationId").value(newest))
                                .andExpect(jsonPath("$.items[0].type").value("PARTY_INVITE"))
                                .andExpect(
                                        jsonPath("$.items[0].targetType").value("PARTY_INVITATION"))
                                .andExpect(jsonPath("$.items[0].readAt").isEmpty())
                                .andExpect(jsonPath("$.items[1].notificationId").value(middle))
                                .andExpect(jsonPath("$.hasNext").value(true)));

        mvc.perform(
                        get("/api/v1/notifications")
                                .header("Authorization", bearer(10))
                                .queryParam("size", "2")
                                .queryParam("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].notificationId").value(oldest))
                .andExpect(jsonPath("$.nextCursor").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void excludesFutureExpiredClosedAndOtherUsersFromListAndCount() throws Exception {
        Instant now = Instant.now();
        long visible = notification(10, "PARTY_INVITE", now.minusSeconds(10), now.plusSeconds(600));
        notification(10, "WATCH_CONFIRM", now.plusSeconds(600), now.plusSeconds(1_200));
        notification(10, "PARTY_INVITE", now.minusSeconds(1_200), now.minusSeconds(600));
        long closed = notification(10, "WATCH_CONFIRM", now.minusSeconds(10), now.plusSeconds(600));
        jdbc.update("UPDATE notifications SET closed_at=now() WHERE id=?", closed);
        long dismissed =
                notification(10, "PARTY_INVITE", now.minusSeconds(10), now.plusSeconds(600));
        jdbc.update("UPDATE notifications SET dismissed_at=now() WHERE id=?", dismissed);
        notification(20, "PARTY_INVITE", now.minusSeconds(10), now.plusSeconds(600));

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].notificationId").value(visible));
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void marksOwnedNotificationReadIdempotentlyAndHidesForeignRows() throws Exception {
        Instant now = Instant.now();
        long owned = notification(10, "PARTY_INVITE", now.minusSeconds(10), now.plusSeconds(600));
        long foreign =
                notification(20, "WATCH_CONFIRM", now.minusSeconds(10), now.plusSeconds(600));

        mvc.perform(
                        patch("/api/v1/notifications/{notificationId}/read", owned)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNoContent());
        Instant firstRead = readAt(owned);
        mvc.perform(
                        patch("/api/v1/notifications/{notificationId}/read", owned)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNoContent());
        assertThat(readAt(owned)).isEqualTo(firstRead);
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        mvc.perform(
                        patch("/api/v1/notifications/{notificationId}/read", foreign)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(readAt(foreign)).isNull();
    }

    @Test
    void dismissesOwnedNotificationIdempotentlyAndHidesForeignRows() throws Exception {
        Instant now = Instant.now();
        long owned = notification(10, "PARTY_INVITE", now.minusSeconds(10), now.plusSeconds(600));
        long foreign =
                notification(20, "WATCH_CONFIRM", now.minusSeconds(10), now.plusSeconds(600));

        mvc.perform(
                        delete("/api/v1/notifications/{notificationId}", owned)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNoContent());
        Instant firstDismissed = dismissedAt(owned);
        mvc.perform(
                        delete("/api/v1/notifications/{notificationId}", owned)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNoContent());
        assertThat(dismissedAt(owned)).isEqualTo(firstDismissed);

        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        mvc.perform(
                        delete("/api/v1/notifications/{notificationId}", foreign)
                                .header("Authorization", bearer(10)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(dismissedAt(foreign)).isNull();
    }

    @Test
    void marksOnlyCurrentlyVisibleNotificationsReadAll() throws Exception {
        Instant now = Instant.now();
        long first = notification(10, "PARTY_INVITE", now.minusSeconds(20), now.plusSeconds(600));
        long second = notification(10, "WATCH_CONFIRM", now.minusSeconds(10), now.plusSeconds(600));
        long future =
                notification(10, "PARTY_INVITE", now.plusSeconds(600), now.plusSeconds(1_200));
        long foreign =
                notification(20, "WATCH_CONFIRM", now.minusSeconds(10), now.plusSeconds(600));

        mvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", bearer(10)))
                .andExpect(status().isNoContent());

        assertThat(readAt(first)).isNotNull();
        assertThat(readAt(second)).isEqualTo(readAt(first));
        assertThat(readAt(future)).isNull();
        assertThat(readAt(foreign)).isNull();
    }

    @Test
    void rejectsInvalidCursorAndPageSize() throws Exception {
        for (String size : new String[] {"0", "51"}) {
            mvc.perform(
                            get("/api/v1/notifications")
                                    .header("Authorization", bearer(10))
                                    .queryParam("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(
                        get("/api/v1/notifications")
                                .header("Authorization", bearer(10))
                                .queryParam("cursor", "broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private long notification(long userId, String type, Instant notifiedAt, Instant expiresAt) {
        String targetType = type.equals("PARTY_INVITE") ? "PARTY_INVITATION" : "WATCH_RECORD";
        long sourceId = nextSourceId++;
        return jdbc.queryForObject(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES (?,?,?,?,?,'제목','본문',?,?) RETURNING id",
                Long.class,
                userId,
                type,
                sourceId,
                targetType,
                sourceId,
                Timestamp.from(notifiedAt),
                Timestamp.from(expiresAt));
    }

    private Instant readAt(long notificationId) {
        Timestamp value =
                jdbc.queryForObject(
                        "SELECT read_at FROM notifications WHERE id=?",
                        Timestamp.class,
                        notificationId);
        return value == null ? null : value.toInstant();
    }

    private Instant dismissedAt(long notificationId) {
        Timestamp value =
                jdbc.queryForObject(
                        "SELECT dismissed_at FROM notifications WHERE id=?",
                        Timestamp.class,
                        notificationId);
        return value == null ? null : value.toInstant();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id, String status) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status,withdrawn_at)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?,?)",
                id,
                "notification" + id + "@example.test",
                "notification" + id,
                status,
                status.equals("WITHDRAWN") ? Timestamp.from(Instant.now()) : null);
    }
}
