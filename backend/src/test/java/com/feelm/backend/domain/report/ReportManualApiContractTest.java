package com.feelm.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.report.dto.ReportGenerateResponse;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository;
import com.feelm.backend.domain.report.repository.ReportManualRepository;
import com.feelm.backend.domain.report.service.ReportContentAssembler;
import com.feelm.backend.domain.report.service.ReportGenerationScheduler;
import com.feelm.backend.domain.report.service.ReportManualService;
import com.feelm.backend.domain.report.service.ReportReadService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class ReportManualApiContractTest extends ReportApiContractSupport {

    private static final long BASE = 5_930_100L;
    @Autowired private ReportManualService manual;
    @Autowired private ApplicationContext context;

    @BeforeEach
    void setUpFilm() {
        users();
    }

    @AfterEach
    void cleanFilm() {
        jdbc.update("DELETE FROM frames WHERE user_id = ?", OWNER);
        jdbc.update("DELETE FROM ratings WHERE user_id = ?", OWNER);
        jdbc.update("DELETE FROM movies WHERE id BETWEEN ? AND ?", BASE, BASE + 100);
        jdbc.update("DELETE FROM popcorn_flavors WHERE id IN (?, ?)", BASE + 1, BASE + 2);
    }

    @Test
    void firstReportRequiresTwentyFramesAndIncludesAllFilm() throws Exception {
        frames(19);
        generate()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("INSUFFICIENT_FRAMES"));
        frame(20);
        generate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.frontUnchanged").value(false));
        var report = manual.generate(OWNER);
        mockMvc.perform(
                        get("/api/v1/reports/{id}", report.reportId())
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ratingCount").value(20))
                .andExpect(jsonPath("$.analysisScope").value("FILM"))
                .andExpect(jsonPath("$.comparisons", hasSize(0)))
                .andExpect(jsonPath("$.period.half").value("H2"));
        // API clock is July 1 KST. These frames span multiple previous calendar halves.
        assertThat(count()).isOne();
    }

    @Test
    void unchangedFilmAndNoOpRatingSaveReuseLatest() throws Exception {
        frames(20);
        var first = manual.generate(OWNER);
        jdbc.update(
                "UPDATE ratings SET score = score, updated_at = now() WHERE user_id = ?", OWNER);
        var second = manual.generate(OWNER);
        assertThat(second.reportId()).isEqualTo(first.reportId());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.reused()).isTrue();
        assertThat(second.reuseReason()).isEqualTo("UNCHANGED_FILM");
        assertThat(second.frontUnchanged()).isFalse();
        assertThat(count()).isOne();
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(jsonPath("$.generation.reason").value("UNCHANGED"));
    }

    @Test
    void additionsDeletionsAndEveryRatingValueAreRecalculated() {
        frames(20);
        var assembler = spy(context.getBean(ReportContentAssembler.class));
        var calls = new AtomicInteger();
        doAnswer(
                        call -> {
                            calls.incrementAndGet();
                            return call.callRealMethod();
                        })
                .when(assembler)
                .assembleCumulative(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var service = serviceWith(new ReportManualRepository(jdbc), assembler);
        var first = service.generate(OWNER);
        String original = content(first.reportId());
        frame(21);
        jdbc.update("UPDATE movies SET release_date = DATE '2000-01-01' WHERE id = ?", BASE + 21);
        var added = service.generate(OWNER);
        assertThat(added.reportId()).isNotEqualTo(first.reportId());
        jdbc.update("DELETE FROM frames WHERE id = ?", BASE + 21);
        var restored = service.generate(OWNER);
        assertThat(restored.reportId())
                .isNotEqualTo(first.reportId())
                .isNotEqualTo(added.reportId());
        for (String change :
                new String[] {
                    "score = 3.5",
                    "comment = '수정한 평가'",
                    "visibility = 'PRIVATE'",
                    "is_spoiler = true"
                }) {
            int before = calls.get();
            jdbc.update("UPDATE ratings SET " + change + " WHERE id = ?", BASE + 1);
            service.generate(OWNER);
            assertThat(calls.get()).isEqualTo(before + 1);
        }
        jdbc.update("DELETE FROM frames WHERE id = ?", BASE + 2);
        service.generate(OWNER);
        assertThat(content(first.reportId())).isEqualTo(original);
        assertThat(calls.get()).isEqualTo(8);
        assertThat(count()).isBetween(3, 8);
    }

    @Test
    void changedFilmWithIdenticalPresentationReusesWithoutUpdatingStoredSnapshot()
            throws Exception {
        frames(20);
        var first = manual.generate(OWNER);
        markIssued(first.reportId());
        String original =
                jdbc.queryForObject(
                        "SELECT row_to_json(tr)::text FROM taste_reports tr WHERE id = ?",
                        String.class,
                        first.reportId());
        clock.set(RELEASE.plusSeconds(60));
        jdbc.update("UPDATE ratings SET comment = '분석 내용은 같은 한줄평' WHERE id = ?", BASE + 1);
        generate()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(first.reportId()))
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.reuseReason").value("SAME_CONTENT"))
                .andExpect(jsonPath("$.frontUnchanged").value(false))
                .andExpect(jsonPath("$.createdAt").value(first.createdAt().toString()))
                .andExpect(jsonPath("$.issuedAt").value(RELEASE.toString()));
        assertThat(manual.generate(OWNER).reuseReason()).isEqualTo("SAME_CONTENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT row_to_json(tr)::text FROM taste_reports tr WHERE id = ?",
                                String.class,
                                first.reportId()))
                .isEqualTo(original);
        assertThat(count()).isOne();
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void concurrentSameContentGenerationDoesNotInsert() throws Exception {
        frames(20);
        var first = manual.generate(OWNER);
        jdbc.update("UPDATE ratings SET comment = '변경' WHERE id = ?", BASE + 1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> manual.generate(OWNER));
            var b = executor.submit(() -> manual.generate(OWNER));
            for (var future : java.util.List.of(a, b)) {
                var result = future.get(10, TimeUnit.SECONDS);
                assertThat(result.reportId()).isEqualTo(first.reportId());
                assertThat(result.reuseReason()).isEqualTo("SAME_CONTENT");
            }
        }
        assertThat(count()).isOne();
    }

    @Test
    void identicalFourCutWithDifferentBackAnalysisIsSaved() throws Exception {
        frames(20);
        var first = manual.generate(OWNER);
        var mapper = context.getBean(ObjectMapper.class);
        var before = mapper.readTree(content(first.reportId()));
        frame(21);
        jdbc.update("UPDATE movies SET release_date = DATE '2000-01-01' WHERE id = ?", BASE + 21);
        var response =
                generate()
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.reused").value(false))
                        .andExpect(jsonPath("$.frontUnchanged").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var result = mapper.readTree(response);
        long nextId = result.get("reportId").asLong();
        var after = mapper.readTree(content(nextId));
        assertThat(after.get("fourCut")).isEqualTo(before.get("fourCut"));
        assertThat(after.get("eraShares")).isNotEqualTo(before.get("eraShares"));
        assertThat(result.get("reuseReason").isNull()).isTrue();
        assertThat(nextId).isNotEqualTo(first.reportId());
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", nextId)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(nextId))
                .andExpect(jsonPath("$.issuedAt").value(RELEASE.toString()));
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].reportId").value(nextId));
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void firstReportAndChangedFrontDoNotShowMinorNotice() {
        frames(20);
        var first = manual.generate(OWNER);
        assertThat(first.frontUnchanged()).isFalse();
        jdbc.update("DELETE FROM frames WHERE user_id = ?", OWNER);
        var empty = manual.generate(OWNER);
        assertThat(empty.reused()).isFalse();
        assertThat(empty.frontUnchanged()).isFalse();
        assertThat(empty.reportId()).isNotEqualTo(first.reportId());
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void concurrentBackOnlyChangeNotifiesOnlyNewResult() throws Exception {
        frames(20);
        var first = manual.generate(OWNER);
        frame(21);
        jdbc.update("UPDATE movies SET release_date = DATE '2000-01-01' WHERE id = ?", BASE + 21);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> manual.generate(OWNER));
            var b = executor.submit(() -> manual.generate(OWNER));
            var responses =
                    java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(responses)
                    .filteredOn(result -> !result.reused())
                    .singleElement()
                    .satisfies(result -> assertThat(result.frontUnchanged()).isTrue());
            assertThat(responses)
                    .filteredOn(ReportGenerateResponse::reused)
                    .singleElement()
                    .satisfies(
                            result -> {
                                assertThat(result.frontUnchanged()).isFalse();
                                assertThat(result.reuseReason()).isEqualTo("UNCHANGED_FILM");
                            });
            assertThat(responses)
                    .extracting(ReportGenerateResponse::reportId)
                    .containsOnly(responses.getFirst().reportId());
            assertThat(responses.getFirst().reportId()).isNotEqualTo(first.reportId());
        }
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void correctedDisplayNameDoesNotInsertAnIdenticalReport() throws Exception {
        frames(20);
        long personId = BASE + 60;
        jdbc.update(
                "INSERT INTO people (id, tmdb_person_id, name) VALUES (?, ?, '마크 해밀')",
                personId,
                personId);
        jdbc.update(
                "INSERT INTO movie_people (movie_id, person_id, role) VALUES (?, ?, 'ACTOR')",
                BASE + 1,
                personId);
        try {
            var first = manual.generate(OWNER);
            String original = content(first.reportId());
            jdbc.update("UPDATE people SET original_name = 'Mark Hamill' WHERE id = ?", personId);
            jdbc.update("UPDATE ratings SET comment = '한줄평 변경' WHERE id = ?", BASE + 1);
            mockMvc.perform(
                            get("/api/v1/reports/{id}", first.reportId())
                                    .header("Authorization", bearer(OWNER)))
                    .andExpect(jsonPath("$.fourCut.actor.displayName").value("Mark Hamill"));
            var same = manual.generate(OWNER);
            assertThat(same.reportId()).isEqualTo(first.reportId());
            assertThat(same.reuseReason()).isEqualTo("SAME_CONTENT");
            assertThat(content(first.reportId())).isEqualTo(original);
            assertThat(count()).isOne();
            // Clearing a corrected classification must also compare the displayed result.
            jdbc.update("UPDATE people SET original_name = NULL WHERE id = ?", personId);
            assertThat(manual.generate(OWNER).reuseReason()).isEqualTo("SAME_CONTENT");
        } finally {
            jdbc.update("DELETE FROM movie_people WHERE person_id = ?", personId);
            jdbc.update("DELETE FROM people WHERE id = ?", personId);
        }
    }

    @Test
    void staleSameContentSnapshotRetriesLatestWinner() throws Exception {
        frames(20);
        var first = manual.generate(OWNER);
        jdbc.update("UPDATE ratings SET comment = '동일 분석' WHERE id = ?", BASE + 1);
        var repository = spy(new ReportManualRepository(jdbc));
        var acquiredSnapshot = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        doAnswer(
                        call -> {
                            if (attempts.incrementAndGet() == 1) {
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM taste_reports WHERE user_id = ?",
                                        Integer.class,
                                        OWNER);
                                acquiredSnapshot.countDown();
                                assertThat(winnerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                            }
                            return call.callRealMethod();
                        })
                .when(repository)
                .tryLock(OWNER);
        var racing = serviceWith(repository);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delayed = executor.submit(() -> racing.generate(OWNER));
            try {
                assertThat(acquiredSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
                frame(21);
                jdbc.update(
                        "UPDATE movies SET release_date = DATE '2000-01-01' WHERE id = ?",
                        BASE + 21);
                var winner = manual.generate(OWNER);
                assertThat(winner.reportId()).isNotEqualTo(first.reportId());
                winnerCommitted.countDown();
                assertThat(delayed.get(10, TimeUnit.SECONDS).reportId())
                        .isEqualTo(winner.reportId());
                assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
            } finally {
                winnerCommitted.countDown();
            }
        }
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void emptyChangedFilmIsHandled() throws Exception {
        frames(20);
        manual.generate(OWNER);
        jdbc.update("DELETE FROM frames WHERE user_id = ?", OWNER);
        var empty = manual.generate(OWNER);
        mockMvc.perform(
                        get("/api/v1/reports/{id}", empty.reportId())
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ratingCount").value(0))
                .andExpect(jsonPath("$.fourCut.genre").isEmpty());
        assertThat(manual.generate(OWNER).reportId()).isEqualTo(empty.reportId());
    }

    @Test
    void concurrentGenerationIsIdempotent() throws Exception {
        frames(20);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> manual.generate(OWNER));
            var b = executor.submit(() -> manual.generate(OWNER));
            assertThat(a.get().reportId()).isEqualTo(b.get().reportId());
        }
        assertThat(count()).isOne();
    }

    @Test
    void staleSnapshotBeforeWinningCommitRetriesAndReusesWinner() throws Exception {
        frames(20);
        var repository = spy(new ReportManualRepository(jdbc));
        var acquiredSnapshot = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        doAnswer(
                        call -> {
                            if (attempts.incrementAndGet() == 1) {
                                // Fix the SERIALIZABLE snapshot before the other request's
                                // INSERT/commit.
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM taste_reports WHERE user_id = ?",
                                        Integer.class,
                                        OWNER);
                                acquiredSnapshot.countDown();
                                assertThat(winnerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                            }
                            return call.callRealMethod();
                        })
                .when(repository)
                .tryLock(OWNER);
        var racing = serviceWith(repository);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delayed = executor.submit(() -> racing.generate(OWNER));
            try {
                assertThat(acquiredSnapshot.await(10, TimeUnit.SECONDS)).isTrue();
                var winner = manual.generate(OWNER);
                winnerCommitted.countDown();
                var retried = delayed.get(10, TimeUnit.SECONDS);
                assertThat(retried.reportId()).isEqualTo(winner.reportId());
                assertThat(retried.reused()).isTrue();
                assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
            } finally {
                winnerCommitted.countDown();
            }
        }
        assertThat(count()).isOne();
    }

    @Test
    void filmWriteBetweenFingerprintAndSourcesKeepsConsistentSnapshot() throws Exception {
        frames(20);
        jdbc.update("UPDATE movies SET release_date = DATE '2000-01-01' WHERE id = ?", BASE + 1);
        var repository = spy(new ReportManualRepository(jdbc));
        var fingerprintRead = new CountDownLatch(1);
        var writeCommitted = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            var state = call.callRealMethod();
                            fingerprintRead.countDown();
                            assertThat(writeCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                            return state;
                        })
                .when(repository)
                .filmState(OWNER);
        var racing = serviceWith(repository);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> racing.generate(OWNER));
            try {
                assertThat(fingerprintRead.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.update("DELETE FROM frames WHERE id = ?", BASE + 1);
                jdbc.update("UPDATE ratings SET score = 2.0 WHERE id = ?", BASE + 2);
                writeCommitted.countDown();
                var original = pending.get(10, TimeUnit.SECONDS);
                mockMvc.perform(
                                get("/api/v1/reports/{id}", original.reportId())
                                        .header("Authorization", bearer(OWNER)))
                        .andExpect(jsonPath("$.summary.ratingCount").value(20))
                        .andExpect(jsonPath("$.summary.averageScore").value(4.0));
                var changed = manual.generate(OWNER);
                assertThat(changed.reportId()).isNotEqualTo(original.reportId());
                mockMvc.perform(
                                get("/api/v1/reports/{id}", changed.reportId())
                                        .header("Authorization", bearer(OWNER)))
                        .andExpect(jsonPath("$.summary.ratingCount").value(19));
            } finally {
                writeCommitted.countDown();
            }
        }
    }

    @Test
    void concurrentIssueReturnsSingleStoredCompletion() throws Exception {
        frames(20);
        var created = manual.generate(OWNER);
        var read = context.getBean(ReportReadService.class);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return read.issue(OWNER, created.reportId());
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return read.issue(OWNER, created.reportId());
                            });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).issuedAt())
                    .isEqualTo(b.get(10, TimeUnit.SECONDS).issuedAt());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM taste_reports WHERE user_id = ? AND issued_at IS NOT NULL",
                                Integer.class,
                                OWNER))
                .isOne();
    }

    private ReportManualService serviceWith(ReportManualRepository repository) {
        return serviceWith(repository, context.getBean(ReportContentAssembler.class));
    }

    private ReportManualService serviceWith(
            ReportManualRepository repository, ReportContentAssembler assembler) {
        return new ReportManualService(
                repository,
                context.getBean(
                        com.feelm.backend.domain.report.repository.ReportReadRepository.class),
                context.getBean(ReportGenerationRepository.class),
                assembler,
                context.getBean(ObjectMapper.class),
                clock,
                context.getBean(PlatformTransactionManager.class));
    }

    @Test
    void failedGenerationRollsBackAndCanRetry() {
        frames(20);
        jdbc.execute(
                """
                CREATE FUNCTION test_manual_report_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.user_id = 5560001 THEN RAISE EXCEPTION 'test insert failure'; END IF;
                RETURN NEW; END $$
                """);
        jdbc.execute(
                "CREATE TRIGGER test_manual_report_failure BEFORE INSERT ON taste_reports FOR EACH ROW EXECUTE FUNCTION test_manual_report_failure()");
        try {
            assertThatThrownBy(() -> manual.generate(OWNER))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_manual_report_failure ON taste_reports");
            jdbc.execute("DROP FUNCTION test_manual_report_failure()");
        }
        assertThat(manual.generate(OWNER).reused()).isFalse();
    }

    @Test
    void manualIssueHasNoSemesterWindowAndListsMultipleSnapshots() throws Exception {
        frames(20);
        clock.set(Instant.parse("2030-06-30T14:55:00Z"));
        for (int i = 0; i < 5; i++) {
            jdbc.update("UPDATE ratings SET score = ? WHERE id = ?", 2.0 + i * 0.5, BASE + 1);
            jdbc.update(
                    "UPDATE movies SET release_date = ? WHERE id = ?",
                    java.sql.Date.valueOf((1990 + i * 10) + "-01-01"),
                    BASE + 1);
            var result = manual.generate(OWNER);
            assertThat(period(result)).isEqualTo("H1");
            mockMvc.perform(
                            post("/api/v1/reports/{id}/issue", result.reportId())
                                    .header("Authorization", bearer(OWNER)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)));
        var latest = manual.generate(OWNER);
        clock.set(RELEASE);
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", latest.reportId())
                                .header("Authorization", bearer(OWNER)))
                .andExpect(jsonPath("$.issuedAt").value("2030-06-30T14:55:00Z"));
        frame(21);
        jdbc.update("UPDATE movies SET release_date = DATE '2040-01-01' WHERE id = ?", BASE + 21);
        assertThat(period(manual.generate(OWNER))).isEqualTo("H2");
    }

    @Test
    void ownerIsolationAndLegacyReportsRemainReadable() throws Exception {
        frames(20);
        var fresh = manual.generate(OWNER);
        mockMvc.perform(post("/api/v1/users/me/reports")).andExpect(status().isUnauthorized());
        for (String endpoint :
                new String[] {"/api/v1/reports/" + fresh.reportId(), "/api/v1/reports/999999999"}) {
            mockMvc.perform(get(endpoint).header("Authorization", bearer(OTHER)))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", fresh.reportId())
                                .header("Authorization", bearer(OTHER)))
                .andExpect(status().isNotFound());
        report(BASE + 50, OWNER, 2029, "H1", "READY");
        mockMvc.perform(
                        get("/api/v1/reports/{id}", BASE + 50)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisScope").value("HALF"))
                .andExpect(jsonPath("$.summary.ratingCount").value(3));
    }

    @Test
    void cumulativeComparisonUsesPreviousSnapshot() throws Exception {
        frames(20);
        for (int flavor = 1; flavor <= 2; flavor++) {
            jdbc.update(
                    "INSERT INTO popcorn_flavors"
                            + " (id,code,display_name,color,description,sort_order,cluster_id,representative_keywords)"
                            + " VALUES (?, ?, '테스트 맛', '#111111', '테스트', ?, ?, '[]'::jsonb)",
                    BASE + flavor,
                    "R930_" + flavor,
                    BASE + flavor,
                    BASE + flavor);
        }
        jdbc.update(
                "UPDATE movies SET representative_flavor_id = ? WHERE id BETWEEN ? AND ?",
                BASE + 1,
                BASE,
                BASE + 100);
        var first = manual.generate(OWNER);
        frame(21);
        jdbc.update(
                "UPDATE movies SET representative_flavor_id = ? WHERE id = ?", BASE + 2, BASE + 21);
        var next = manual.generate(OWNER);
        mockMvc.perform(
                        get("/api/v1/reports/{id}", next.reportId())
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ratingCount").value(21))
                .andExpect(jsonPath("$.summary.newFlavorCount").value(1))
                .andExpect(jsonPath("$.comparisons[?(@.isNew == true)]", hasSize(1)));
        assertThat(content(first.reportId())).contains("\"ratingCount\": 20");
    }

    @Test
    void manualReportImageIsImmediatelyAvailableAndOwnerOnly() throws Exception {
        frames(20);
        clock.set(Instant.parse("2030-05-01T00:00:00Z"));
        var result = manual.generate(OWNER);
        var image =
                new java.awt.image.BufferedImage(
                        1080, 3268, java.awt.image.BufferedImage.TYPE_INT_RGB);
        byte[] png;
        try (var output = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            png = output.toByteArray();
        }
        mockMvc.perform(
                        post("/api/v1/reports/{id}/image", result.reportId())
                                .header("Authorization", bearer(OTHER))
                                .contentType("image/png")
                                .content(png))
                .andExpect(status().isNotFound());
        mockMvc.perform(
                        post("/api/v1/reports/{id}/image", result.reportId())
                                .header("Authorization", bearer(OWNER))
                                .contentType("image/png")
                                .content(png))
                .andExpect(status().isCreated());
        frame(21);
        manual.generate(OWNER);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM report_images WHERE report_id = ?",
                                Integer.class,
                                result.reportId()))
                .isOne();
    }

    @Test
    void noAutomaticSemesterOverwrite() {
        assertThat(
                        ReportGenerationScheduler.class.isAnnotationPresent(
                                org.springframework.stereotype.Component.class))
                .isFalse();
        assertThat(
                        Arrays.stream(ReportGenerationScheduler.class.getDeclaredMethods())
                                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class)))
                .isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions generate() throws Exception {
        return mockMvc.perform(
                post("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)));
    }

    private int count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM taste_reports WHERE user_id = ?", Integer.class, OWNER);
    }

    private String content(long id) {
        return jdbc.queryForObject(
                "SELECT content::text FROM taste_reports WHERE id = ?", String.class, id);
    }

    private String period(ReportGenerateResponse result) {
        return jdbc.queryForObject(
                "SELECT half FROM taste_reports WHERE id = ?", String.class, result.reportId());
    }

    private void frames(int count) {
        for (int i = 1; i <= count; i++) frame(i);
    }

    private void frame(int suffix) {
        long id = BASE + suffix;
        var at =
                Timestamp.from(
                        Instant.parse(
                                suffix % 2 == 0 ? "2029-01-01T00:00:00Z" : "2030-01-01T00:00:00Z"));
        jdbc.update(
                "INSERT INTO movies (id, tmdb_id, title) VALUES (?, ?, ?)",
                id,
                id,
                "리포트 요청 영화 " + suffix);
        jdbc.update(
                "INSERT INTO ratings (id, user_id, movie_id, score, visibility, created_at, updated_at) VALUES (?, ?, ?, 4.0, 'PUBLIC', ?, ?)",
                id,
                OWNER,
                id,
                at,
                at);
        jdbc.update(
                "INSERT INTO frames (id, rating_id, user_id, movie_id, created_at) VALUES (?, ?, ?, ?, ?)",
                id,
                id,
                OWNER,
                id,
                at);
    }
}
