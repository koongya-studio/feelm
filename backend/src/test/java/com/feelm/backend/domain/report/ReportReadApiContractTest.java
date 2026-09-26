package com.feelm.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.preparing").value(false));

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
                .andExpect(jsonPath("$.fourCut.genre.displayName").value("ROMANCE"))
                .andExpect(jsonPath("$.genreShares[0].share").value(67))
                .andExpect(jsonPath("$.topDirectors[0].count").value(2))
                .andExpect(jsonPath("$.comparisons[0].delta").value(12))
                .andExpect(jsonPath("$.keywords[0].code").value("GENRE_FAVORITE"))
                .andExpect(jsonPath("$.keywords[0].rationale").value("로맨스 2편 평균 ★4.0"))
                .andExpect(jsonPath("$.highlights[0]").value("다크초코 취향이 12%p 늘었어요"))
                // 캐릭터 카드는 code만 계약이고 이름·설명은 기준정보다(API-060, 업무 -618)
                .andExpect(jsonPath("$.characterCard.code").value("RDMV"))
                .andExpect(jsonPath("$.characterCard.name").value("안락의자 탐정"))
                .andExpect(jsonPath("$.characterCard.tagline").value("익숙한 자리에서 잔뜩 봤고, 별점은 냉정했어요"))
                .andExpect(jsonPath("$.characterCard.axes", hasSize(4)))
                .andExpect(jsonPath("$.characterCard.axes[0].axis").value("EXPLORATION"))
                .andExpect(jsonPath("$.characterCard.axes[3].label").value("많음"))
                .andExpect(jsonPath("$.characterCard.basis.frameCount").value(3))
                .andExpect(jsonPath("$.characterCard.basis.lowSample").value(true))
                // 04 칸 그림은 저장소 에셋이라 계약이 이미지 URL을 내리지 않는다
                .andExpect(jsonPath("$.characterCard.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.fourCut.character.name").value("안락의자 탐정"))
                .andExpect(jsonPath("$.fourCut.character.image").doesNotExist())
                // 판정 기준 스냅샷은 저장하지만 응답에 내리지 않는다
                .andExpect(jsonPath("$.characterAxisMedians").doesNotExist())
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
    void detailFillsMissingStoredGenreDisplayName() throws Exception {
        users();
        long reportId = 5_560_105;
        report(reportId, OWNER, 2030, "H1", "READY");
        jdbc.update(
                """
                UPDATE taste_reports
                SET content = jsonb_set(
                    jsonb_set(content, '{topGenres,0,code}', '"TMDB_10749"'::jsonb),
                    '{fourCut,genre,displayName}', 'null'::jsonb)
                WHERE id = ?
                """,
                reportId);

        mockMvc.perform(
                        get("/api/v1/reports/{id}", reportId)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topGenres[0].code").value("TMDB_10749"))
                .andExpect(jsonPath("$.fourCut.genre.name").value("로맨스"))
                .andExpect(jsonPath("$.fourCut.genre.displayName").value("ROMANCE"));

        assertThat(
                        jdbc.queryForObject(
                                "SELECT content #>> '{fourCut,genre,displayName}'"
                                        + " FROM taste_reports WHERE id = ?",
                                String.class,
                                reportId))
                .isNull();
    }

    @Test
    void detailCanonicalizesStoredPersonDisplayNames() throws Exception {
        users();
        long reportId = 5_560_106;
        long directorId = 5_560_030;
        long actorId = 5_560_040;
        report(reportId, OWNER, 2030, "H1", "READY");
        // 한국 인물은 원어명이 없어 NULL로 남고, 외국 인물만 영문이 붙는다.
        insertPerson(directorId, "봉준호", null);
        // 현재 name이 이미 라틴으로 바뀌어도 기존 READY JSON의 한글 카드는 승인 원어명으로 보정한다.
        insertPerson(actorId, "Mark Hamill", "Mark Hamill");
        jdbc.update(
                """
                UPDATE taste_reports
                SET content = jsonb_set(
                    jsonb_set(
                        jsonb_set(content, '{topDirectors,0,personId}', to_jsonb(?::bigint)),
                        '{topActors,0,personId}', to_jsonb(?::bigint)),
                    '{fourCut,director,name}', '"봉준호"'::jsonb)
                WHERE id = ?
                """,
                directorId,
                actorId,
                reportId);
        jdbc.update(
                """
                UPDATE taste_reports
                SET content = jsonb_set(
                    jsonb_set(content, '{topDirectors,0,name}', '"봉준호"'::jsonb),
                    '{fourCut,actor,name}', '"마크 해밀"'::jsonb)
                WHERE id = ?
                """,
                reportId);
        jdbc.update(
                """
                UPDATE taste_reports
                SET content = jsonb_set(
                    jsonb_set(content, '{topActors,0,name}', '"마크 해밀"'::jsonb),
                    '{fourCut,director,displayName}', '"Stale Korean Name"'::jsonb)
                WHERE id = ?
                """,
                reportId);

        try {
            mockMvc.perform(
                            get("/api/v1/reports/{id}", reportId)
                                    .header("Authorization", bearer(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fourCut.director").exists())
                    .andExpect(jsonPath("$.fourCut.director.name").value("봉준호"))
                    .andExpect(jsonPath("$.fourCut.director.displayName").doesNotExist())
                    .andExpect(jsonPath("$.fourCut.actor.name").value("마크 해밀"))
                    .andExpect(jsonPath("$.fourCut.actor.displayName").value("Mark Hamill"));

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT (content #>> '{fourCut,director,displayName}')"
                                            + " || '|' || coalesce(content #>>"
                                            + " '{fourCut,actor,displayName}', '<NULL>')"
                                            + " FROM taste_reports WHERE id = ?",
                                    String.class,
                                    reportId))
                    .isEqualTo("Stale Korean Name|<NULL>");
        } finally {
            jdbc.update("DELETE FROM people WHERE id IN (?, ?)", directorId, actorId);
        }
    }

    @Test
    void detailFillsDirectorDisplayNameAndKeepsCardFields() throws Exception {
        users();
        long reportId = 5_560_108;
        long directorId = 5_560_031;
        report(reportId, OWNER, 2030, "H1", "READY");
        insertPerson(directorId, "웨스 앤더슨", "Wes Anderson");
        jdbc.update(
                """
                UPDATE taste_reports
                SET content = jsonb_set(
                    jsonb_set(
                        jsonb_set(content, '{topDirectors,0,personId}', to_jsonb(?::bigint)),
                        '{topDirectors,0,name}', '"웨스 앤더슨"'::jsonb),
                    '{fourCut,director}',
                    '{"name": "웨스 앤더슨", "displayName": null,
                       "image": {"url": "https://images.test/d.jpg"},
                       "work": {"url": "https://images.test/work.jpg"}}'::jsonb)
                WHERE id = ?
                """,
                directorId,
                reportId);

        try {
            mockMvc.perform(
                            get("/api/v1/reports/{id}", reportId)
                                    .header("Authorization", bearer(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fourCut.director.name").value("웨스 앤더슨"))
                    .andExpect(jsonPath("$.fourCut.director.displayName").value("Wes Anderson"))
                    // 재조립이 나머지 인자를 유실하거나 뒤바꾸지 않아야 한다.
                    .andExpect(
                            jsonPath("$.fourCut.director.image.url")
                                    .value("https://images.test/d.jpg"))
                    .andExpect(
                            jsonPath("$.fourCut.director.work.url")
                                    .value("https://images.test/work.jpg"));
        } finally {
            jdbc.update("DELETE FROM people WHERE id = ?", directorId);
        }
    }

    @Test
    void detailKeepsEmptyFourCutCards() throws Exception {
        users();
        long reportId = 5_560_107;
        report(reportId, OWNER, 2030, "H1", "READY");
        // 감독·배우 크레딧이 없는 사용자는 카드 자체가 null로 저장된다.
        jdbc.update(
                """
                UPDATE taste_reports
                SET content = jsonb_set(
                    jsonb_set(content, '{fourCut,director}', 'null'::jsonb),
                    '{fourCut,actor}', 'null'::jsonb)
                WHERE id = ?
                """,
                reportId);

        mockMvc.perform(
                        get("/api/v1/reports/{id}", reportId)
                                .header("Authorization", bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fourCut.director").doesNotExist())
                .andExpect(jsonPath("$.fourCut.actor").doesNotExist())
                .andExpect(jsonPath("$.fourCut.genre.displayName").value("ROMANCE"));
    }

    private void insertPerson(long id, String name, String originalName) {
        jdbc.update(
                "INSERT INTO people (id, tmdb_person_id, name, original_name)"
                        + " VALUES (?, ?, ?, ?)",
                id,
                Math.toIntExact(id),
                name,
                originalName);
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
