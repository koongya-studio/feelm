package com.feelm.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReportContentAssemblerTest {

    @Test
    @DisplayName("TMDb 장르 코드는 영문 이름으로 표시하고 모르는 코드는 기존 이름으로 대체한다")
    void genreDisplayNameHandlesTmdbCodes() {
        assertThat(ReportContentAssembler.genreDisplayName("TMDB_10749")).isEqualTo("ROMANCE");
        assertThat(ReportContentAssembler.genreDisplayName("TMDB_18")).isEqualTo("DRAMA");
        assertThat(ReportContentAssembler.genreDisplayName("TMDB_999999")).isNull();
        assertThat(ReportContentAssembler.genreDisplayName("ACTION")).isEqualTo("ACTION");
    }
}
