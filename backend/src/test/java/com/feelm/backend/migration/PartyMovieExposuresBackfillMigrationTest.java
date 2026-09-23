package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * 파티 추천 이력 정본 표의 백필이 기존 노출을 실제로 옮기는지 검증한다(S15P21E106-815).
 *
 * <p>계약 테스트는 빈 DB에 Flyway를 적용하므로 백필 {@code INSERT … SELECT DISTINCT ON}이 항상 0행을 대상으로 돈다. 여기서는 직전
 * 버전까지 적용해 파티 세트 2개에 같은 영화의 노출·미노출과 개인 세트 노출을 만든 뒤 나머지를 적용한다. 개인 이력 백필 테스트와 같은 골격이다.
 *
 * <p>실행에 Docker가 필요하다. Flyway가 기동 로그를 표준 출력에 쏟으므로 출력 캡처를 쓰는 테스트와 같은 잠금을 잡는다.
 */
@DisplayName("파티 추천 이력 정본 표 백필 마이그레이션 — S15P21E106-815")
@ResourceLock("backend-output-capture")
class PartyMovieExposuresBackfillMigrationTest {

    /** 이 마이그레이션 직전 버전. 사이에 새 마이그레이션이 끼면 이 값도 함께 올린다. */
    private static final String VERSION_BEFORE_BACKFILL = "202609221510";

    private static final Path MIGRATION_FILE =
            Path.of(
                    "src/main/resources/db/migration/"
                            + "V202609231400__create_party_movie_exposures.sql");

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("party_movie_exposures");

    private static long partyId;
    private static long otherPartyId;
    private static long repeatedMovieId;
    private static long onceMovieId;
    private static long driftMovieId;
    private static long bufferedMovieId;
    private static long olderSetId;
    private static long newerSetId;
    private static long otherPartySetId;
    private static long personalSetId;
    private static long newerRepeatedItemId;
    private static long onceItemId;
    private static long driftItemId;
    private static long otherPartyItemId;

    @BeforeAll
    static void migrateAroundBackfill() throws SQLException {
        Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(MigrationVersion.fromVersion(VERSION_BEFORE_BACKFILL))
                .load()
                .migrate();

        long ownerId = insertUser("owner@feelm.test", "파티장");
        partyId = insertParty(ownerId);
        otherPartyId = insertParty(ownerId);
        repeatedMovieId = insertMovie(8_001, "두 세트에 노출된 영화");
        onceMovieId = insertMovie(8_002, "한 번만 노출된 영화");
        driftMovieId = insertMovie(8_004, "드리프트 복구용 영화");
        bufferedMovieId = insertMovie(8_003, "쟁여만 둔 영화");

        // 세트 id 오름차순 = 최신순. 같은 영화가 두 세트에 노출되면 뒤 세트가 승자여야 한다.
        olderSetId = insertPartySet(partyId, "input-old");
        newerSetId = insertPartySet(partyId, "input-new");
        otherPartySetId = insertPartySet(otherPartyId, "input-other");
        insertItem(olderSetId, repeatedMovieId, "PARTY", 1, true);
        onceItemId = insertItem(olderSetId, onceMovieId, "PARTY", 2, true);
        driftItemId = insertItem(olderSetId, driftMovieId, "PARTY", 3, true);
        newerRepeatedItemId = insertItem(newerSetId, repeatedMovieId, "PARTY", 1, true);
        insertItem(newerSetId, bufferedMovieId, "PARTY", 2, false);
        otherPartyItemId = insertItem(otherPartySetId, repeatedMovieId, "PARTY", 1, true);

        // 개인 세트의 노출은 파티 이력이 아니다. party_id가 NULL이라 백필 대상에서 빠져야 한다.
        personalSetId =
                scalar(
                        "INSERT INTO recommendation_sets (user_id, input_version, model_version,"
                                + " generated_at, generation_id) VALUES ("
                                + ownerId
                                + ", 'redis-candidate', 'model-v1', now(), 'generation-personal')"
                                + " RETURNING id");
        insertItem(personalSetId, repeatedMovieId, "TASTE", 1, true);

        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("파티별 노출 영화마다 정확히 한 행이 생기고 미노출·개인 세트는 빠진다")
    void backfillsOneRowPerExposedMovie() {
        assertThat(count("SELECT count(*) FROM party_movie_exposures")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM party_movie_exposures WHERE party_id = " + partyId))
                .isEqualTo(3);
        assertThat(
                        count(
                                "SELECT count(*) FROM party_movie_exposures WHERE movie_id = "
                                        + bufferedMovieId))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM party_movie_exposures WHERE set_id = "
                                        + personalSetId))
                .isZero();
    }

    @Test
    @DisplayName("두 세트에 노출된 영화는 set_id가 큰 쪽의 항목을 가리킨다")
    void keepsLatestExposureForRepeatedMovie() {
        assertThat(
                        column(
                                "SELECT recommendation_item_id || '|' || set_id"
                                        + " FROM party_movie_exposures WHERE party_id = "
                                        + partyId
                                        + " AND movie_id = "
                                        + repeatedMovieId))
                .containsExactly(newerRepeatedItemId + "|" + newerSetId);
        assertThat(
                        column(
                                "SELECT recommendation_item_id || '|' || set_id"
                                        + " FROM party_movie_exposures WHERE party_id = "
                                        + partyId
                                        + " AND movie_id = "
                                        + onceMovieId))
                .containsExactly(onceItemId + "|" + olderSetId);
    }

    @Test
    @DisplayName("다른 파티의 같은 영화 노출은 별도 행이다")
    void isolatesParties() {
        assertThat(
                        column(
                                "SELECT recommendation_item_id || '|' || set_id"
                                        + " FROM party_movie_exposures WHERE party_id = "
                                        + otherPartyId))
                .containsExactly(otherPartyItemId + "|" + otherPartySetId);
    }

    @Test
    @DisplayName("백필 문을 다시 실행해도 행이 늘지 않고, 더 최신 노출이 있으면 그쪽으로만 갱신된다")
    void backfillIsIdempotentAndRecoversDrift() throws Exception {
        long before = count("SELECT count(*) FROM party_movie_exposures");
        String backfill = backfillStatement();

        execute(backfill);
        assertThat(count("SELECT count(*) FROM party_movie_exposures")).isEqualTo(before);

        // 이전 코드가 upsert 없이 exposed_at만 찍은 상황(롤백 창 드리프트)을 흉내 낸다.
        long driftSetId = insertPartySet(partyId, "input-drift");
        long recoveredItemId = insertItem(driftSetId, driftMovieId, "PARTY", 1, true);
        assertThat(
                        column(
                                "SELECT recommendation_item_id || '|' || set_id"
                                        + " FROM party_movie_exposures WHERE party_id = "
                                        + partyId
                                        + " AND movie_id = "
                                        + driftMovieId))
                .containsExactly(driftItemId + "|" + olderSetId);

        execute(backfill);

        assertThat(count("SELECT count(*) FROM party_movie_exposures")).isEqualTo(before);
        assertThat(
                        column(
                                "SELECT recommendation_item_id || '|' || set_id"
                                        + " FROM party_movie_exposures WHERE party_id = "
                                        + partyId
                                        + " AND movie_id = "
                                        + driftMovieId))
                .containsExactly(recoveredItemId + "|" + driftSetId);
        assertThat(
                        column(
                                "SELECT set_id::text FROM party_movie_exposures WHERE party_id = "
                                        + partyId
                                        + " AND movie_id = "
                                        + repeatedMovieId))
                .containsExactly(String.valueOf(newerSetId));
    }

    // --- helpers ---------------------------------------------------------

    /** 파일 안의 백필 INSERT만 잘라낸다. DDL은 재실행할 수 없으므로 복구 절차도 이 문장만 다시 돈다. */
    private static String backfillStatement() throws Exception {
        String sql = Files.readString(MIGRATION_FILE, StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO party_movie_exposures");
        assertThat(start).as("백필 INSERT 문이 마이그레이션 파일에 있어야 한다").isNotNegative();
        return sql.substring(start);
    }

    private static long insertUser(String email, String nickname) {
        return scalar(
                "INSERT INTO users (email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1999-03-14', 'FEMALE', 'ACTIVE') RETURNING id");
    }

    private static long insertParty(long ownerId) {
        return scalar(
                "INSERT INTO parties (name, owner_id, status) VALUES ('파티', "
                        + ownerId
                        + ", 'ACTIVE') RETURNING id");
    }

    private static long insertMovie(int tmdbId, String title) {
        return scalar(
                "INSERT INTO movies (tmdb_id, title) VALUES ("
                        + tmdbId
                        + ", '"
                        + title
                        + "') RETURNING id");
    }

    private static long insertPartySet(long party, String inputId) {
        return scalar(
                "INSERT INTO recommendation_sets (party_id, input_version, model_version,"
                        + " candidate_version, generated_at) VALUES ("
                        + party
                        + ", '"
                        + inputId
                        + "', 'model-v1', 0, now()) RETURNING id");
    }

    private static long insertItem(
            long setId, long movieId, String type, int position, boolean exposed) {
        return scalar(
                "INSERT INTO recommendation_items (set_id, movie_id, type, position, reason,"
                        + " exposed_at) VALUES ("
                        + setId
                        + ", "
                        + movieId
                        + ", '"
                        + type
                        + "', "
                        + position
                        + ", '{}'::jsonb, "
                        + (exposed ? "now()" : "NULL")
                        + ") RETURNING id");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(String sql) {
        return Long.parseLong(column(sql).getFirst());
    }

    private static long count(String sql) {
        return Long.parseLong(column(sql).getFirst());
    }

    private static List<String> column(String sql) {
        try (Connection connection = DATABASE.connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
