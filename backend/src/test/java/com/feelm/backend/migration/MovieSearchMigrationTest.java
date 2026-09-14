package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.search.dto.request.MovieSearchCriteria;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ADR-0010과 157 플랜의 검색 GIN 인덱스를 실제 Flyway 마이그레이션으로 검증한다.
 *
 * <p>테스트는 인덱스를 생성하지 않는다. 작은 fixture의 실행 계획은 인덱스 사용 가능성을 검증할 뿐, 156 실데이터의 실행 계획·응답 시간 완료 조건을 대신하지
 * 않는다.
 */
@DisplayName("영화 검색 마이그레이션 — GIN 인덱스와 검색 실행 계획")
class MovieSearchMigrationTest {

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("movie_search");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target("202609121200")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .load()
                .migrate();
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword()));
        jdbc.update(
                """
                INSERT INTO movies (id, tmdb_id, title, original_title)
                SELECT n, 900000 + n, md5(n::text), md5((n + 30000)::text)
                FROM generate_series(1, 10000) n
                """);
        jdbc.update(
                """
                UPDATE movies SET title = 'UniqueNeedle movie', original_title = 'OriginalNeedle film'
                WHERE id = 1
                """);
        jdbc.update(
                """
                INSERT INTO people (id, tmdb_person_id, name)
                SELECT n, 900000 + n, md5(n::text) FROM generate_series(1, 10000) n
                """);
        jdbc.update("UPDATE people SET name = 'PersonNeedle actor' WHERE id = 1");
        jdbc.execute("ANALYZE movies");
        jdbc.execute("ANALYZE people");
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("최신 develop DB 다음에 검색 마이그레이션 3개를 순서대로 적용한다")
    void upgradesLatestDevelopDatabaseInOrder() {
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT version
                                FROM flyway_schema_history
                                WHERE version LIKE '20260914%'
                                ORDER BY installed_rank
                                """,
                                String.class))
                .containsExactly("202609140100", "202609140110", "202609140120");
    }

    @ParameterizedTest
    @CsvSource({"movies,title", "movies,original_title", "people,name"})
    @DisplayName("제목·원제·인물명의 검색 정규화 함수식에 gin_trgm_ops 인덱스가 생성된다")
    void createsTrigramGinForNormalizedColumn(String table, String column) {
        assertThat(indexes(table))
                .anySatisfy(
                        index -> {
                            assertThat(index.method()).isEqualTo("gin");
                            assertThat(index.expression())
                                    .isEqualTo("movie_search_normalize(" + column + ")");
                            assertThat(index.definition()).contains("gin_trgm_ops");
                            assertThat(index.predicate()).isNull();
                            assertThat(index.valid()).isTrue();
                        });
    }

    @ParameterizedTest
    @CsvSource({"ΟΣ,οσ", "ος,οσ", "οσ,οσ", "ΟΣΚΑΡΑ,οσκαρα", "İ,i̇", "MaTrIx,matrix"})
    @DisplayName("Java 검색조건과 PostgreSQL 함수는 같은 Unicode 정규화 값을 만든다")
    void normalizesSearchInputsLikeCriteria(String input, String expected) {
        MovieSearchCriteria criteria =
                new MovieSearchCriteria(input, null, null, null, null, null, null, null, null);
        assertThat(criteria.q()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT movie_search_normalize(?)", String.class, input))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"title", "original_title"})
    @DisplayName("영화 제목과 원제는 simple 설정의 full-text GIN 인덱스로 검색할 수 있다")
    void createsSimpleFullTextGin(String column) {
        // 제목·원제를 합친 vector 또는 각각의 vector 모두 허용한다.
        assertThat(fullTextIndexes())
                .anySatisfy(
                        index -> {
                            assertThat(index.expression()).containsPattern("\\b" + column + "\\b");
                            assertThat(index.expression()).contains("movie_search_normalize(");
                            assertThat(index.predicate()).isNull();
                            assertThat(index.valid()).isTrue();
                            assertThat(
                                            jdbc.queryForList(
                                                    """
                            SELECT opc.opcname
                            FROM pg_index i
                            JOIN pg_class c ON c.oid = i.indexrelid
                            JOIN pg_opclass opc ON opc.oid = ANY(i.indclass)
                            WHERE c.relname = ?
                            """,
                                                    String.class,
                                                    index.name()))
                                    .contains("tsvector_ops");
                        });
    }

    @ParameterizedTest
    @MethodSource("trigramQueries")
    @DisplayName("대표 부분 일치·오타 검색 쿼리는 실제 검색 인덱스를 사용한다")
    void searchPredicatesUseIndexes(String table, String column, String predicate, String query) {
        // table/column/predicate는 아래 테스트 상수만 사용하며 검색어는 바인딩한다.
        String sql =
                "SELECT id FROM "
                        + table
                        + " WHERE movie_search_normalize("
                        + column
                        + ") "
                        + predicate;
        assertThat(jdbc.queryForList(sql, Long.class, query)).containsExactly(1L);
        assertUsesSearchIndex(sql, query, indexes(table));
    }

    static Stream<Arguments> trigramQueries() {
        return Stream.of(
                Arguments.of("movies", "title", "LIKE movie_search_normalize(?)", "%uniqueneedle%"),
                Arguments.of(
                        "movies",
                        "original_title",
                        "LIKE movie_search_normalize(?)",
                        "%originalneedle%"),
                Arguments.of("people", "name", "LIKE movie_search_normalize(?)", "%personneedle%"),
                Arguments.of("movies", "title", "% movie_search_normalize(?)", "UniqueNedle movie"),
                Arguments.of(
                        "movies",
                        "original_title",
                        "% movie_search_normalize(?)",
                        "OriginalNedle film"));
    }

    @Test
    @DisplayName("full-text 검색식과 인덱스식이 일치하면 실행 계획에 GIN 인덱스가 나타난다")
    void fullTextSearchUsesGin() {
        List<SearchIndex> indexes = fullTextIndexes();
        assertThat(indexes).as("Flyway가 생성한 검색용 full-text GIN 인덱스").isNotEmpty();
        for (SearchIndex index : indexes) {
            // pg_catalog에서 얻은 인덱스식을 사용해 복합·분리 vector 설계 모두 검증한다.
            String sql =
                    "SELECT id FROM movies WHERE ("
                            + index.expression()
                            + ") @@ plainto_tsquery('simple', movie_search_normalize(?))";
            String query =
                    index.expression().contains("original_title")
                            ? "OriginalNeedle"
                            : "UniqueNeedle";
            assertThat(jdbc.queryForList(sql, Long.class, query)).containsExactly(1L);
            assertUsesSearchIndex(sql, query, List.of(index));
        }
    }

    private static void assertUsesSearchIndex(String sql, String query, List<SearchIndex> indexes) {
        String plan =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql,
                                String.class,
                                query));
        assertThat(plan).as("검색 실행 계획:\n%s", plan).contains("Index").doesNotContain("Seq Scan");
        assertThat(indexes)
                .anySatisfy(
                        index -> {
                            assertThat(index.method()).isEqualTo("gin");
                            assertThat(plan).contains(index.name());
                        });
    }

    private static List<SearchIndex> fullTextIndexes() {
        return indexes("movies").stream()
                .filter(index -> "gin".equals(index.method()))
                .filter(index -> index.expression() != null)
                .filter(index -> index.expression().contains("to_tsvector('simple'::regconfig"))
                .toList();
    }

    private static List<SearchIndex> indexes(String table) {
        return jdbc.query(
                """
                SELECT c.relname, am.amname, pg_get_expr(i.indexprs, i.indrelid) expression,
                       pg_get_indexdef(i.indexrelid) definition,
                       pg_get_expr(i.indpred, i.indrelid) predicate, i.indisvalid
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace ns ON ns.oid = t.relnamespace
                JOIN pg_am am ON am.oid = c.relam
                WHERE ns.nspname = 'public' AND t.relname = ?
                """,
                (row, rowNumber) ->
                        new SearchIndex(
                                row.getString("relname"),
                                row.getString("amname"),
                                row.getString("expression"),
                                row.getString("definition"),
                                row.getString("predicate"),
                                row.getBoolean("indisvalid")),
                table);
    }

    private record SearchIndex(
            String name,
            String method,
            String expression,
            String definition,
            String predicate,
            boolean valid) {}
}
