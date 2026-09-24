package com.feelm.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository.LockAttempt;
import com.feelm.backend.domain.report.service.ReportGenerationCoordinator;
import com.feelm.backend.domain.report.service.ReportGenerationCoordinator.PublicationResult;
import com.feelm.backend.domain.report.service.ReportPreparationService;
import com.feelm.backend.domain.report.service.ReportPublicationService;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("반기 취향 리포트 생성 — 실제 PostgreSQL 계약")
class ReportGenerationIntegrationTest extends SharedPostgresContractTest {

    private static final long BASE = 5_550_000L;
    private static final long USER = BASE + 1;
    private static final ReportPeriod PERIOD = new ReportPeriod(2026, ReportHalf.H1);

    @Autowired private ReportPreparationService preparationService;
    @Autowired private ReportPublicationService publicationService;
    @Autowired private ReportGenerationCoordinator coordinator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        removeReportFailureTrigger();
        jdbc.update("DELETE FROM taste_report_generation_runs WHERE year = 2026 AND half = 'H1'");
        jdbc.update(
                "DELETE FROM report_images WHERE report_id IN"
                        + " (SELECT id FROM taste_reports WHERE user_id BETWEEN ? AND ?)",
                BASE,
                BASE + 999);
        jdbc.update("DELETE FROM taste_reports WHERE user_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM frames WHERE user_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM ratings WHERE user_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM movie_genres WHERE movie_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM movie_countries WHERE movie_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM movie_people WHERE movie_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM movies WHERE id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM people WHERE id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM genres WHERE id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM popcorn_flavors WHERE id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", BASE, BASE + 999);
    }

    @Test
    @DisplayName("남아 있는 프레임이 3편 이상일 때만 준비 결과를 유지한다")
    void eligibilityUsesSurvivingFrames() throws Exception {
        insertUser();
        insertFrames(1, 2, "2026-01-10T00:00:00Z");

        preparationService.prepare(PERIOD, Instant.parse("2026-06-01T00:00:00Z"), false);
        assertThat(reportCount()).isZero();

        insertFrames(3, 3, "2026-02-10T00:00:00Z");
        preparationService.prepare(PERIOD, Instant.parse("2026-06-01T00:00:00Z"), false);
        assertThat(reportContent().path("summary").path("ratingCount").asInt()).isEqualTo(3);

        insertFrames(4, 5, "2026-03-10T00:00:00Z");
        preparationService.prepare(PERIOD, Instant.parse("2026-06-01T00:00:00Z"), false);
        assertThat(reportContent().path("summary").path("ratingCount").asInt()).isEqualTo(5);

        jdbc.update(
                "DELETE FROM ratings WHERE id IN (?, ?, ?)", ratingId(3), ratingId(4), ratingId(5));
        preparationService.prepare(PERIOD, Instant.parse("2026-06-01T00:00:00Z"), false);
        assertThat(reportCount()).isZero();

        preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true);
        assertThat(completedRunCount()).isOne();
        assertThat(completedPreparedCount()).isZero();
        assertThat(coordinator.publishPrepared(PERIOD))
                .isEqualTo(PublicationResult.NO_ELIGIBLE_USERS);
    }

    @Test
    @DisplayName("같은 스냅샷에서 비율·순위·지난 반기 차이와 신규 여부를 집계한다")
    void aggregatesOneSnapshot() throws Exception {
        insertUser();
        insertMetadata();
        insertReadyPreviousReport();
        insertMovieFrame(1, new BigDecimal("4.5"), "2026-01-10T00:00:00Z", 2024, 1);
        insertMovieFrame(2, new BigDecimal("4.0"), "2026-02-10T00:00:00Z", 2019, 1);
        insertMovieFrame(3, new BigDecimal("5.0"), "2026-03-10T00:00:00Z", 2018, 2);
        insertMovieFrame(4, new BigDecimal("3.0"), "2025-10-10T00:00:00Z", 2010, 1);
        mapMovie(1, true, true, "KR", 1, 1);
        mapMovie(2, true, false, "US", 1, 2);
        mapMovie(3, false, true, "KR", 2, 1);
        mapMovie(4, true, false, "KR", 1, 1);

        preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true);
        assertThat(coordinator.publishPrepared(PERIOD)).isEqualTo(PublicationResult.READY);

        JsonNode content = reportContent();
        assertThat(reportStatus()).isEqualTo("READY");
        assertThat(content.path("summary").path("ratingCount").asInt()).isEqualTo(3);
        assertThat(content.path("summary").path("averageScore").decimalValue())
                .isEqualByComparingTo("4.5");
        assertThat(content.path("summary").path("newFlavorCount").asInt()).isEqualTo(1);
        assertThat(content.path("topGenres").get(0).path("code").asText()).isEqualTo("R555_DRAMA");
        assertThat(content.path("fourCut").path("genre").path("name").asText()).isEqualTo("드라마");
        assertThat(content.path("fourCut").path("genre").path("displayName").asText())
                .isEqualTo("R555_DRAMA");
        assertThat(content.path("fourCut").path("genre").path("image").path("url").asText())
                .isEqualTo("https://image.tmdb.org/t/p/original/report-backdrop-3.jpg");
        assertThat(content.path("topDirectors").get(0).path("personId").asLong())
                .isEqualTo(personId(1));
        assertThat(content.path("topActors").get(0).path("personId").asLong())
                .isEqualTo(personId(3));
        assertThat(content.path("countryShares").get(0).path("label").asText()).isEqualTo("한국");
        assertThat(content.path("countryShares").get(0).path("share").asInt()).isEqualTo(67);
        assertThat(content.path("eraShares").get(0).path("label").asText()).isEqualTo("2010년대");
        // 비교 축은 팝콘 맛이다(업무 -774). 이번 반기는 느와르 2편·달콤 1편(3편), 직전 반기는
        // 느와르 1편(1편)이라 느와르 100%→67%, 달콤 0%→33%다. 달콤은 이번 반기 전 프레임에
        // 없어 신규다. 두 증감 폭이 같아 배열 순서는 표시명으로 찾는다.
        JsonNode sweet = comparisonOf(content, "달콤");
        assertThat(sweet.path("delta").asInt()).isEqualTo(33);
        assertThat(sweet.path("isNew").asBoolean()).isTrue();
        JsonNode noir = comparisonOf(content, "느와르");
        assertThat(noir.path("delta").asInt()).isEqualTo(-33);
        assertThat(noir.path("isNew").asBoolean()).isFalse();
        assertThat(content.path("fourCut").path("director").path("image").path("url").asText())
                .isEqualTo("https://images.test/director-1.jpg");
        assertThat(content.path("fourCut").path("director").path("displayName").asText())
                .isEqualTo("Director One");
        assertThat(content.path("fourCut").path("actor").path("displayName").asText())
                .isEqualTo("Actor One");
        assertThat(content.path("fourCut").path("director").path("work").path("url").asText())
                .isEqualTo("https://image.tmdb.org/t/p/original/report-backdrop-1.jpg");
        /*
         * 캐릭터 카드는 프레임 3편 이상이면 늘 함께 생긴다 — 16종이 모든 조합을 덮어 후보 없음이
         * 없다(API-060, 업무 -779). 이 사용자는 3편이라 소량 표본이다.
         */
        assertThat(content.path("characterCard").path("code").asText()).hasSize(4);
        assertThat(content.path("characterCard").path("name").asText()).isNotBlank();
        assertThat(content.path("characterCard").path("tagline").asText()).isNotBlank();
        assertThat(content.path("characterCard").path("axes")).hasSize(4);
        assertThat(content.path("characterCard").path("basis").path("frameCount").asInt())
                .isEqualTo(3);
        assertThat(content.path("characterCard").path("basis").path("lowSample").asBoolean())
                .isTrue();
        assertThat(content.path("fourCut").path("character").path("name").asText())
                .isEqualTo(content.path("characterCard").path("name").asText());
        assertThat(content.path("fourCut").path("character").has("image")).isFalse();
        // 취향 키워드는 임계값을 넘긴 규칙만 순서대로 최대 넷이다(업무 -774). 이 픽스처는
        // 감독·배우 3편 이상과 새 감독 3명을 못 넘겨 사람 규칙이 빠지고, 맛 둘(신규 달콤 ·
        // 느와르 67%)과 장르 둘(드라마 67% · 드라마 평균 ★4.8)로 넷이 차서 평가 습관까지
        // 가지 않는다.
        assertThat(content.path("keywords")).hasSize(4);
        assertThat(keywordOf(content, "FLAVOR_EXPLORER").path("label").asText())
                .isEqualTo("달콤 탐험가");
        assertThat(keywordOf(content, "FLAVOR_EXPLORER").path("rationale").asText())
                .isEqualTo("이번 반기에 달콤 프레임 1개 추가");
        assertThat(keywordOf(content, "FLAVOR_DEVOTEE").path("rationale").asText())
                .isEqualTo("느와르가 67%");
        assertThat(keywordOf(content, "GENRE_DEVOTEE").path("rationale").asText())
                .isEqualTo("드라마가 67%");
        assertThat(keywordOf(content, "GENRE_FAVORITE").path("rationale").asText())
                .isEqualTo("드라마 2편 평균 ★4.8");
    }

    @Test
    @DisplayName("두 영화에서 모두 11번째인 배우도 감상 편수와 4컷에 반영한다")
    void actorCreditsBeyondTenthAreCounted() throws Exception {
        insertUser();
        insertFrames(1, 3, "2026-01-10T00:00:00Z");
        insertPerson(1, "열한 번째 배우", "https://images.test/actor-11.jpg");

        for (int movieSuffix = 1; movieSuffix <= 2; movieSuffix++) {
            for (int position = 1; position <= 10; position++) {
                int personSuffix = 1 + (movieSuffix - 1) * 10 + position;
                insertPerson(personSuffix, "단역 " + personSuffix, null);
                jdbc.update(
                        "INSERT INTO movie_people (movie_id, person_id, role, display_order)"
                                + " VALUES (?, ?, 'ACTOR', ?)",
                        movieId(movieSuffix),
                        personId(personSuffix),
                        position);
            }
            jdbc.update(
                    "INSERT INTO movie_people (movie_id, person_id, role, display_order)"
                            + " VALUES (?, ?, 'ACTOR', 11)",
                    movieId(movieSuffix),
                    personId(1));
        }

        preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true);

        JsonNode content = reportContent();
        assertThat(content.path("topActors").get(0).path("personId").asLong())
                .isEqualTo(personId(1));
        assertThat(content.path("topActors").get(0).path("count").asInt()).isEqualTo(2);
        assertThat(content.path("fourCut").path("actor").path("name").asText())
                .isEqualTo("열한 번째 배우");
        assertThat(content.path("fourCut").path("actor").path("image").path("url").asText())
                .isEqualTo("https://images.test/actor-11.jpg");
        assertThat(content.path("fourCut").path("actor").path("displayName").isNull()).isTrue();
    }

    @Test
    @DisplayName("최종 준비 트랜잭션은 시작 뒤 커밋된 평가 수정을 포함하지 않는다")
    void cutoffSnapshotIsStable() throws Exception {
        insertUser();
        insertFrames(1, 3, "2026-01-10T00:00:00Z");
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE frames IN ACCESS EXCLUSIVE MODE");
            Future<?> preparation =
                    worker.submit(
                            () ->
                                    preparationService.prepare(
                                            PERIOD, PERIOD.cutoffExclusive(), true));

            assertThat(waitUntilEligibilityQueryBlocks()).isTrue();
            assertThat(preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true).locked())
                    .isFalse();
            jdbc.update("UPDATE ratings SET score = 1.0 WHERE id = ?", ratingId(1));
            blocker.commit();
            preparation.get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }

        assertThat(reportStatus()).isEqualTo("GENERATING");
        assertThat(reportContent().path("summary").path("averageScore").decimalValue())
                .isEqualByComparingTo("4.0");

        ReportGenerationRepository lateRepository = mock(ReportGenerationRepository.class);
        when(lateRepository.tryLock(PERIOD))
                .thenReturn(new LockAttempt(true, PERIOD.cutoffExclusive().plusSeconds(301)));
        when(lateRepository.findCompletedPreparedCount(PERIOD)).thenReturn(null);
        ReportPreparationService latePreparation =
                new ReportPreparationService(lateRepository, null, null);
        assertThatThrownBy(() -> latePreparation.prepareScheduledFinal(PERIOD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("23:50–23:55");
        verify(lateRepository, never()).findEligibleUserIds(any(), any());
    }

    @Test
    @DisplayName("완성된 준비 결과를 재시도하면 한 행만 READY로 전이한다")
    void retryAndTransition() throws Exception {
        insertUser();
        insertFrames(1, 3, "2026-01-10T00:00:00Z");
        insertUser(USER + 1, "report-555-second@test.local", "report555-second");
        insertFrames(USER + 1, 11, 13, "2026-01-10T00:10:00Z");

        installReportFailureTrigger(USER + 1);
        assertThatThrownBy(() -> preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("forced report write failure");
        assertThat(periodReportCount()).isZero();
        assertThat(completedRunCount()).isZero();
        removeReportFailureTrigger();

        preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true);
        assertThat(reportStatus()).isEqualTo("GENERATING");
        assertThat(reportContent().path("generation").path("finalSnapshot").asBoolean()).isTrue();
        assertThat(completedRunCount()).isOne();
        assertThat(completedPreparedCount()).isEqualTo(2);

        jdbc.update("UPDATE ratings SET score = 1 WHERE id = ?", ratingId(1));
        assertThat(
                        preparationService
                                .prepare(PERIOD, PERIOD.cutoffExclusive(), true)
                                .preparedCount())
                .isZero();
        assertThat(reportContent().path("summary").path("averageScore").decimalValue())
                .isEqualByComparingTo("4.0");

        assertThat(coordinator.findPreparedPeriodsReadyToPublish(PERIOD.cutoffExclusive()))
                .doesNotContain(PERIOD);
        assertThat(
                        coordinator.findPreparedPeriodsReadyToPublish(
                                PERIOD.cutoffExclusive().plus(2, ChronoUnit.DAYS)))
                .contains(PERIOD);

        assertThat(coordinator.publishPrepared(PERIOD)).isEqualTo(PublicationResult.READY);
        assertThat(reportStatus()).isEqualTo("READY");
        assertThat(coordinator.publishPrepared(PERIOD)).isEqualTo(PublicationResult.ALREADY_READY);
        assertThat(
                        coordinator.findPreparedPeriodsReadyToPublish(
                                PERIOD.cutoffExclusive().plus(2, ChronoUnit.DAYS)))
                .doesNotContain(PERIOD);
        assertThat(reportCount()).isOne();
        assertThat(periodReportCount()).isEqualTo(2);
    }

    /*
     * 캐릭터 카드 판정이 대상자 전체의 중앙값 기준 상대 판정이라(API-060, 업무 -779) 같은 반기의
     * 모든 리포트가 같은 스냅샷을 써야 한다. 지표가 서로 다른 셋을 한 번에 준비해 확인한다.
     */
    @Test
    @DisplayName("대상자 전원이 같은 중앙값 스냅샷을 쓴다")
    void allSubjectsShareOneMedianSnapshot() throws Exception {
        insertUser();
        insertFrames(1, 3, "2026-01-10T00:00:00Z");
        insertUser(USER + 1, "report-779-second@test.local", "report779-second");
        insertFrames(USER + 1, 11, 22, "2026-02-10T00:00:00Z");
        insertUser(USER + 2, "report-779-third@test.local", "report779-third");
        insertFrames(USER + 2, 31, 38, "2026-03-10T00:00:00Z");

        preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true);

        JsonNode first = reportContent(USER).path("characterAxisMedians");
        JsonNode second = reportContent(USER + 1).path("characterAxisMedians");
        JsonNode third = reportContent(USER + 2).path("characterAxisMedians");

        assertThat(first.isMissingNode()).isFalse();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        // 대상자 셋은 30명 미만이라 고정 임계값으로 떨어진다
        assertThat(first.path("subjectCount").asInt()).isEqualTo(3);
        assertThat(first.path("fixedThreshold").asBoolean()).isTrue();

        // 편수 3 · 12 · 8을 고정 임계값 10편으로 가르면 S · V · S다
        assertThat(reportContent(USER).path("characterCard").path("code").asText()).endsWith("S");
        assertThat(reportContent(USER + 1).path("characterCard").path("code").asText())
                .endsWith("V");
        assertThat(reportContent(USER + 2).path("characterCard").path("code").asText())
                .endsWith("S");
    }

    @Test
    @DisplayName("프레임 10편 이상이면 소량 표본이 아니다")
    void marksNormalSampleFromTenFrames() throws Exception {
        insertUser();
        insertFrames(1, 10, "2026-01-10T00:00:00Z");

        preparationService.prepare(PERIOD, PERIOD.cutoffExclusive(), true);

        JsonNode basis = reportContent().path("characterCard").path("basis");
        assertThat(basis.path("frameCount").asInt()).isEqualTo(10);
        assertThat(basis.path("lowSample").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("프레임 3편 미만이면 리포트도 카드도 없다")
    void skipsUsersBelowThreeFrames() throws Exception {
        insertUser();
        insertFrames(1, 2, "2026-01-10T00:00:00Z");

        assertThat(
                        preparationService
                                .prepare(PERIOD, PERIOD.cutoffExclusive(), true)
                                .preparedCount())
                .isZero();
        assertThat(reportCount()).isZero();
    }

    private void insertUser() {
        insertUser(USER, "report-555@test.local", "report555");
    }

    private void insertUser(long userId, String email, String nickname) {
        jdbc.update(
                """
                INSERT INTO users (id, email, nickname, birth_date, gender, status)
                VALUES (?, ?, ?, DATE '2000-01-01', 'OTHER', 'ACTIVE')
                """,
                userId,
                email,
                nickname);
    }

    private void insertMetadata() {
        jdbc.update(
                "INSERT INTO genres (id, code, name, tmdb_genre_id)"
                        + " VALUES (?, 'R555_THRILLER', '스릴러', 955553)",
                genreId(1));
        jdbc.update(
                "INSERT INTO genres (id, code, name, tmdb_genre_id)"
                        + " VALUES (?, 'R555_DRAMA', '드라마', 955554)",
                genreId(2));
        jdbc.update(
                "INSERT INTO popcorn_flavors"
                        + " (id, code, display_name, color, description, sort_order, cluster_id, representative_keywords)"
                        + " VALUES (?, 'R555_NOIR', '느와르', '#111111', '테스트 맛 설명', 555101, 555101, '[]'::jsonb)",
                flavorId(1));
        jdbc.update(
                "INSERT INTO popcorn_flavors"
                        + " (id, code, display_name, color, description, sort_order, cluster_id, representative_keywords)"
                        + " VALUES (?, 'R555_SWEET', '달콤', '#ffaaaa', '테스트 맛 설명', 555102, 555102, '[]'::jsonb)",
                flavorId(2));
        insertPerson(1, "감독 하나", "Director One", "https://images.test/director-1.jpg");
        insertPerson(2, "감독 둘", null);
        insertPerson(3, "배우 하나", "Actor One", "https://images.test/actor-1.jpg");
        insertPerson(4, "배우 둘", null);
    }

    private void insertPerson(int suffix, String name, String profileUrl) {
        insertPerson(suffix, name, null, profileUrl);
    }

    private void insertPerson(int suffix, String name, String originalName, String profileUrl) {
        jdbc.update(
                "INSERT INTO people (id, tmdb_person_id, name, original_name, profile_url)"
                        + " VALUES (?, ?, ?, ?, ?)",
                personId(suffix),
                Math.toIntExact(personId(suffix)),
                name,
                originalName,
                profileUrl);
    }

    private void insertReadyPreviousReport() {
        jdbc.update(
                """
                INSERT INTO taste_reports (user_id, year, half, status, content)
                VALUES (?, 2025, 'H2', 'READY', '{}'::jsonb)
                """,
                USER);
    }

    private void insertFrames(int from, int to, String firstCreatedAt) {
        insertFrames(USER, from, to, firstCreatedAt);
    }

    private void insertFrames(long userId, int from, int to, String firstCreatedAt) {
        Instant base = Instant.parse(firstCreatedAt);
        for (int suffix = from; suffix <= to; suffix++) {
            insertMovieFrame(
                    userId,
                    suffix,
                    new BigDecimal("4.0"),
                    base.plusSeconds((suffix - from) * 60L).toString(),
                    2020,
                    null);
        }
    }

    private void insertMovieFrame(
            int suffix, BigDecimal score, String createdAt, int releaseYear, Integer flavorSuffix) {
        insertMovieFrame(USER, suffix, score, createdAt, releaseYear, flavorSuffix);
    }

    private void insertMovieFrame(
            long userId,
            int suffix,
            BigDecimal score,
            String createdAt,
            int releaseYear,
            Integer flavorSuffix) {
        long movieId = movieId(suffix);
        jdbc.update(
                """
                INSERT INTO movies
                    (id, tmdb_id, title, release_date, representative_flavor_id,
                     poster_url, backdrop_url)
                VALUES (?, ?, ?, make_date(?, 1, 1), ?, ?, ?)
                """,
                movieId,
                Math.toIntExact(movieId),
                "리포트 영화 " + suffix,
                releaseYear,
                flavorSuffix == null ? null : flavorId(flavorSuffix),
                "https://image.tmdb.org/t/p/original/report-poster-" + suffix + ".jpg",
                "https://image.tmdb.org/t/p/original/report-backdrop-" + suffix + ".jpg");
        Instant instant = Instant.parse(createdAt);
        jdbc.update(
                """
                INSERT INTO ratings
                    (id, user_id, movie_id, score, visibility, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PUBLIC', ?, ?)
                """,
                ratingId(suffix),
                userId,
                movieId,
                score,
                Timestamp.from(instant),
                Timestamp.from(instant));
        jdbc.update(
                """
                INSERT INTO frames (id, rating_id, user_id, movie_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                frameId(suffix),
                ratingId(suffix),
                userId,
                movieId,
                Timestamp.from(instant));
    }

    private void mapMovie(
            int movieSuffix,
            boolean thriller,
            boolean drama,
            String country,
            int directorSuffix,
            int actorSuffix) {
        long movieId = movieId(movieSuffix);
        if (thriller) {
            jdbc.update(
                    "INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)",
                    movieId,
                    genreId(1));
        }
        if (drama) {
            jdbc.update(
                    "INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)",
                    movieId,
                    genreId(2));
        }
        jdbc.update(
                "INSERT INTO movie_countries (movie_id, country_code) VALUES (?, ?)",
                movieId,
                country);
        jdbc.update(
                "INSERT INTO movie_people (movie_id, person_id, role, display_order)"
                        + " VALUES (?, ?, 'DIRECTOR', 0)",
                movieId,
                personId(directorSuffix));
        jdbc.update(
                "INSERT INTO movie_people (movie_id, person_id, role, display_order)"
                        + " VALUES (?, ?, 'ACTOR', 0)",
                movieId,
                personId(actorSuffix + 2));
    }

    private boolean waitUntilEligibilityQueryBlocks() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Boolean blocked =
                    jdbc.queryForObject(
                            """
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_stat_activity
                                WHERE query LIKE 'SELECT f.user_id%'
                                  AND wait_event_type = 'Lock'
                            )
                            """,
                            Boolean.class);
            if (Boolean.TRUE.equals(blocked)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private JsonNode reportContent(long userId) throws Exception {
        String content =
                jdbc.queryForObject(
                        "SELECT content::text FROM taste_reports WHERE user_id = ? AND year = 2026 AND half = 'H1'",
                        String.class,
                        userId);
        return objectMapper.readTree(content);
    }

    private JsonNode reportContent() throws Exception {
        String content =
                jdbc.queryForObject(
                        "SELECT content::text FROM taste_reports WHERE user_id = ? AND year = 2026 AND half = 'H1'",
                        String.class,
                        USER);
        return objectMapper.readTree(content);
    }

    private JsonNode keywordOf(JsonNode content, String code) {
        for (JsonNode keyword : content.path("keywords")) {
            if (code.equals(keyword.path("code").asText())) {
                return keyword;
            }
        }
        throw new AssertionError("키워드에 " + code + "가 없다: " + content.path("keywords"));
    }

    private JsonNode comparisonOf(JsonNode content, String label) {
        for (JsonNode comparison : content.path("comparisons")) {
            if (label.equals(comparison.path("label").asText())) {
                return comparison;
            }
        }
        throw new AssertionError("비교 목록에 " + label + "이(가) 없다: " + content.path("comparisons"));
    }

    private String reportStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM taste_reports WHERE user_id = ? AND year = 2026 AND half = 'H1'",
                String.class,
                USER);
    }

    private long reportCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM taste_reports WHERE user_id = ? AND year = 2026 AND half = 'H1'",
                Long.class,
                USER);
    }

    private long periodReportCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM taste_reports WHERE user_id BETWEEN ? AND ?"
                        + " AND year = 2026 AND half = 'H1'",
                Long.class,
                BASE,
                BASE + 999);
    }

    private long completedRunCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM taste_report_generation_runs WHERE year = 2026 AND half = 'H1'",
                Long.class);
    }

    private int completedPreparedCount() {
        return jdbc.queryForObject(
                "SELECT prepared_count FROM taste_report_generation_runs"
                        + " WHERE year = 2026 AND half = 'H1'",
                Integer.class);
    }

    private void installReportFailureTrigger(long rejectedUserId) {
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION test_fail_report_555() RETURNS trigger AS $$
                BEGIN
                    IF NEW.user_id = %d THEN
                        RAISE EXCEPTION 'forced report write failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """
                        .formatted(rejectedUserId));
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_report_555
                BEFORE INSERT OR UPDATE ON taste_reports
                FOR EACH ROW EXECUTE FUNCTION test_fail_report_555()
                """);
    }

    private void removeReportFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_report_555 ON taste_reports");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_report_555()");
    }

    private long movieId(int suffix) {
        return BASE + 100 + suffix;
    }

    private long ratingId(int suffix) {
        return BASE + 200 + suffix;
    }

    private long frameId(int suffix) {
        return BASE + 300 + suffix;
    }

    private long genreId(int suffix) {
        return BASE + 400 + suffix;
    }

    private long flavorId(int suffix) {
        return BASE + 500 + suffix;
    }

    private long personId(int suffix) {
        return BASE + 600 + suffix;
    }
}
