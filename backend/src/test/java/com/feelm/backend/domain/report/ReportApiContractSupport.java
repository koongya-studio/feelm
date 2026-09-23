package com.feelm.backend.domain.report;

import com.feelm.backend.domain.report.service.ReportImageStorage;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@Import(ReportApiContractSupport.ClockConfiguration.class)
abstract class ReportApiContractSupport extends SharedPostgresContractTest {

    static final long OWNER = 5_560_001L;
    static final long OTHER = 5_560_002L;
    static final Instant RELEASE = Instant.parse("2030-06-30T15:00:00Z");
    static final Instant NEXT_RELEASE = Instant.parse("2030-12-31T15:00:00Z");

    static final String CONTENT =
            """
            {
              "summary": {"ratingCount": 3, "newFlavorCount": 1, "averageScore": 4.0},
              "topGenres": [{"code": "ROMANCE", "name": "로맨스", "count": 2}],
              "topActors": [{"personId": 40, "name": "배우", "count": 2}],
              "topDirectors": [{"personId": 30, "name": "감독", "count": 2}],
              "characterCard": {
                "code": "RDMV",
                "name": "안락의자 탐정",
                "tagline": "움직이지 않고 사건만 잔뜩 봤습니다",
                "description": "익숙한 맛 안에서 아주 많이 봤고 평가는 냉정했습니다.",
                "axes": [
                  {"axis": "EXPLORATION", "value": "R", "label": "정착"},
                  {"axis": "TONE", "value": "D", "label": "그림자"},
                  {"axis": "STANCE", "value": "M", "label": "머리"},
                  {"axis": "VOLUME", "value": "V", "label": "다작"}
                ],
                "basis": {"frameCount": 3, "lowSample": true}
              },
              "flavorChanges": [],
              "fourCut": {
                "genre": {"name": "로맨스", "displayName": "ROMANCE", "image": {"url": null}},
                "director": {"name": "감독", "displayName": null, "image": {"url": null}, "work": null},
                "actor": {"name": "배우", "displayName": null, "image": {"url": null}},
                "character": {"name": "안락의자 탐정", "displayName": null}
              },
              "genreShares": [{"label": "로맨스", "share": 67}],
              "countryShares": [{"label": "한국", "share": 33}],
              "eraShares": [{"label": "2020년대", "share": 67}],
              "keywords": [{"code": "GENRE_FAVORITE", "label": "로맨스 편애",
                "rationale": "로맨스 2편 평균 ★4.0"}],
              "comparisons": [{"label": "다크초코", "delta": 12, "isNew": false}],
              "highlights": ["다크초코 취향이 12%p 늘었어요"],
              "characterAxisMedians": {"subjectCount": 30, "fixedThreshold": false,
                "newFlavorCount": 1, "brightRatio": 0.5, "averageScore": 3.5,
                "scoreStdDev": 0.5, "ratingCount": 20},
              "generation": {"contentVersion": 1, "snapshotAt": "2030-06-30T14:51:00Z",
                "cutoffAt": "2030-06-30T14:50:00Z", "finalSnapshot": true}
            }
            """;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TokenProvider tokenProvider;
    @Autowired protected MutableClock clock;
    @Autowired private ReportImageStorage reportImageStorage;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.query(
                        """
                        SELECT ri.storage_key
                        FROM report_images ri
                        JOIN taste_reports tr ON tr.id = ri.report_id
                        WHERE tr.user_id IN (?, ?)
                        """,
                        (result, row) -> result.getString("storage_key"),
                        OWNER,
                        OTHER)
                .forEach(reportImageStorage::delete);
        jdbc.update("DELETE FROM taste_reports WHERE user_id IN (?, ?)", OWNER, OTHER);
        jdbc.update("DELETE FROM taste_report_generation_runs WHERE year = 2030");
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", OWNER, OTHER);
        clock.set(RELEASE);
    }

    protected void users() {
        jdbc.update(
                """
                INSERT INTO users (id, email, nickname, birth_date, gender, status)
                VALUES (?, 'report-556-owner@test.local', 'report556-owner', DATE '2000-01-01',
                        'OTHER', 'ACTIVE')
                """,
                OWNER);
        jdbc.update(
                """
                INSERT INTO users (id, email, nickname, birth_date, gender, status)
                VALUES (?, 'report-556-other@test.local', 'report556-other', DATE '2000-01-01',
                        'OTHER', 'ACTIVE')
                """,
                OTHER);
    }

    protected void report(long id, long userId, int year, String half, String status) {
        jdbc.update(
                """
                INSERT INTO taste_reports (id, user_id, year, half, status, content, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                id,
                userId,
                year,
                half,
                status,
                CONTENT,
                Timestamp.from(Instant.parse("2030-06-30T14:55:00Z")));
    }

    protected void markIssued(long id) {
        jdbc.update(
                "UPDATE taste_reports SET issued_at = ? WHERE id = ?", Timestamp.from(RELEASE), id);
    }

    protected String bearer(long userId) {
        return "Bearer " + tokenProvider.createAccessToken(userId);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock reportApiContractClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(RELEASE);

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
