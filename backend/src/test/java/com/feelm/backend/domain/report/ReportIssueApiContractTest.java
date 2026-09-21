package com.feelm.backend.domain.report;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("리포트 첫 발급 API — API-080")
class ReportIssueApiContractTest extends ReportApiContractSupport {

    @Test
    void issuesOnceAndReturnsOriginalTimestamp() throws Exception {
        users();
        report(5_560_101, OWNER, 2030, "H1", "READY");

        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", 5_560_101)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedAt").value(RELEASE.toString()));

        clock.set(RELEASE.plusSeconds(90));
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", 5_560_101)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedAt").value(RELEASE.toString()));
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reportId").value(5_560_101))
                .andExpect(jsonPath("$.current.issuedAt").value(RELEASE.toString()));

        var storedIssue =
                jdbc.queryForObject(
                        "SELECT issued_at FROM taste_reports WHERE id = ?",
                        java.sql.Timestamp.class,
                        5_560_101);
        org.assertj.core.api.Assertions.assertThat(storedIssue.toInstant()).isEqualTo(RELEASE);
    }

    @Test
    void rejectsIneligibleIssue() throws Exception {
        users();
        report(5_560_101, OWNER, 2030, "H1", "READY");
        report(5_560_102, OTHER, 2030, "H1", "READY");
        report(5_560_103, OWNER, 2029, "H2", "GENERATING");

        for (long id : new long[] {5_560_102, 5_560_199}) {
            mockMvc.perform(
                            post("/api/v1/reports/{id}/issue", id)
                                    .header("Authorization", bearer(OWNER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", 5_560_103)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_GENERATING"));

        clock.set(RELEASE.minusNanos(1));
        issueConflicts();
        clock.set(NEXT_RELEASE);
        issueConflicts();

        Long unissued =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM taste_reports WHERE id = ? AND issued_at IS NULL",
                        Long.class,
                        5_560_101);
        org.assertj.core.api.Assertions.assertThat(unissued).isOne();

        markIssued(5_560_101);
        clock.set(Instant.parse("2031-01-01T00:00:00Z"));
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", 5_560_101)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuedAt").value(RELEASE.toString()));
    }

    private void issueConflicts() throws Exception {
        mockMvc.perform(
                        post("/api/v1/reports/{id}/issue", 5_560_101)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
