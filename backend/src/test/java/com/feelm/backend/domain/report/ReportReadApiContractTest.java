package com.feelm.backend.domain.report;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("리포트 조회 API — API-059·060")
class ReportReadApiContractTest extends ReportApiContractSupport {

    @Test
    void listRespectsIssueAndPeriodBoundary() throws Exception {
        users();
        report(5_560_101, OWNER, 2029, "H1", "READY");
        report(5_560_102, OWNER, 2029, "H2", "READY");
        report(5_560_103, OWNER, 2030, "H1", "READY");
        report(5_560_104, OTHER, 2030, "H1", "READY");

        clock.set(RELEASE.minusNanos(1));
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].reportId").value(5_560_101))
                .andExpect(jsonPath("$.current.reportId").value(5_560_102));

        clock.set(RELEASE);
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].reportId").value(5_560_102))
                .andExpect(jsonPath("$.items[1].reportId").value(5_560_101))
                .andExpect(jsonPath("$.current.reportId").value(5_560_103))
                .andExpect(jsonPath("$.current.status").value("READY"))
                .andExpect(jsonPath("$.current.issuedAt").isEmpty())
                .andExpect(jsonPath("$.preparing").value(false));
    }

    @Test
    void distinguishesPreparingAndNoEligibleUsers() throws Exception {
        users();
        report(5_560_103, OWNER, 2030, "H1", "GENERATING");
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.current.status").value("GENERATING"))
                .andExpect(jsonPath("$.preparing").value(true));

        jdbc.update("DELETE FROM taste_reports WHERE id = ?", 5_560_103);
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").isEmpty())
                .andExpect(jsonPath("$.preparing").value(true));

        jdbc.update(
                """
                INSERT INTO taste_report_generation_runs
                    (year, half, snapshot_at, prepared_count)
                VALUES (2030, 'H1', ?, 0)
                """,
                Timestamp.from(Instant.parse("2030-06-30T14:51:00Z")));
        mockMvc.perform(get("/api/v1/users/me/reports").header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").isEmpty())
                .andExpect(jsonPath("$.preparing").value(false));
    }

    @Test
    void detailReadsStoredSnapshotAndProtectsOwner() throws Exception {
        users();
        report(5_560_101, OWNER, 2030, "H1", "READY");
        report(5_560_102, OTHER, 2030, "H1", "READY");
        report(5_560_103, OWNER, 2029, "H2", "GENERATING");

        clock.set(RELEASE.minusNanos(1));
        mockMvc.perform(
                        get("/api/v1/reports/{id}", 5_560_101)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        clock.set(RELEASE);
        mockMvc.perform(
                        get("/api/v1/reports/{id}", 5_560_101)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.ratingCount").value(3))
                .andExpect(jsonPath("$.fourCut.genre.name").value("로맨스"))
                .andExpect(jsonPath("$.genreShares[0].share").value(67))
                .andExpect(jsonPath("$.topDirectors[0].count").value(2))
                .andExpect(jsonPath("$.comparisons[0].delta").value(12))
                .andExpect(jsonPath("$.highlights[0]").value("로맨스 취향이 12%p 늘었어요"))
                .andExpect(jsonPath("$.generation").doesNotExist());

        for (long id : new long[] {5_560_102, 5_560_199}) {
            mockMvc.perform(get("/api/v1/reports/{id}", id).header("Authorization", bearer(OWNER)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mockMvc.perform(
                        get("/api/v1/reports/{id}", 5_560_103)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_GENERATING"));
        mockMvc.perform(get("/api/v1/reports/{id}", 5_560_101))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void detailHidesUnpublishedReportRegardlessOfStatus() throws Exception {
        users();
        report(5_560_105, OWNER, 2030, "H2", "GENERATING");

        clock.set(NEXT_RELEASE.minusNanos(1));
        mockMvc.perform(
                        get("/api/v1/reports/{id}", 5_560_105)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        clock.set(NEXT_RELEASE);
        mockMvc.perform(
                        get("/api/v1/reports/{id}", 5_560_105)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_GENERATING"));
    }
}
