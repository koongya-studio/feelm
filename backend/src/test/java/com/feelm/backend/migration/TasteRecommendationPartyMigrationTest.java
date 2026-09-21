package com.feelm.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("취향·추천·파티 마이그레이션")
class TasteRecommendationPartyMigrationTest {

    private static final MigrationPostgresSupport.MigrationTestDatabase DATABASE =
            MigrationPostgresSupport.database("taste_recommendation_party");

    @BeforeAll
    static void migrate() {
        DATABASE.flyway().migrate();
    }

    @AfterAll
    static void dropDatabase() {
        DATABASE.close();
    }

    @Test
    @DisplayName("대상 테이블과 팝콘 맛 군집 컬럼을 생성한다")
    void createsTablesAndFlavorColumns() {
        assertThat(
                        column(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = 'public'"))
                .contains(
                        "frames",
                        "taste_analyses",
                        "parties",
                        "party_members",
                        "party_invitations",
                        "recommendation_sets",
                        "recommendation_items",
                        "user_movie_dismissals",
                        "user_movie_exposures");
        assertThat(columnsOf("popcorn_flavors")).contains("cluster_id", "representative_keywords");
        assertThat(columnsOf("movies")).doesNotContain("taste_model_version");
    }

    @Test
    @DisplayName("프레임은 평가와 1대1이고 평가 삭제 시 함께 삭제된다")
    void enforcesFrameLifecycle() throws SQLException {
        long userId = insertUser("frame@feelm.test", "프레임사용자");
        long movieId = insertMovie(181001, "프레임 영화");
        long ratingId =
                insertReturningId(
                        "INSERT INTO ratings(user_id, movie_id, score) VALUES ("
                                + userId
                                + ", "
                                + movieId
                                + ", 5)");

        execute(
                "INSERT INTO frames(rating_id, user_id, movie_id) VALUES ("
                        + ratingId
                        + ", "
                        + userId
                        + ", "
                        + movieId
                        + ")");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO frames(rating_id, user_id, movie_id) VALUES ("
                                                + ratingId
                                                + ", "
                                                + userId
                                                + ", "
                                                + movieId
                                                + ")"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_frames_rating_id");

        execute("DELETE FROM ratings WHERE id = " + ratingId);
        assertThat(longValue("SELECT count(*) FROM frames WHERE rating_id = " + ratingId)).isZero();
    }

    @Test
    @DisplayName("사용자별 취향 분석은 하나이며 평가 수는 음수가 될 수 없다")
    void enforcesTasteAnalysisConstraints() throws SQLException {
        long userId = insertUser("taste@feelm.test", "취향사용자");
        execute(
                "INSERT INTO taste_analyses(user_id, analysis, keywords, rating_count, analyzed_at) VALUES ("
                        + userId
                        + ", '{}'::jsonb, '[]'::jsonb, 1, now())");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO taste_analyses(user_id, analysis, keywords, rating_count, analyzed_at) VALUES ("
                                                + userId
                                                + ", '{}'::jsonb, '[]'::jsonb, 2, now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_taste_analyses_user_id");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO taste_analyses(user_id, analysis, keywords, rating_count, analyzed_at) VALUES ("
                                                + insertUser("negative@feelm.test", "음수사용자")
                                                + ", '{}'::jsonb, '[]'::jsonb, -1, now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_taste_analyses_rating_count");
    }

    @Test
    @DisplayName("파티 종료 상태와 단일 오너를 보장한다")
    void enforcesPartyStateAndSingleOwner() throws SQLException {
        long ownerId = insertUser("owner@feelm.test", "오너");
        long memberId = insertUser("member@feelm.test", "멤버");
        long partyId =
                insertReturningId(
                        "INSERT INTO parties(name, owner_id, status) VALUES ('테스트 파티', "
                                + ownerId
                                + ", 'ACTIVE')");
        execute(
                "INSERT INTO party_members(party_id, user_id, role, joined_at) VALUES ("
                        + partyId
                        + ", "
                        + ownerId
                        + ", 'OWNER', now())");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_members(party_id, user_id, role, joined_at) VALUES ("
                                                + partyId
                                                + ", "
                                                + memberId
                                                + ", 'OWNER', now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uix_party_members_single_owner");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO parties(name, owner_id, status, closed_reason, closed_at) VALUES ("
                                                + "'잘못된 파티', "
                                                + ownerId
                                                + ", 'ACTIVE', 'OWNER_CLOSED', now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_parties_closed_state");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO parties(name, owner_id, status) VALUES ('"
                                                + "가".repeat(21)
                                                + "', "
                                                + ownerId
                                                + ", 'ACTIVE')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("초대의 상태 시각, 자기 초대, 대기 중 중복을 제한한다")
    void enforcesInvitationRules() throws SQLException {
        long inviterId = insertUser("inviter@feelm.test", "초대자");
        long inviteeId = insertUser("invitee@feelm.test", "초대대상");
        long partyId =
                insertReturningId(
                        "INSERT INTO parties(name, owner_id, status) VALUES ('초대 파티', "
                                + inviterId
                                + ", 'ACTIVE')");
        execute(
                "INSERT INTO party_invitations(party_id, inviter_id, invitee_id, status) VALUES ("
                        + partyId
                        + ", "
                        + inviterId
                        + ", "
                        + inviteeId
                        + ", 'PENDING')");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_invitations(party_id, inviter_id, invitee_id, status) VALUES ("
                                                + partyId
                                                + ", "
                                                + inviterId
                                                + ", "
                                                + inviteeId
                                                + ", 'PENDING')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uix_party_invitations_pending");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_invitations(party_id, inviter_id, invitee_id, status) VALUES ("
                                                + partyId
                                                + ", "
                                                + inviterId
                                                + ", "
                                                + inviterId
                                                + ", 'PENDING')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_party_invitations_not_self");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO party_invitations(party_id, inviter_id, invitee_id, status) VALUES ("
                                                + partyId
                                                + ", "
                                                + inviterId
                                                + ", "
                                                + insertUser("responded@feelm.test", "응답누락")
                                                + ", 'DECLINED')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_party_invitations_response_state");
    }

    @Test
    @DisplayName("추천 세트의 소유자는 사용자와 파티 중 정확히 하나다")
    void enforcesRecommendationSetOwner() throws SQLException {
        long userId = insertUser("recommend@feelm.test", "추천사용자");
        long partyId =
                insertReturningId(
                        "INSERT INTO parties(name, owner_id, status) VALUES ('추천 파티', "
                                + userId
                                + ", 'ACTIVE')");

        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO recommendation_sets(input_version, model_version, generated_at)"
                                                + " VALUES ('input-1', 'als-1', now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_recommendation_sets_owner");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO recommendation_sets(user_id, party_id, input_version, model_version, generated_at) VALUES ("
                                                + userId
                                                + ", "
                                                + partyId
                                                + ", 'input-2', 'als-1', now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_recommendation_sets_owner");
    }

    @Test
    @DisplayName("개인 추천 세트는 generation ID를 저장하고 같은 generation·회차를 중복 생성할 수 없다")
    void storesRecommendationCandidateMetadata() throws SQLException {
        long userId = insertUser("candidate@feelm.test", "후보사용자");
        long otherUserId = insertUser("candidate-other@feelm.test", "다른후보사용자");
        long movieId = insertMovie(181003, "후보 영화");
        long discoveryMovieId = insertMovie(181004, "발견 후보 영화");
        long setId =
                insertReturningId(
                        "INSERT INTO recommendation_sets"
                                + " (user_id,input_version,model_version,candidate_version,generation_id,generated_at)"
                                + " VALUES ("
                                + userId
                                + ",'input-candidate','als-1',3,'generation-3',now())");
        execute(
                "INSERT INTO recommendation_items"
                        + " (set_id,movie_id,type,flavor_code,position,reason) VALUES ("
                        + setId
                        + ","
                        + movieId
                        + ",'TASTE','FLAVOR_A',1,'{}'::jsonb)");
        execute(
                "INSERT INTO recommendation_items"
                        + " (set_id,movie_id,type,flavor_code,position,reason) VALUES ("
                        + setId
                        + ","
                        + discoveryMovieId
                        + ",'DISCOVERY','FLAVOR_B',1,'{}'::jsonb)");

        assertThat(longValue("SELECT candidate_version FROM recommendation_sets WHERE id=" + setId))
                .isEqualTo(3);
        assertThat(column("SELECT generation_id FROM recommendation_sets WHERE id=" + setId))
                .containsExactly("generation-3");
        assertThat(column("SELECT flavor_code FROM recommendation_items WHERE set_id=" + setId))
                .containsExactlyInAnyOrder("FLAVOR_A", "FLAVOR_B");
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO recommendation_sets"
                                                + " (user_id,input_version,model_version,"
                                                + " candidate_version,generation_id,generated_at) VALUES ("
                                                + otherUserId
                                                + ",'another-input','als-2',9,'generation-3',now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uix_recommendation_sets_generation_batch");
        // 회차를 구분하면 같은 generation이라도 batch_no가 다르면 저장할 수 있다(S15P21E106-219).
        execute(
                "INSERT INTO recommendation_sets"
                        + " (user_id,input_version,model_version,"
                        + " candidate_version,generation_id,batch_no,generated_at) VALUES ("
                        + userId
                        + ",'input-candidate','als-1',3,'generation-3',1,now())");
        assertThat(
                        longValue(
                                "SELECT count(*) FROM recommendation_sets"
                                        + " WHERE generation_id='generation-3'"))
                .isEqualTo(2);
        execute(
                "INSERT INTO recommendation_sets"
                        + " (user_id,input_version,model_version,candidate_version,generation_id,generated_at)"
                        + " VALUES ("
                        + userId
                        + ",'input-candidate','als-1',3,'generation-4',now())");
    }

    @Test
    @DisplayName("추천 아이템 삭제 후 관심없음 이력의 출처만 비운다")
    void preservesDismissalWhenRecommendationItemIsDeleted() throws SQLException {
        long userId = insertUser("dismiss@feelm.test", "관심없음사용자");
        long movieId = insertMovie(181002, "관심없음 영화");
        long setId =
                insertReturningId(
                        "INSERT INTO recommendation_sets(user_id, input_version, model_version, generated_at) VALUES ("
                                + userId
                                + ", 'input-dismiss', 'als-1', now())");
        long itemId =
                insertReturningId(
                        "INSERT INTO recommendation_items(set_id, movie_id, type, position, reason) VALUES ("
                                + setId
                                + ", "
                                + movieId
                                + ", 'TASTE', 1, '{}'::jsonb)");
        execute(
                "INSERT INTO user_movie_dismissals(user_id, movie_id, recommendation_item_id) VALUES ("
                        + userId
                        + ", "
                        + movieId
                        + ", "
                        + itemId
                        + ")");

        execute("DELETE FROM recommendation_items WHERE id = " + itemId);
        assertThat(
                        column(
                                "SELECT recommendation_item_id::text FROM user_movie_dismissals WHERE user_id = "
                                        + userId))
                .containsExactly((String) null);
        assertThatThrownBy(
                        () ->
                                execute(
                                        "INSERT INTO user_movie_dismissals(user_id, movie_id) VALUES ("
                                                + userId
                                                + ", "
                                                + movieId
                                                + ")"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_user_movie_dismissals_user_movie");
    }

    @Test
    @DisplayName("마이그레이션을 다시 적용해도 추가 실행하지 않는다")
    void isSafeToReapply() {
        assertThat(DATABASE.flyway().migrate().migrationsExecuted).isZero();
    }

    private static long insertUser(String email, String nickname) throws SQLException {
        return insertReturningId(
                "INSERT INTO users(email, nickname, birth_date, gender, status) VALUES ('"
                        + email
                        + "', '"
                        + nickname
                        + "', DATE '1999-03-14', 'FEMALE', 'ACTIVE')");
    }

    private static long insertMovie(int tmdbId, String title) throws SQLException {
        return insertReturningId(
                "INSERT INTO movies(tmdb_id, title) VALUES (" + tmdbId + ", '" + title + "')");
    }

    private static long insertReturningId(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql + " RETURNING id")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static List<String> columnsOf(String table) {
        return column(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name = '"
                        + table
                        + "'");
    }

    private static long longValue(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static List<String> column(String sql) {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect() throws SQLException {
        return DATABASE.connect();
    }
}
