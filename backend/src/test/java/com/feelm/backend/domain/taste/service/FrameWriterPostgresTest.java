package com.feelm.backend.domain.taste.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.taste.repository.FrameQueryRepository;
import com.feelm.backend.domain.taste.service.FrameWriter.FrameCreation;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("프레임 저장 — 실제 PostgreSQL 계약")
class FrameWriterPostgresTest extends SharedPostgresContractTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-16T01:23:45.123456Z");
    private static final long FIXTURE_ID_MIN = 4_700_000L;
    private static final long FIXTURE_ID_MAX = 4_700_999L;

    @Autowired private FrameWriter frameWriter;
    @Autowired private FrameQueryRepository frameQueryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void cleanFixtureRange() {
        jdbcTemplate.update(
                "DELETE FROM frames WHERE rating_id BETWEEN ? AND ?",
                FIXTURE_ID_MIN,
                FIXTURE_ID_MAX);
        jdbcTemplate.update(
                "DELETE FROM ratings WHERE id BETWEEN ? AND ?", FIXTURE_ID_MIN, FIXTURE_ID_MAX);
        jdbcTemplate.update(
                "DELETE FROM movies WHERE id BETWEEN ? AND ?", FIXTURE_ID_MIN, FIXTURE_ID_MAX);
        jdbcTemplate.update(
                "DELETE FROM users WHERE id BETWEEN ? AND ?", FIXTURE_ID_MIN, FIXTURE_ID_MAX);
    }

    @Test
    @DisplayName("프레임 저장은 호출자의 기존 트랜잭션 안에서만 실행한다")
    void requiresCallerTransaction() {
        TestIds ids = ids(0);

        assertThatThrownBy(
                        () ->
                                frameWriter.create(
                                        ids.ratingId(), ids.userId(), ids.movieId(), CREATED_AT))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional
    @DisplayName("평가와 사용자·영화가 모두 일치하면 프레임 한 건을 생성한다")
    void createsFrameForMatchingRating() {
        TestIds ids = insertRatingFixture(1);

        FrameCreation created =
                frameWriter.create(ids.ratingId(), ids.userId(), ids.movieId(), CREATED_AT);

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT id, rating_id, user_id, movie_id, created_at FROM frames WHERE id = ?",
                        created.frameId());
        assertThat(((Number) row.get("rating_id")).longValue()).isEqualTo(ids.ratingId());
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(ids.userId());
        assertThat(((Number) row.get("movie_id")).longValue()).isEqualTo(ids.movieId());
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(CREATED_AT);
        assertThat(created.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @Transactional
    @DisplayName("존재하지 않는 평가로는 프레임을 만들지 않는다")
    void rejectsMissingRatingWithoutPartialFrame() {
        TestIds ids = ids(2);

        assertThatThrownBy(
                        () ->
                                frameWriter.create(
                                        ids.ratingId(), ids.userId(), ids.movieId(), CREATED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(frameCount(ids.ratingId())).isZero();
    }

    @Test
    @Transactional
    @DisplayName("평가 소유자와 다른 사용자로는 프레임을 만들지 않는다")
    void rejectsMismatchedUserWithoutPartialFrame() {
        TestIds ids = insertRatingFixture(3);

        assertThatThrownBy(
                        () ->
                                frameWriter.create(
                                        ids.ratingId(),
                                        ids.userId() + 1,
                                        ids.movieId(),
                                        CREATED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(frameCount(ids.ratingId())).isZero();
    }

    @Test
    @Transactional
    @DisplayName("평가 대상과 다른 영화로는 프레임을 만들지 않는다")
    void rejectsMismatchedMovieWithoutPartialFrame() {
        TestIds ids = insertRatingFixture(4);

        assertThatThrownBy(
                        () ->
                                frameWriter.create(
                                        ids.ratingId(),
                                        ids.userId(),
                                        ids.movieId() + 1,
                                        CREATED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(frameCount(ids.ratingId())).isZero();
    }

    @Test
    @Transactional
    @DisplayName("한 평가에는 프레임을 한 건만 생성한다")
    void rejectsDuplicateRatingFrame() {
        TestIds ids = insertRatingFixture(5);
        frameWriter.create(ids.ratingId(), ids.userId(), ids.movieId(), CREATED_AT);

        assertThatThrownBy(
                        () ->
                                frameWriter.create(
                                        ids.ratingId(), ids.userId(), ids.movieId(), CREATED_AT))
                .isInstanceOf(IllegalStateException.class);

        assertThat(frameCount(ids.ratingId())).isOne();
    }

    @Test
    @DisplayName("독립 트랜잭션이 동시에 저장해도 한 평가에는 프레임 하나만 남는다")
    void concurrentTransactionsCreateOnlyOneFrame() throws Exception {
        TestIds ids = ids(8);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(ignored -> insertRatingFixture(ids));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> first = workers.submit(() -> createConcurrently(ids, ready, start));
            Future<Boolean> second = workers.submit(() -> createConcurrently(ids, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(frameCount(ids.ratingId())).isOne();
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("호출자가 중복 예외를 잡아도 전체 트랜잭션은 커밋되지 않는다")
    void caughtWriterFailureMarksCallerTransactionRollbackOnly() {
        TestIds ids = ids(9);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        ignored -> {
                                            insertRatingFixture(ids);
                                            frameWriter.create(
                                                    ids.ratingId(),
                                                    ids.userId(),
                                                    ids.movieId(),
                                                    CREATED_AT);
                                            try {
                                                frameWriter.create(
                                                        ids.ratingId(),
                                                        ids.userId(),
                                                        ids.movieId(),
                                                        CREATED_AT);
                                            } catch (IllegalStateException expected) {
                                                // 호출자가 예외를 삼켜도 MANDATORY writer가 트랜잭션을
                                                // rollback-only로 표시해야 한다.
                                            }
                                        }))
                .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(frameCount(ids.ratingId())).isZero();
        assertThat(ratingCount(ids.ratingId())).isZero();
    }

    @Test
    @Transactional
    @DisplayName("서브마이크로초 시각은 PostgreSQL 정밀도에 맞춰 내림 절삭한다")
    void truncatesSubMicrosecondTimeBeforePersisting() {
        TestIds ids = insertRatingFixture(10);
        Instant input = Instant.parse("2026-09-16T01:23:45.123456700Z");
        Instant expected = Instant.parse("2026-09-16T01:23:45.123456Z");

        FrameCreation created =
                frameWriter.create(ids.ratingId(), ids.userId(), ids.movieId(), input);
        Instant stored =
                jdbcTemplate
                        .queryForObject(
                                "SELECT created_at FROM frames WHERE id = ?",
                                Timestamp.class,
                                created.frameId())
                        .toInstant();

        assertThat(stored).isEqualTo(expected);
        assertThat(created.createdAt()).isEqualTo(expected);
    }

    @Test
    @DisplayName("호출자 트랜잭션이 실패하면 평가와 프레임을 함께 롤백한다")
    void rollsBackWithCallerTransaction() {
        TestIds ids = ids(6);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        ignored -> {
                                            insertRatingFixture(ids);
                                            frameWriter.create(
                                                    ids.ratingId(),
                                                    ids.userId(),
                                                    ids.movieId(),
                                                    CREATED_AT);
                                            throw new ForcedRollbackException();
                                        }))
                .isInstanceOf(ForcedRollbackException.class);

        assertThat(frameCount(ids.ratingId())).isZero();
        assertThat(ratingCount(ids.ratingId())).isZero();
    }

    @Test
    @Transactional
    @DisplayName("생성한 프레임은 기존 목록과 상세 조회에서 즉시 보인다")
    void exposesWrittenFrameToExistingQueries() {
        TestIds ids = insertRatingFixture(7);
        FrameCreation created =
                frameWriter.create(ids.ratingId(), ids.userId(), ids.movieId(), CREATED_AT);

        var frames = frameQueryRepository.findFrames(ids.userId(), null, null, 10);
        var detail = frameQueryRepository.findDetail(ids.userId(), created.frameId());

        assertThat(frames)
                .singleElement()
                .satisfies(
                        frame -> {
                            assertThat(frame.frameId()).isEqualTo(created.frameId());
                            assertThat(frame.movieId()).isEqualTo(ids.movieId());
                            assertThat(frame.score()).isEqualByComparingTo("4.5");
                            assertThat(frame.createdAt()).isEqualTo(CREATED_AT);
                        });
        assertThat(detail)
                .hasValueSatisfying(
                        frame -> {
                            assertThat(frame.frameId()).isEqualTo(created.frameId());
                            assertThat(frame.ratingId()).isEqualTo(ids.ratingId());
                            assertThat(frame.movieId()).isEqualTo(ids.movieId());
                        });
    }

    private TestIds insertRatingFixture(int suffix) {
        TestIds ids = ids(suffix);
        insertRatingFixture(ids);
        return ids;
    }

    private void insertRatingFixture(TestIds ids) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, nickname, birth_date, gender, status) VALUES (?, ?, ?, DATE '2000-01-01', 'OTHER', 'ACTIVE')",
                ids.userId(),
                "frame-" + ids.userId() + "@test.local",
                "frame" + ids.userId());
        jdbcTemplate.update(
                "INSERT INTO movies (id, tmdb_id, title) VALUES (?, ?, ?)",
                ids.movieId(),
                Math.toIntExact(ids.movieId()),
                "프레임 테스트 영화 " + ids.movieId());
        jdbcTemplate.update(
                "INSERT INTO ratings (id, user_id, movie_id, score, visibility, created_at, updated_at) VALUES (?, ?, ?, ?, 'PUBLIC', ?, ?)",
                ids.ratingId(),
                ids.userId(),
                ids.movieId(),
                new BigDecimal("4.5"),
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT));
    }

    private TestIds ids(int suffix) {
        long base = 4_700_000L + suffix * 10L;
        return new TestIds(base + 1, base + 2, base + 3);
    }

    private long frameCount(long ratingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM frames WHERE rating_id = ?", Long.class, ratingId);
    }

    private long ratingCount(long ratingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ratings WHERE id = ?", Long.class, ratingId);
    }

    private boolean createConcurrently(TestIds ids, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 저장 시작 신호를 받지 못했습니다.");
        }
        try {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            ignored ->
                                    frameWriter.create(
                                            ids.ratingId(),
                                            ids.userId(),
                                            ids.movieId(),
                                            CREATED_AT));
            return true;
        } catch (IllegalStateException duplicate) {
            return false;
        }
    }

    private record TestIds(long userId, long movieId, long ratingId) {}

    private static final class ForcedRollbackException extends RuntimeException {}
}
