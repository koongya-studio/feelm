package com.feelm.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportContent.Keyword;
import com.feelm.backend.domain.report.model.ReportContent.Share;
import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.model.ReportSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    @Test
    @DisplayName("외국 인물만 영문을 병기하고 한국 인물은 한 줄로 둔다")
    void personDisplayNameOnlyForForeignPeople() {
        assertThat(ReportContentAssembler.personDisplayName("마크 해밀", "Mark Hamill"))
                .isEqualTo("Mark Hamill");
        // 한국 인물은 적재에서 제외되어 원어명이 없다(시안: 봉준호는 한글 한 줄).
        assertThat(ReportContentAssembler.personDisplayName("봉준호", null)).isNull();
        // ko-KR 번역이 없어 이름이 이미 영문인 인물도 병기할 것이 없다.
        assertThat(ReportContentAssembler.personDisplayName("Geoffrey Arend", "Geoffrey Arend"))
                .isNull();
        assertThat(ReportContentAssembler.personDisplayName("봉준호", "  ")).isNull();
    }

    @Test
    @DisplayName("장르와 감독 대표 이미지는 사진이 있는 작품 중 평점·최신순으로 고른다")
    void representativeImagesPreferHighestRatedAvailableVisual() {
        ReportSource.Frame noImage =
                new ReportSource.Frame(
                        1, 1, 1, new BigDecimal("5.0"), IN_PERIOD, 2026, null, null, null, null);
        ReportSource.Frame visual =
                new ReportSource.Frame(
                        2,
                        1,
                        2,
                        new BigDecimal("4.5"),
                        IN_PERIOD.plusSeconds(1),
                        2026,
                        null,
                        null,
                        "https://image.tmdb.org/t/p/original/poster.jpg",
                        "https://image.tmdb.org/t/p/original/backdrop.jpg");
        ReportContent content =
                new ReportContentAssembler()
                        .assemble(
                                PERIOD,
                                PERIOD.cutoffExclusive(),
                                IN_PERIOD,
                                true,
                                List.of(noImage, visual),
                                List.of(
                                        new ReportSource.Genre(1, "TMDB_18", "드라마"),
                                        new ReportSource.Genre(2, "TMDB_18", "드라마")),
                                List.of(),
                                List.of(
                                        new ReportSource.Person(
                                                1,
                                                10,
                                                "감독",
                                                null,
                                                "https://image.tmdb.org/t/p/original/director.jpg",
                                                ReportSource.Role.DIRECTOR),
                                        new ReportSource.Person(
                                                2,
                                                10,
                                                "감독",
                                                null,
                                                "https://image.tmdb.org/t/p/original/director.jpg",
                                                ReportSource.Role.DIRECTOR)),
                                false,
                                CharacterCardSelector.medians(List.of()));

        assertThat(content.fourCut().genre().image().url()).endsWith("/backdrop.jpg");
        assertThat(content.fourCut().director().work().url()).endsWith("/backdrop.jpg");
    }

    private static final ReportPeriod PERIOD = new ReportPeriod(2026, ReportHalf.H1);
    private static final Instant IN_PERIOD = Instant.parse("2026-02-01T00:00:00Z");

    private static ReportSource.Frame frame(long id, Integer releaseYear) {
        return new ReportSource.Frame(
                id, 1L, id, new BigDecimal("4.0"), IN_PERIOD, releaseYear, null, null, null, null);
    }

    private static ReportContent assemble(List<ReportSource.Frame> frames) {
        return new ReportContentAssembler()
                .assemble(
                        PERIOD,
                        PERIOD.cutoffExclusive(),
                        IN_PERIOD,
                        true,
                        frames,
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        // 이 테스트는 시대·키워드만 본다. 카드 축은 고정 임계값 폴백을 쓴다(업무 -779)
                        CharacterCardSelector.medians(List.of()));
    }

    @Test
    @DisplayName("시대는 상위 두 연대만 이름을 쓰고 나머지를 `그 이전` 한 줄로 합친다")
    void eraSharesFoldTailIntoOneRow() {
        // 2010년대 3편 · 2020년대 2편 · 2000년대 2편 · 1990년대 1편 = 8편
        ReportContent content =
                assemble(
                        List.of(
                                frame(1, 2011),
                                frame(2, 2012),
                                frame(3, 2013),
                                frame(4, 2021),
                                frame(5, 2022),
                                frame(6, 2001),
                                frame(7, 2002),
                                frame(8, 1995)));

        assertThat(content.eraShares())
                .containsExactly(
                        new Share("2010년대", 38),
                        new Share("2020년대", 25),
                        // 2000년대 2편 + 1990년대 1편 = 3편 → 38%
                        new Share("그 이전", 38));
    }

    @Test
    @DisplayName("합칠 나머지 연대가 없으면 `그 이전` 줄을 넣지 않는다")
    void eraSharesOmitRestRowWhenNothingLeft() {
        ReportContent content = assemble(List.of(frame(1, 2011), frame(2, 2021), frame(3, 2022)));

        assertThat(content.eraShares())
                .containsExactly(new Share("2020년대", 67), new Share("2010년대", 33));
    }

    @Test
    @DisplayName("개봉 연도가 없는 영화는 시대 줄에 세지 않되 분모에는 남는다")
    void eraSharesKeepUnknownYearInDenominator() {
        ReportContent content =
                assemble(List.of(frame(1, 2011), frame(2, 2012), frame(3, null), frame(4, null)));

        // 2010년대 2편이지만 분모는 4편이라 50%다
        assertThat(content.eraShares()).containsExactly(new Share("2010년대", 50));
    }

    @Test
    @DisplayName("4컷 감독 카드는 외국 인물만 영문을 병기하고 한국 인물은 비워 둔다")
    void fourCutDirectorCarriesOriginalNameOnlyForForeignPeople() {
        ReportContent foreign =
                assemble(
                        List.of(scored(1, "4.5", 2011)),
                        List.of(director(1, 100, "드니 빌뇌브", "Denis Villeneuve")));
        assertThat(foreign.fourCut().director().name()).isEqualTo("드니 빌뇌브");
        assertThat(foreign.fourCut().director().displayName()).isEqualTo("Denis Villeneuve");

        // 한국 인물은 적재에서 제외되어 원어명이 없다(시안: 봉준호는 한글 한 줄).
        ReportContent korean =
                assemble(List.of(scored(1, "4.5", 2011)), List.of(director(1, 100, "봉준호")));
        assertThat(korean.fourCut().director().name()).isEqualTo("봉준호");
        assertThat(korean.fourCut().director().displayName()).isNull();
    }

    private static ReportSource.Frame scored(long id, String score, Integer releaseYear) {
        return new ReportSource.Frame(
                id, 1L, id, new BigDecimal(score), IN_PERIOD, releaseYear, null, null, null, null);
    }

    private static ReportSource.Person director(long frameId, long personId, String name) {
        return director(frameId, personId, name, null);
    }

    private static ReportSource.Person director(
            long frameId, long personId, String name, String originalName) {
        return new ReportSource.Person(
                frameId, personId, name, originalName, null, ReportSource.Role.DIRECTOR);
    }

    private static ReportContent assemble(
            List<ReportSource.Frame> frames, List<ReportSource.Person> people) {
        return new ReportContentAssembler()
                .assemble(
                        PERIOD,
                        PERIOD.cutoffExclusive(),
                        IN_PERIOD,
                        true,
                        frames,
                        List.of(),
                        List.of(),
                        people,
                        false,
                        CharacterCardSelector.medians(List.of()));
    }

    private static List<String> codes(ReportContent content) {
        return content.keywords().stream().map(Keyword::code).toList();
    }

    @Test
    @DisplayName("취향 키워드는 임계값을 넘긴 규칙만 순서대로 최대 넷을 만든다")
    void keywordsPickRulesThatClearTheirThreshold() {
        // 한 감독 3편 · 평균 ★4.7 · 2000년 이전 3편(50%)
        ReportContent content =
                assemble(
                        List.of(
                                scored(1, "5.0", 1995),
                                scored(2, "5.0", 1998),
                                scored(3, "4.5", 1999),
                                scored(4, "4.5", 2021),
                                scored(5, "4.5", 2022),
                                scored(6, "4.5", 2023)),
                        List.of(
                                director(1, 100, "봉준호"),
                                director(2, 100, "봉준호"),
                                director(3, 100, "봉준호")));

        assertThat(codes(content))
                .containsExactly("DIRECTOR_BINGE", "CLASSIC_DIGGER", "ERA_LOVER", "GENEROUS_RATER");
        assertThat(content.keywords().getFirst())
                .isEqualTo(new Keyword("DIRECTOR_BINGE", "봉준호 몰아보기", "봉준호 영화 3편"));
        assertThat(content.keywords().get(1).rationale()).isEqualTo("2006년 이전 영화가 50%");
    }

    @Test
    @DisplayName("임계값을 하나도 못 넘기면 취향 키워드가 비고 화면이 칸을 숨긴다")
    void keywordsAreEmptyWhenNothingStandsOut() {
        // 세 연대가 3편씩(각 33%)이라 어느 연대도 40%를 못 넘고, 2007년 이후뿐이라 고전도
        // 아니며, 2025년 이후가 없어 신작도 아니다. 평균 ★3.5는 후하지도 깐깐하지도 않고
        // 한 감독이 2편씩이라 몰아보기도 처음 본 감독 3명도 아니다.
        ReportContent content =
                assemble(
                        List.of(
                                scored(1, "3.5", 2011),
                                scored(2, "3.5", 2013),
                                scored(3, "3.5", 2015),
                                scored(4, "3.5", 2020),
                                scored(5, "3.5", 2021),
                                scored(6, "3.5", 2022),
                                scored(7, "3.5", 2007),
                                scored(8, "3.5", 2008),
                                scored(9, "3.5", 2009)),
                        List.of(
                                director(1, 100, "감독 하나"),
                                director(2, 100, "감독 하나"),
                                director(3, 200, "감독 둘"),
                                director(4, 200, "감독 둘")));

        assertThat(content.keywords()).isEmpty();
    }

    @Test
    @DisplayName("깐깐하게 준 반기는 후한 평가자 대신 깐깐한 눈이 뜬다")
    void keywordsMarkStrictRater() {
        ReportContent content =
                assemble(List.of(scored(1, "2.0", 2021), scored(2, "3.0", 2022)), List.of());

        assertThat(codes(content)).contains("STRICT_RATER");
        assertThat(codes(content)).doesNotContain("GENEROUS_RATER");
    }
}
