package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.service.ReportPublicationService;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 반기 리포트가 공개될 때 리포트 발급 알림을 만드는지(S15P21E106-858). 실제 마이그레이션과 PostgreSQL을 쓴다. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@DisplayName("리포트 발급 알림 — S15P21E106-858")
class ReportIssuedNotificationTest extends SharedPostgresContractTest {

    private static final long BASE = 5_858_000L;
    private static final long ENABLED = BASE + 1;
    private static final long DISABLED = BASE + 2;
    private static final long WITHDRAWN = BASE + 3;

    /** 다른 리포트 테스트가 쓰는 반기와 겹치지 않게 아직 공개 전인 반기를 쓴다. */
    private static final ReportPeriod PERIOD = new ReportPeriod(2026, ReportHalf.H2);

    /** 다음 반기 공개 시각(2026-01-01)이 이미 지나 첫 발급을 기록할 수 없는 반기 */
    private static final ReportPeriod CLOSED_PERIOD = new ReportPeriod(2025, ReportHalf.H1);

    @Autowired ReportPublicationService publicationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TokenProvider tokens;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_report_notification ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_report_notification()");
        jdbc.update("DELETE FROM notifications WHERE user_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM taste_reports WHERE user_id BETWEEN ? AND ?", BASE, BASE + 999);
        jdbc.update("DELETE FROM users WHERE id BETWEEN ? AND ?", BASE, BASE + 999);
    }

    @Test
    @DisplayName("공개한 리포트마다 켠 사용자에게만 알림을 만들고 Web Push는 걸지 않는다")
    void publishingCreatesNotificationForEnabledUsers() {
        long enabledReport = preparedReport(ENABLED, "ACTIVE", true);
        preparedReport(DISABLED, "ACTIVE", false);

        assertThat(publicationService.publishPreparedFinal(PERIOD)).isEqualTo(2);

        assertThat(notificationCount(DISABLED)).isZero();
        assertThat(
                        jdbc.queryForMap(
                                "SELECT type,source_id,target_type,target_id,title,body,"
                                        + " expires_at - notified_at = interval '90 days'"
                                        + " AS kept_90_days"
                                        + " FROM notifications WHERE user_id=?",
                                ENABLED))
                .containsEntry("type", "REPORT_ISSUED")
                .containsEntry("source_id", enabledReport)
                .containsEntry("target_type", "TASTE_REPORT")
                .containsEntry("target_id", enabledReport)
                .containsEntry("title", "취향 리포트 발급")
                .containsEntry("body", "2026년 하반기 취향 리포트가 도착했어요.")
                .containsEntry("kept_90_days", true);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM web_push_deliveries d JOIN notifications n"
                                        + " ON n.id = d.notification_id WHERE n.user_id BETWEEN ?"
                                        + " AND ?",
                                Long.class,
                                BASE,
                                BASE + 999))
                .isZero();
    }

    @Test
    @DisplayName("API-070 목록이 리포트 발급 알림을 종류·대상 그대로 내려준다")
    void listsReportIssuedNotification() throws Exception {
        long report = preparedReport(ENABLED, "ACTIVE", true);
        publicationService.publishPreparedFinal(PERIOD);

        mvc.perform(
                        get("/api/v1/notifications")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokens.createAccessToken(ENABLED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("REPORT_ISSUED"))
                .andExpect(jsonPath("$.items[0].targetType").value("TASTE_REPORT"))
                .andExpect(jsonPath("$.items[0].targetId").value(report))
                .andExpect(jsonPath("$.items[0].title").value("취향 리포트 발급"));
    }

    @Test
    @DisplayName("다시 공개해도 알림이 늘지 않는다")
    void republishingDoesNotDuplicate() {
        preparedReport(ENABLED, "ACTIVE", true);

        publicationService.publishPreparedFinal(PERIOD);
        assertThat(publicationService.publishPreparedFinal(PERIOD)).isZero();

        assertThat(notificationCount(ENABLED)).isOne();
    }

    @Test
    @DisplayName("이미 같은 리포트의 알림이 있으면 공개는 성공하고 알림은 하나로 남는다")
    void existingNotificationIsKept() {
        long report = preparedReport(ENABLED, "ACTIVE", true);
        jdbc.update(
                "INSERT INTO notifications(user_id,type,source_id,target_type,target_id,title,body,"
                        + "notified_at,expires_at) VALUES (?,'REPORT_ISSUED',?,'TASTE_REPORT',?,"
                        + "'이전 제목','이전 본문',now(),now() + interval '1 day')",
                ENABLED,
                report,
                report);

        assertThat(publicationService.publishPreparedFinal(PERIOD)).isOne();

        assertThat(notificationCount(ENABLED)).isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM notifications WHERE user_id=?",
                                String.class,
                                ENABLED))
                .isEqualTo("이전 제목");
    }

    @Test
    @DisplayName("알림 쓰기가 실패하면 공개도 함께 되돌아간다")
    void notificationFailureRollsBackPublication() {
        preparedReport(ENABLED, "ACTIVE", true);
        jdbc.execute(
                """
                CREATE FUNCTION test_fail_report_notification() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.type = 'REPORT_ISSUED' THEN
                        RAISE EXCEPTION 'forced report notification failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute(
                """
                CREATE TRIGGER test_fail_report_notification
                BEFORE INSERT ON notifications
                FOR EACH ROW EXECUTE FUNCTION test_fail_report_notification()
                """);

        assertThatThrownBy(() -> publicationService.publishPreparedFinal(PERIOD))
                .isInstanceOf(DataAccessException.class);

        assertThat(reportStatus(ENABLED)).isEqualTo("GENERATING");
        assertThat(notificationCount(ENABLED)).isZero();
    }

    @Test
    @DisplayName("첫 발급을 기록할 수 없는 시각에 공개되면 알림을 만들지 않는다")
    void skipsNotificationAfterIssueWindow() {
        preparedReport(ENABLED, "ACTIVE", true, CLOSED_PERIOD);

        assertThat(publicationService.publishPreparedFinal(CLOSED_PERIOD)).isOne();

        assertThat(reportStatus(ENABLED)).isEqualTo("READY");
        assertThat(notificationCount(ENABLED)).isZero();
    }

    @Test
    @DisplayName("활성 계정이 아니면 알림을 만들지 않는다")
    void skipsInactiveAccounts() {
        preparedReport(WITHDRAWN, "WITHDRAWN", true);

        publicationService.publishPreparedFinal(PERIOD);

        assertThat(notificationCount(WITHDRAWN)).isZero();
    }

    @Test
    @DisplayName("설정 행이 없는 계정은 기본값(켜짐)으로 본다")
    void missingSettingsRowMeansEnabled() {
        preparedReport(ENABLED, "ACTIVE", true);
        jdbc.update("DELETE FROM notification_settings WHERE user_id=?", ENABLED);

        publicationService.publishPreparedFinal(PERIOD);

        assertThat(notificationCount(ENABLED)).isOne();
    }

    @Test
    @DisplayName("리포트 발급 알림은 리포트만 대상으로 삼는다")
    void reportIssuedTargetsOnlyTasteReport() {
        insertUser(ENABLED, "ACTIVE", true);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO notifications(user_id,type,source_id,"
                                                + "target_type,target_id,title,body,notified_at,"
                                                + "expires_at) VALUES (?,'REPORT_ISSUED',1,'PARTY',"
                                                + "1,'제목','본문',now(),now() + interval '1 day')",
                                        ENABLED))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_notifications_type_target");
    }

    /** 마감 스냅샷으로 준비를 마친 GENERATING 리포트를 만든다. 공개 조건(`publishPreparedFinal`)과 같은 표식을 둔다. */
    private long preparedReport(long userId, String status, boolean reportIssuedEnabled) {
        return preparedReport(userId, status, reportIssuedEnabled, PERIOD);
    }

    private long preparedReport(
            long userId, String status, boolean reportIssuedEnabled, ReportPeriod period) {
        insertUser(userId, status, reportIssuedEnabled);
        String content =
                "{\"generation\":{\"finalSnapshot\":true,\"cutoffAt\":\""
                        + period.cutoffExclusive()
                        + "\"}}";
        return jdbc.queryForObject(
                "INSERT INTO taste_reports(user_id,year,half,status,content)"
                        + " VALUES (?,?,?,'GENERATING',CAST(? AS jsonb)) RETURNING id",
                Long.class,
                userId,
                period.year(),
                period.half().name(),
                content);
    }

    private String reportStatus(long userId) {
        return jdbc.queryForObject(
                "SELECT status FROM taste_reports WHERE user_id=?", String.class, userId);
    }

    private void insertUser(long userId, String status, boolean reportIssuedEnabled) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?)",
                userId,
                "report-notice" + userId + "@example.test",
                "rn" + (userId - BASE),
                status);
        jdbc.update(
                "INSERT INTO notification_settings(user_id,report_issued_enabled) VALUES (?,?)",
                userId,
                reportIssuedEnabled);
    }

    private long notificationCount(long userId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT count(*) AS count FROM notifications"
                                + " WHERE user_id=? AND type='REPORT_ISSUED'",
                        userId);
        return ((Number) row.get("count")).longValue();
    }
}
