package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
class NotificationStreamRepositoryTest extends SharedPostgresContractTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepository repository;

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status) VALUES "
                        + "(10,'sse@test.example','sse',DATE '2000-01-01','OTHER','ACTIVE'),"
                        + "(20,'other@test.example','other',DATE '2000-01-01','OTHER','ACTIVE')");
    }

    @Test
    void scheduledVisibilityExpiryAndUserIsolation() {
        Instant due = Instant.parse("2026-09-26T00:00:00Z");
        String empty = repository.streamVersion(10, due.minusSeconds(1));
        long id = insert(10, 1, due, due.plusSeconds(60));
        insert(20, 2, due.minusSeconds(10), due.plusSeconds(60));
        assertThat(repository.streamVersion(10, due.minusSeconds(1))).isEqualTo(empty);
        String visible = repository.streamVersion(10, due);
        assertThat(visible).isNotEqualTo(empty);
        jdbc.update("UPDATE notifications SET read_at=? WHERE id=?", Timestamp.from(due), id);
        String read = repository.streamVersion(10, due);
        assertThat(read).isNotEqualTo(visible);
        assertThat(repository.streamVersion(10, due.plusSeconds(60))).isEqualTo(empty);
        jdbc.update("UPDATE notifications SET dismissed_at=? WHERE id=?", Timestamp.from(due), id);
        assertThat(repository.streamVersion(10, due)).isEqualTo(empty);
        jdbc.update(
                "UPDATE notifications SET dismissed_at=NULL, closed_at=? WHERE id=?",
                Timestamp.from(due),
                id);
        assertThat(repository.streamVersion(10, due)).isEqualTo(empty);
    }

    private long insert(long userId, long sourceId, Instant due, Instant expires) {
        return jdbc.queryForObject(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,"
                        + "title,body,notified_at,expires_at) VALUES (?,'WATCH_CONFIRM',?,'WATCH_RECORD',?,'test','test',?,?) RETURNING id",
                Long.class,
                userId,
                sourceId,
                sourceId,
                Timestamp.from(due),
                Timestamp.from(expires));
    }
}
