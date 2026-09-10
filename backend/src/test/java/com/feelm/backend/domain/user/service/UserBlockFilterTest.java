package com.feelm.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** FR-39: 다른 도메인이 쓰는 차단 필터가 양방향 차단 관계를 어떻게 보는지 확인한다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@Testcontainers
@DisplayName("UserBlockFilter — 공통 차단 필터")
class UserBlockFilterTest {

    private static final Instant BLOCKED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

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
