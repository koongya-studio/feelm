package com.feelm.backend.domain.taste.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import com.feelm.backend.domain.taste.repository.TasteAnalysisRepository;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 취향 분석 캐시 재계산 — 실제 PostgreSQL 계약(S15P21E106-775).
 *
 * <p>JSONB 매핑과 집계 SQL을 함께 보므로 H2가 아닌 실제 서버가 필요하다. 계약(API-040)이 요구하는 전제 — 배열을 자르지 않음, 연대 라벨 형식, 비중
 * 분모, 이름 채우기 — 를 하나씩 고정한다.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("취향 분석 캐시 재계산 — API-040 계약")
class TasteAnalysisWriterPostgresTest extends SharedPostgresContractTest {

    private static final long ID_MIN = 4_775_000L;
    private static final long ID_MAX = 4_775_999L;
    private static final long USER_ID = 4_775_001L;
    private static final String GENRE_CODE_PREFIX = "TASTE_775_";
    private static final Instant ANALYZED_AT = Instant.parse("2026-09-24T01:23:45.123456Z");

    @Autowired private TasteAnalysisWriter tasteAnalysisWriter;
    @Autowired private TasteAnalysisRepository tasteAnalysisRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void cleanFixtureRange() {
        jdbcTemplate.update(
                "DELETE FROM taste_analyses WHERE user_id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update("DELETE FROM ratings WHERE user_id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update(
                "DELETE FROM movie_people WHERE movie_id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update(
                "DELETE FROM movie_countries WHERE movie_id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update(
                "DELETE FROM movie_genres WHERE movie_id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update("DELETE FROM movies WHERE id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update("DELETE FROM people WHERE id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update("DELETE FROM genres WHERE id BETWEEN ? AND ?", ID_MIN, ID_MAX);
        jdbcTemplate.update("DELETE FROM users WHERE id BETWEEN ? AND ?", ID_MIN, ID_MAX);
    }

    @Test
    @DisplayName("T1 첫 평가에서 캐시 행이 생기고 4축이 채워진다")
    void createsAnalysisOnFirstRating() {
        insertUser();
        insertGenre(ID_MIN + 100, "THRILLER", "스릴러");
        insertMovie(ID_MIN + 200, "곡성", LocalDate.of(2016, 5, 11));
        linkGenre(ID_MIN + 200, ID_MIN + 100);
        linkCountry(ID_MIN + 200, "KR");
        insertPerson(ID_MIN + 300, "나홍진");
        linkDirector(ID_MIN + 200, ID_MIN + 300);
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.5");

        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getRatingCount()).isEqualTo(1);
        assertThat(saved.getAnalyzedAt()).isEqualTo(ANALYZED_AT);
        assertThat(saved.getAnalysis().genres())
                .singleElement()
                .satisfies(
                        genre -> {
                            assertThat(genre.code()).isEqualTo(GENRE_CODE_PREFIX + "THRILLER");
                            assertThat(genre.name()).isEqualTo("스릴러");
                            assertThat(genre.count()).isEqualTo(1);
                            assertThat(genre.averageScore()).isEqualByComparingTo("4.5");
                            assertThat(genre.share()).isEqualByComparingTo("1.00");
                        });
        assertThat(saved.getAnalysis().eras())
                .singleElement()
                .satisfies(era -> assertThat(era.label()).isEqualTo("2010s"));
        assertThat(saved.getAnalysis().directors())
                .singleElement()
                .satisfies(director -> assertThat(director.name()).isEqualTo("나홍진"));
        // 평가 1편은 최소 평가 수에 못 미쳐 키워드가 없다.
        assertThat(saved.getKeywords()).isEmpty();
    }

    @Test
    @DisplayName("T3 평가를 지우면 그 영화의 기여가 빠진다")
    void recomputesAfterRatingRemoved() {
        insertUser();
        insertGenre(ID_MIN + 100, "THRILLER", "스릴러");
        insertGenre(ID_MIN + 101, "COMEDY", "코미디");
        insertMovie(ID_MIN + 200, "곡성", LocalDate.of(2016, 5, 11));
        insertMovie(ID_MIN + 201, "극한직업", LocalDate.of(2019, 1, 23));
        linkGenre(ID_MIN + 200, ID_MIN + 100);
        linkGenre(ID_MIN + 201, ID_MIN + 101);
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.5");
        insertRating(ID_MIN + 401, ID_MIN + 201, "3.0");
        refresh();
        assertThat(loadSaved().getAnalysis().genres()).hasSize(2);

        jdbcTemplate.update("DELETE FROM ratings WHERE id = ?", ID_MIN + 401);
        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getRatingCount()).isEqualTo(1);
        assertThat(saved.getAnalysis().genres())
                .singleElement()
                .satisfies(
                        genre ->
                                assertThat(genre.code()).isEqualTo(GENRE_CODE_PREFIX + "THRILLER"));
    }

    @Test
    @DisplayName("T4 평가가 하나도 남지 않으면 행을 지운다")
    void removesRowWhenAllRatingsDeleted() {
        insertUser();
        insertMovie(ID_MIN + 200, "곡성", LocalDate.of(2016, 5, 11));
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.5");
        refresh();
        assertThat(findSaved()).isPresent();

        jdbcTemplate.update("DELETE FROM ratings WHERE user_id = ?", USER_ID);
        refresh();

        assertThat(findSaved()).isEmpty();
    }

    @Test
    @DisplayName("T7 장르와 국가는 나타난 종류를 전부 담는다")
    void aggregatesAllKindsWithoutTruncation() {
        insertUser();
        String[] countries = {"KR", "US", "JP", "FR", "GB", "DE", "IT"};
        for (int i = 0; i < 12; i++) {
            insertGenre(ID_MIN + 100 + i, "G%02d".formatted(i), "장르" + i);
            insertMovie(ID_MIN + 200 + i, "영화" + i, LocalDate.of(2016, 5, 11));
            linkGenre(ID_MIN + 200 + i, ID_MIN + 100 + i);
            linkCountry(ID_MIN + 200 + i, countries[i % countries.length]);
            insertRating(ID_MIN + 400 + i, ID_MIN + 200 + i, "4.0");
        }

        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getAnalysis().genres()).hasSize(12);
        assertThat(saved.getAnalysis().countries()).hasSize(7);
    }

    @Test
    @DisplayName("T8 연대는 10년 버킷을 2010s 형식으로 쓰고 합치지 않는다")
    void bucketsErasByDecadeWithoutMerging() {
        insertUser();
        insertMovie(ID_MIN + 200, "괴물", LocalDate.of(2006, 7, 27));
        insertMovie(ID_MIN + 201, "살인의 추억", LocalDate.of(2003, 4, 25));
        insertMovie(ID_MIN + 202, "초록물고기", LocalDate.of(1997, 2, 7));
        insertMovie(ID_MIN + 203, "개봉일 없음", null);
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.0");
        insertRating(ID_MIN + 401, ID_MIN + 201, "4.0");
        insertRating(ID_MIN + 402, ID_MIN + 202, "4.0");
        insertRating(ID_MIN + 403, ID_MIN + 203, "4.0");

        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getAnalysis().eras())
                .extracting(TasteAnalysis.Era::label)
                .containsExactlyInAnyOrder("2000s", "1990s");
        // 개봉일 없는 영화는 어느 버킷에도 없지만 분모에는 남는다.
        assertThat(saved.getRatingCount()).isEqualTo(4);
        assertThat(saved.getAnalysis().eras())
                .filteredOn(era -> era.label().equals("2000s"))
                .singleElement()
                .satisfies(era -> assertThat(era.share()).isEqualByComparingTo("0.50"));
    }

    @Test
    @DisplayName("T9 비중의 분모는 평가 편수이고 장르 비중 합은 1을 넘을 수 있다")
    void usesRatingCountAsShareDenominator() {
        insertUser();
        insertGenre(ID_MIN + 100, "THRILLER", "스릴러");
        insertGenre(ID_MIN + 101, "DRAMA", "드라마");
        insertGenre(ID_MIN + 102, "CRIME", "범죄");
        insertMovie(ID_MIN + 200, "곡성", LocalDate.of(2016, 5, 11));
        linkGenre(ID_MIN + 200, ID_MIN + 100);
        linkGenre(ID_MIN + 200, ID_MIN + 101);
        linkGenre(ID_MIN + 200, ID_MIN + 102);
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.0");

        refresh();

        List<TasteAnalysis.Genre> genres = loadSaved().getAnalysis().genres();
        assertThat(genres).hasSize(3);
        assertThat(genres)
                .allSatisfy(genre -> assertThat(genre.share()).isEqualByComparingTo("1.00"));
        BigDecimal sum =
                genres.stream()
                        .map(TasteAnalysis.Genre::share)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("T10 감독만 상위 다섯 명으로 자른다")
    void keepsTopDirectorsOnly() {
        insertUser();
        for (int i = 0; i < 8; i++) {
            insertPerson(ID_MIN + 300 + i, "감독" + i);
            insertMovie(ID_MIN + 200 + i, "영화" + i, LocalDate.of(2016, 5, 11));
            linkDirector(ID_MIN + 200 + i, ID_MIN + 300 + i);
            insertRating(ID_MIN + 400 + i, ID_MIN + 200 + i, "4.0");
        }

        refresh();

        assertThat(loadSaved().getAnalysis().directors()).hasSize(5);
    }

    @Test
    @DisplayName("T11 국가 이름은 ISO 코드에서 한국어 표시명으로 채운다")
    void fillsCountryDisplayName() {
        insertUser();
        insertMovie(ID_MIN + 200, "곡성", LocalDate.of(2016, 5, 11));
        insertMovie(ID_MIN + 201, "인셉션", LocalDate.of(2010, 7, 21));
        linkCountry(ID_MIN + 200, "KR");
        linkCountry(ID_MIN + 201, "US");
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.0");
        insertRating(ID_MIN + 401, ID_MIN + 201, "4.0");

        refresh();

        assertThat(loadSaved().getAnalysis().countries())
                .extracting(TasteAnalysis.Country::code, TasteAnalysis.Country::name)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("KR", "한국"),
                        org.assertj.core.groups.Tuple.tuple("US", "미국"));
    }

    @Test
    @DisplayName("T11-2 ISO에 없는 역사 코드도 표시명으로 바꾼다")
    void mapsHistoricalCountryCodes() {
        insertUser();
        insertMovie(ID_MIN + 200, "전함 포템킨", LocalDate.of(1925, 12, 21));
        linkCountry(ID_MIN + 200, "SU");
        insertRating(ID_MIN + 400, ID_MIN + 200, "4.0");

        refresh();

        assertThat(loadSaved().getAnalysis().countries())
                .singleElement()
                .satisfies(country -> assertThat(country.name()).isEqualTo("소련"));
    }

    @Test
    @DisplayName("T12 평가가 최소 편수 이상이면 키워드가 채워진다")
    void fillsKeywordsFromSelector() {
        insertUser();
        insertGenre(ID_MIN + 100, "THRILLER", "스릴러");
        insertPerson(ID_MIN + 300, "봉준호");
        for (int i = 0; i < 16; i++) {
            insertMovie(ID_MIN + 200 + i, "영화" + i, LocalDate.of(2016, 5, 11));
            linkGenre(ID_MIN + 200 + i, ID_MIN + 100);
            linkCountry(ID_MIN + 200 + i, "KR");
            linkDirector(ID_MIN + 200 + i, ID_MIN + 300);
            insertRating(ID_MIN + 400 + i, ID_MIN + 200 + i, "4.0");
        }

        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getRatingCount()).isEqualTo(16);
        // 감독 몰아보기·장르 편식·국가 집중·연대 애호가가 모두 조건을 넘긴다.
        assertThat(saved.getKeywords())
                .extracting(TasteAnalysis.Keyword::code)
                .contains("DIRECTOR_BINGE", "GENRE_DEVOTEE", "COUNTRY_FOCUS", "ERA_LOVER");
        assertThat(saved.getKeywords())
                .extracting(TasteAnalysis.Keyword::label)
                .contains("봉준호 몰아보기", "스릴러 편식", "한국 영화 집중", "2010년대 애호가");
        // 같은 축의 넓이형은 배타 규칙으로 빠진다(S15P21E106-834).
        assertThat(saved.getKeywords())
                .extracting(TasteAnalysis.Keyword::code)
                .doesNotContain("GENRE_OMNIVORE", "WORLD_EXPLORER");
    }

    @Test
    @DisplayName("T13 평가가 최소 편수 미만이면 분석만 채우고 키워드는 비운다")
    void leavesKeywordsEmptyBelowMinimumRatings() {
        insertUser();
        insertGenre(ID_MIN + 100, "THRILLER", "스릴러");
        for (int i = 0; i < 14; i++) {
            insertMovie(ID_MIN + 200 + i, "영화" + i, LocalDate.of(2016, 5, 11));
            linkGenre(ID_MIN + 200 + i, ID_MIN + 100);
            insertRating(ID_MIN + 400 + i, ID_MIN + 200 + i, "4.0");
        }

        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getRatingCount()).isEqualTo(14);
        assertThat(saved.getAnalysis().genres()).isNotEmpty();
        assertThat(saved.getKeywords()).isEmpty();
    }

    @Test
    @DisplayName("T14 비중을 먼저 반올림해 고전 발굴가가 오발화하지 않는다")
    void doesNotMisfireClassicDiggerFromRounding() {
        insertUser();
        // 2000년 이전 비중 참값은 (19+19+19+2)/200 = 0.295로 임계 0.30에 미달이다.
        // 소수 두 자리로 먼저 반올림하면 0.10+0.10+0.10+0.01 = 0.31이 되어 발화한다.
        int[] counts = {19, 19, 19, 2, 141};
        int[] years = {1995, 1985, 1975, 1965, 2015};
        long movieId = ID_MIN + 100;
        long ratingId = ID_MIN + 100;
        for (int bucket = 0; bucket < counts.length; bucket++) {
            for (int i = 0; i < counts[bucket]; i++) {
                insertMovie(movieId, "영화" + movieId, LocalDate.of(years[bucket], 5, 11));
                insertRating(ratingId, movieId, "4.0");
                movieId++;
                ratingId++;
            }
        }

        refresh();

        TasteAnalysis saved = loadSaved();
        assertThat(saved.getRatingCount()).isEqualTo(200);
        assertThat(saved.getKeywords())
                .extracting(TasteAnalysis.Keyword::code)
                .doesNotContain("CLASSIC_DIGGER");
    }

    @Test
    @DisplayName("트랜잭션 없이 부르면 즉시 실패한다")
    void requiresCallerTransaction() {
        assertThatThrownBy(() -> tasteAnalysisWriter.refresh(USER_ID, ANALYZED_AT))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // --- 도우미 -----------------------------------------------------------

    private void refresh() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> tasteAnalysisWriter.refresh(USER_ID, ANALYZED_AT));
    }

    private TasteAnalysis loadSaved() {
        return findSaved().orElseThrow(() -> new AssertionError("취향 분석 캐시가 없습니다."));
    }

    private Optional<TasteAnalysis> findSaved() {
        return new TransactionTemplate(transactionManager)
                .execute(status -> tasteAnalysisRepository.findByUserId(USER_ID));
    }

    private void insertUser() {
        jdbcTemplate.update(
                """
                INSERT INTO users (id, email, nickname, birth_date, gender, status)
                VALUES (?, ?, ?, DATE '1996-01-01', 'OTHER', 'ACTIVE')
                """,
                USER_ID,
                "taste-775-" + USER_ID + "@example.com",
                "취향775");
    }

    private void insertGenre(long id, String code, String name) {
        jdbcTemplate.update(
                "INSERT INTO genres (id, code, name, tmdb_genre_id) VALUES (?, ?, ?, ?)",
                id,
                GENRE_CODE_PREFIX + code,
                name,
                (int) id);
    }

    private void insertMovie(long id, String title, LocalDate releaseDate) {
        jdbcTemplate.update(
                "INSERT INTO movies (id, tmdb_id, title, release_date) VALUES (?, ?, ?, ?)",
                id,
                (int) id,
                title,
                releaseDate == null ? null : java.sql.Date.valueOf(releaseDate));
    }

    private void insertPerson(long id, String name) {
        jdbcTemplate.update(
                "INSERT INTO people (id, tmdb_person_id, name) VALUES (?, ?, ?)",
                id,
                (int) id,
                name);
    }

    private void linkGenre(long movieId, long genreId) {
        jdbcTemplate.update(
                "INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId, genreId);
    }

    private void linkCountry(long movieId, String countryCode) {
        jdbcTemplate.update(
                "INSERT INTO movie_countries (movie_id, country_code) VALUES (?, ?)",
                movieId,
                countryCode);
    }

    private void linkDirector(long movieId, long personId) {
        jdbcTemplate.update(
                "INSERT INTO movie_people (movie_id, person_id, role) VALUES (?, ?, 'DIRECTOR')",
                movieId,
                personId);
    }

    private void insertRating(long id, long movieId, String score) {
        jdbcTemplate.update(
                "INSERT INTO ratings (id, user_id, movie_id, score) VALUES (?, ?, ?, ?)",
                id,
                USER_ID,
                movieId,
                new BigDecimal(score));
    }
}
