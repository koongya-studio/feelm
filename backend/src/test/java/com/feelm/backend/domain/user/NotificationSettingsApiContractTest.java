package com.feelm.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** API-017·018: 실제 마이그레이션과 PostgreSQL을 사용하는 알림 설정 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("API-017·018 알림 설정 조회·변경")
class NotificationSettingsApiContractTest extends SharedPostgresContractTest {

    private static final String PATH = "/api/v1/users/me/notification-settings";
    private static final long ACTIVE = 10;
    private static final long WITHDRAWN = 20;

    /** 변경이 자기 행만 건드리는지 보기 위한 제3자. 이 사용자의 설정은 어떤 테스트에서도 바뀌면 안 된다. */
    private static final long OTHER = 30;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenProvider tokens;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(ACTIVE, "active", "ACTIVE");
        user(WITHDRAWN, "withdrawn", "WITHDRAWN");
        user(OTHER, "other", "ACTIVE");
        // 가입 트랜잭션이 만드는 기본행을 그대로 재현한다.
        settings(ACTIVE, true, true, true, true, false);
        settings(WITHDRAWN, true, true, true, true, false);
        settings(OTHER, false, true, false, false, true);
    }

    /** 어떤 경로도 다른 사용자의 설정을 건드리지 않는다. */
    @AfterEach
    void keepsOtherUserSettings() {
        assertThat(settingsOf(OTHER)).containsExactly(false, true, false, false, true);
    }

    // ----- API-017 알림 설정 조회 -----

    @Test
    @DisplayName("T1 가입 기본값을 그대로 내려준다")
    void returnsDefaultsForNewAccount() throws Exception {
        read(ACTIVE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RATING").value(true))
                .andExpect(jsonPath("$.RECOMMENDATION").value(true))
                .andExpect(jsonPath("$.PARTY_INVITE").value(true))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(true))
                .andExpect(jsonPath("$.MARKETING").value(false));
    }

    @Test
    @DisplayName("T2 저장된 값을 항목마다 그대로 내려준다")
    void returnsStoredValuesPerField() throws Exception {
        settings(ACTIVE, false, true, false, true, true);

        read(ACTIVE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RATING").value(false))
                .andExpect(jsonPath("$.RECOMMENDATION").value(true))
                .andExpect(jsonPath("$.PARTY_INVITE").value(false))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(true))
                .andExpect(jsonPath("$.MARKETING").value(true));
    }

    @Test
    @DisplayName("T3 인증 없이 조회할 수 없다")
    void rejectsUnauthenticatedRead() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T4 활성 계정이 아니면 조회할 수 없다")
    void rejectsInactiveAccountRead() throws Exception {
        read(WITHDRAWN)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("T12 설정 행이 없으면 기본값을 내려주고 행을 만들지 않는다")
    void returnsDefaultsWhenRowMissing() throws Exception {
        deleteSettings(ACTIVE);

        read(ACTIVE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RATING").value(true))
                .andExpect(jsonPath("$.RECOMMENDATION").value(true))
                .andExpect(jsonPath("$.PARTY_INVITE").value(true))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(true))
                .andExpect(jsonPath("$.MARKETING").value(false));

        assertThat(rowCount(ACTIVE)).isZero();
    }

    // ----- API-018 알림 설정 변경 -----

    @Test
    @DisplayName("T5 다섯 항목을 전체 교체하고 재조회에 반영한다")
    void replacesAllSettings() throws Exception {
        replace(
                        ACTIVE,
                        "{\"RATING\":false,\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,"
                                + "\"MARKETING\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RATING").value(false))
                .andExpect(jsonPath("$.RECOMMENDATION").value(false))
                .andExpect(jsonPath("$.PARTY_INVITE").value(false))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(false))
                .andExpect(jsonPath("$.MARKETING").value(true));

        assertThat(settingsOf(ACTIVE)).containsExactly(false, false, false, false, true);
        read(ACTIVE)
                .andExpect(jsonPath("$.RATING").value(false))
                .andExpect(jsonPath("$.RECOMMENDATION").value(false))
                .andExpect(jsonPath("$.PARTY_INVITE").value(false))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(false))
                .andExpect(jsonPath("$.MARKETING").value(true));
    }

    @Test
    @DisplayName("T6 같은 요청을 반복해도 결과가 같다")
    void isIdempotentOnRepeatedRequest() throws Exception {
        String body =
                "{\"RATING\":false,\"RECOMMENDATION\":true,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,"
                        + "\"MARKETING\":true}";

        replace(ACTIVE, body).andExpect(status().isOk());
        replace(ACTIVE, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RATING").value(false))
                .andExpect(jsonPath("$.RECOMMENDATION").value(true))
                .andExpect(jsonPath("$.PARTY_INVITE").value(false))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(false))
                .andExpect(jsonPath("$.MARKETING").value(true));

        assertThat(settingsOf(ACTIVE)).containsExactly(false, true, false, false, true);
    }

    @Test
    @DisplayName("T7 다섯 키 중 하나가 없으면 거절한다")
    void rejectsMissingKey() throws Exception {
        for (String body :
                new String[] {
                    "{\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,\"MARKETING\":true}",
                    "{\"RATING\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,\"MARKETING\":true}",
                    "{\"RATING\":false,\"RECOMMENDATION\":false,\"REPORT_ISSUED\":false,\"MARKETING\":true}",
                    "{\"RATING\":false,\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,"
                            + "\"MARKETING\":true}",
                    "{\"RATING\":false,\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false}"
                }) {
            replace(ACTIVE, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(settingsOf(ACTIVE)).containsExactly(true, true, true, true, false);
    }

    @Test
    @DisplayName("T8 값이 null이면 거절한다")
    void rejectsNullValue() throws Exception {
        for (String body :
                new String[] {
                    "{\"RATING\":null,\"RECOMMENDATION\":true,\"PARTY_INVITE\":true,\"REPORT_ISSUED\":true,\"MARKETING\":false}",
                    "{\"RATING\":true,\"RECOMMENDATION\":true,\"PARTY_INVITE\":null,\"REPORT_ISSUED\":true,\"MARKETING\":false}",
                    "{\"RATING\":true,\"RECOMMENDATION\":true,\"PARTY_INVITE\":true,\"REPORT_ISSUED\":null,\"MARKETING\":false}"
                }) {
            replace(ACTIVE, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(settingsOf(ACTIVE)).containsExactly(true, true, true, true, false);
    }

    @Test
    @DisplayName("T9 빈 본문이면 거절한다")
    void rejectsEmptyBody() throws Exception {
        replace(ACTIVE, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(settingsOf(ACTIVE)).containsExactly(true, true, true, true, false);
    }

    @Test
    @DisplayName("T10 인증 없이 변경할 수 없다")
    void rejectsUnauthenticatedUpdate() throws Exception {
        String body =
                "{\"RATING\":false,\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,"
                        + "\"MARKETING\":true}";

        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(
                        put(PATH)
                                .header("Authorization", "Bearer invalid")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(settingsOf(ACTIVE)).containsExactly(true, true, true, true, false);
    }

    @Test
    @DisplayName("T11 활성 계정이 아니면 변경할 수 없다")
    void rejectsInactiveAccountUpdate() throws Exception {
        replace(
                        WITHDRAWN,
                        "{\"RATING\":false,\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,"
                                + "\"MARKETING\":true}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(settingsOf(WITHDRAWN)).containsExactly(true, true, true, true, false);
    }

    @Test
    @DisplayName("T13 설정 행이 없으면 만들어 적용한다")
    void createsRowWhenMissingOnUpdate() throws Exception {
        deleteSettings(ACTIVE);

        replace(
                        ACTIVE,
                        "{\"RATING\":false,\"RECOMMENDATION\":false,\"PARTY_INVITE\":false,\"REPORT_ISSUED\":false,"
                                + "\"MARKETING\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RATING").value(false))
                .andExpect(jsonPath("$.RECOMMENDATION").value(false))
                .andExpect(jsonPath("$.PARTY_INVITE").value(false))
                .andExpect(jsonPath("$.REPORT_ISSUED").value(false))
                .andExpect(jsonPath("$.MARKETING").value(false));

        assertThat(rowCount(ACTIVE)).isEqualTo(1);
        assertThat(settingsOf(ACTIVE)).containsExactly(false, false, false, false, false);
    }

    @Test
    @DisplayName("T15 두 경로의 응답 본문 키가 명세의 다섯 개뿐이다")
    void returnsExactlySpecifiedKeys() throws Exception {
        // 키 이름은 이 API의 계약 자체다. 값 단언만으로는 키가 하나 늘거나 자바 이름으로 새는 회귀를 잡지 못한다(리뷰 A-F3).
        for (ResultActions response :
                List.of(
                        read(ACTIVE),
                        replace(
                                ACTIVE,
                                "{\"RATING\":false,\"RECOMMENDATION\":true,"
                                        + "\"PARTY_INVITE\":true,\"REPORT_ISSUED\":true,\"MARKETING\":false}"))) {
            response.andExpect(status().isOk())
                    .andExpect(jsonPath("$.*", hasSize(5)))
                    .andExpect(jsonPath("$.RATING").exists())
                    .andExpect(jsonPath("$.RECOMMENDATION").exists())
                    .andExpect(jsonPath("$.PARTY_INVITE").exists())
                    .andExpect(jsonPath("$.REPORT_ISSUED").exists())
                    .andExpect(jsonPath("$.MARKETING").exists());
        }
    }

    private ResultActions read(long userId) throws Exception {
        return mvc.perform(get(PATH).header("Authorization", bearer(userId)));
    }

    private ResultActions replace(long userId, String body) throws Exception {
        return mvc.perform(
                put(PATH)
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.createAccessToken(userId);
    }

    private void user(long id, String nickname, String status) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?)",
                id,
                nickname + "@example.test",
                nickname,
                status);
    }

    private void settings(
            long userId,
            boolean rating,
            boolean recommendation,
            boolean partyInvite,
            boolean reportIssued,
            boolean marketing) {
        jdbc.update(
                "INSERT INTO notification_settings(user_id,rating_enabled,recommendation_enabled,"
                        + "party_invite_enabled,report_issued_enabled,marketing_enabled) VALUES (?,?,?,?,?,?)"
                        + " ON CONFLICT (user_id) DO UPDATE SET"
                        + " rating_enabled=EXCLUDED.rating_enabled,"
                        + " recommendation_enabled=EXCLUDED.recommendation_enabled,"
                        + " party_invite_enabled=EXCLUDED.party_invite_enabled,"
                        + " report_issued_enabled=EXCLUDED.report_issued_enabled,"
                        + " marketing_enabled=EXCLUDED.marketing_enabled",
                userId,
                rating,
                recommendation,
                partyInvite,
                reportIssued,
                marketing);
    }

    private void deleteSettings(long userId) {
        jdbc.update("DELETE FROM notification_settings WHERE user_id=?", userId);
    }

    private int rowCount(long userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_settings WHERE user_id=?",
                Integer.class,
                userId);
    }

    /** 저장된 값을 평가·추천·파티 초대·리포트 발급·마케팅 순서로 읽는다. */
    private List<Boolean> settingsOf(long userId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT rating_enabled,recommendation_enabled,party_invite_enabled,"
                                + "report_issued_enabled,marketing_enabled"
                                + " FROM notification_settings WHERE user_id=?",
                        userId);
        return List.of(
                (Boolean) row.get("rating_enabled"),
                (Boolean) row.get("recommendation_enabled"),
                (Boolean) row.get("party_invite_enabled"),
                (Boolean) row.get("report_issued_enabled"),
                (Boolean) row.get("marketing_enabled"));
    }
}
