package com.feelm.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-39: 다른 도메인이 쓰는 차단 필터가 양방향 차단 관계를 어떻게 보는지 확인한다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("UserBlockFilter — 공통 차단 필터")
class UserBlockFilterTest extends SharedPostgresContractTest {

    private static final Instant BLOCKED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Autowired UserBlockFilter filter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        for (long id : new long[] {10, 20, 30, 40, 50}) {
            user(id);
        }
    }

    @Test
    @DisplayName("T18 차단은 저장 방향과 무관하게 양쪽에서 보인다")
    void detectsBlockInBothDirections() {
        block(10, 20);

        assertThat(filter.isBlocked(10, 20)).isTrue();
        assertThat(filter.isBlocked(20, 10)).isTrue();
        assertThat(filter.isBlocked(10, 30)).isFalse();
        assertThat(filter.isBlocked(10, 10)).isFalse();
    }

    @Test
    @DisplayName("T19 후보 중 차단 관계인 사용자만 돌려준다")
    void returnsBlockedCandidatesOnly() {
        block(10, 20);
        block(30, 10);
        block(40, 50);

        assertThat(filter.blockedAmong(10, List.of(20L, 30L, 40L)))
                .containsExactlyInAnyOrder(20L, 30L);
    }

    @Test
    @DisplayName("T20 후보가 비어 있으면 빈 결과다")
    void returnsEmptyForEmptyCandidates() {
        block(10, 20);

        assertThat(filter.blockedAmong(10, List.of())).isEmpty();
        assertThat(filter.blockedAmong(10, null)).isEmpty();
    }

    @Test
    @DisplayName("T21 나와 관계된 모든 차단 상대를 양방향으로 모은다")
    void collectsAllCounterpartsInBothDirections() {
        block(10, 20);
        block(30, 10);
        block(40, 50);

        Set<Long> counterparts = filter.blockedCounterpartIds(10);

        assertThat(counterparts).containsExactlyInAnyOrder(20L, 30L);
        assertThat(filter.blockedCounterpartIds(50)).containsExactly(40L);
        assertThat(filter.blockedCounterpartIds(60)).isEmpty();
    }

    private void user(long id) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER','ACTIVE')",
                id,
                "user" + id + "@example.test",
                "user" + id);
    }

    private void block(long blocker, long blocked) {
        jdbc.update(
                "INSERT INTO user_blocks(blocker_id,blocked_id,created_at) VALUES (?,?,?)",
                blocker,
                blocked,
                Timestamp.from(BLOCKED_AT));
    }
}
