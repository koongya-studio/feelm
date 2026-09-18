package com.feelm.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class NotificationRetentionTest extends SharedPostgresContractTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRetentionCleaner cleaner;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (10,'retention@example.test','retention',DATE '2000-01-01',"
                        + " 'OTHER','ACTIVE')");
    }

    @Test
    void deletesOnlyExpiredNotifications() {
        Instant now = Instant.now();
        long expired = notification(1, now.minusSeconds(1_200), now.minusSeconds(600));
        long active = notification(2, now.minusSeconds(600), now.plusSeconds(600));

        cleaner.deleteExpired();

        assertThat(count(expired)).isZero();
        assertThat(count(active)).isEqualTo(1);
    }

    private long notification(long sourceId, Instant notifiedAt, Instant expiresAt) {
        return jdbc.queryForObject(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES"
                        + " (10,'PARTY_INVITE',?,'PARTY_INVITATION',?,'제목','본문',?,?) RETURNING id",
                Long.class,
                sourceId,
                sourceId,
                Timestamp.from(notifiedAt),
                Timestamp.from(expiresAt));
    }

    private long count(long notificationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE id=?", Long.class, notificationId);
    }
}
