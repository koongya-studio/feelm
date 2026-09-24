package com.feelm.backend.domain.taste.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import com.feelm.backend.domain.taste.entity.TasteAnalysis.Analysis;
import com.feelm.backend.domain.taste.entity.TasteAnalysis.Country;
import com.feelm.backend.domain.taste.entity.TasteAnalysis.Director;
import com.feelm.backend.domain.taste.entity.TasteAnalysis.Era;
import com.feelm.backend.domain.taste.entity.TasteAnalysis.Genre;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** API-040 취향 키워드 산출 규칙(S15P21E106-834). 경계값을 고정해 임계값을 나중에 바꿀 때 무엇이 달라지는지 드러낸다. */
@DisplayName("취향 키워드 산출 — API-040")
class TasteKeywordSelectorTest {

    private static final BigDecimal NEUTRAL_SCORE = new BigDecimal("3.8");

    private final TasteKeywordSelector selector = new TasteKeywordSelector();

    @Test
    @DisplayName("T1 평가가 최소 편수 미만이면 아무 키워드도 만들지 않는다")
    void selectsNothingBelowMinimumRatings() {
        Analysis analysis = strongAnalysis();

        assertThat(selector.select(14, NEUTRAL_SCORE, analysis)).isEmpty();
        assertThat(selector.select(15, NEUTRAL_SCORE, analysis)).isNotEmpty();
        assertThat(selector.select(15, NEUTRAL_SCORE, null)).isEmpty();
        // 온보딩만 마친 사용자(고정 10편)는 최소 편수를 넘지 못한다.
        assertThat(selector.select(10, NEUTRAL_SCORE, analysis)).isEmpty();
    }

    @Test
    @DisplayName("T2 조건을 하나만 넘기면 빈 목록이고, 정확히 두 개를 넘기면 두 개가 나온다")
    void returnsEmptyWhenFewerThanMinimumRulesPass() {
        // 장르 편식(0.47)만 넘고 나머지는 모두 미달인 사용자.
        Analysis oneRule = withEraShare(5, "0.33");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, oneRule)))
                .containsExactly("GENRE_DEVOTEE");
        assertThat(selector.select(15, NEUTRAL_SCORE, oneRule)).isEmpty();

        // 같은 사용자의 1위 연대 비중이 임계를 넘으면 규칙이 둘이 되어 하한을 정확히 채운다.
        Analysis twoRules = withEraShare(7, "0.47");
        assertThat(selector.select(15, NEUTRAL_SCORE, twoRules))
                .hasSize(2)
                .extracting(TasteAnalysis.Keyword::code)
                .containsExactly("GENRE_DEVOTEE", "ERA_LOVER");
    }

    @Test
    @DisplayName("T3 다섯 개를 넘기면 우선순위 앞에서 다섯 개만 남는다")
    void capsAtFiveByPriority() {
        List<TasteAnalysis.Keyword> keywords =
                selector.select(40, new BigDecimal("4.5"), everything());

        assertThat(keywords).hasSize(5);
        assertThat(codes(keywords))
                .containsExactly(
                        "DIRECTOR_BINGE",
                        "GENRE_DEVOTEE",
                        "COUNTRY_FOCUS",
                        "CLASSIC_DIGGER",
                        "ERA_LOVER");
        // 같은 축의 넓이형은 배타 규칙으로 빠지고, 남은 자리를 뒤 순위가 채운다.
        assertThat(codes(keywords)).doesNotContain("GENRE_OMNIVORE", "WORLD_EXPLORER");
        // 그래도 마지막 순위까지는 닿지 않는다.
        assertThat(codes(keywords)).doesNotContain("GENEROUS_RATER");
    }

    @Test
    @DisplayName("T4 1위 감독을 네 편 이상 봤을 때만 몰아보기가 뜬다")
    void picksDirectorBinge() {
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withDirector(4))))
                .contains("DIRECTOR_BINGE");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withDirector(3))))
                .doesNotContain("DIRECTOR_BINGE");
        assertThat(labelOf(selector.evaluate(NEUTRAL_SCORE, withDirector(4)), "DIRECTOR_BINGE"))
                .isEqualTo("봉준호 몰아보기");
    }

    @Test
    @DisplayName("T5 장르 편식은 비중 45%, 잡식은 10종이 경계다")
    void picksGenreDevoteeAndOmnivore() {
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopGenreShare("0.45"))))
                .contains("GENRE_DEVOTEE");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopGenreShare("0.44"))))
                .doesNotContain("GENRE_DEVOTEE");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withGenreKinds(10))))
                .contains("GENRE_OMNIVORE");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withGenreKinds(9))))
                .doesNotContain("GENRE_OMNIVORE");
    }

    @Test
    @DisplayName("T6 국가 집중은 비중 55%, 세계 탐험은 6개국이 경계다")
    void picksCountryRules() {
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopCountryShare("0.55"))))
                .contains("COUNTRY_FOCUS");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopCountryShare("0.54"))))
                .doesNotContain("COUNTRY_FOCUS");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withCountryKinds(6))))
                .contains("WORLD_EXPLORER");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withCountryKinds(5))))
                .doesNotContain("WORLD_EXPLORER");
    }

    @Test
    @DisplayName("T7 연대는 1위 40%와 2000년 이전 합 30%로 가른다")
    void picksEraRules() {
        Analysis classic =
                base(
                        List.of(
                                era("1980s", 4, "0.20"),
                                era("1990s", 3, "0.15"),
                                era("2010s", 5, "0.25")));
        List<TasteAnalysis.Keyword> keywords = selector.evaluate(NEUTRAL_SCORE, classic);
        assertThat(codes(keywords)).contains("CLASSIC_DIGGER");
        assertThat(codes(keywords)).doesNotContain("ERA_LOVER");

        Analysis lover = base(List.of(era("2010s", 9, "0.40"), era("1990s", 2, "0.10")));
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, lover))).contains("ERA_LOVER");
        assertThat(labelOf(selector.evaluate(NEUTRAL_SCORE, lover), "ERA_LOVER"))
                .isEqualTo("2010년대 애호가");

        // 한 칸 아래는 뜨지 않는다. 2000년 이전 합 0.29, 1위 연대 0.39.
        Analysis justBelowClassic =
                base(
                        List.of(
                                era("1980s", 4, "0.20"),
                                era("1990s", 3, "0.09"),
                                era("2010s", 5, "0.25")));
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, justBelowClassic)))
                .doesNotContain("CLASSIC_DIGGER");
        Analysis justBelowLover = base(List.of(era("2010s", 9, "0.39"), era("1990s", 2, "0.10")));
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, justBelowLover)))
                .doesNotContain("ERA_LOVER");
    }

    @Test
    @DisplayName("T8 평균 별점이 4.3 이상이면 후한, 3.0 이하면 깐깐한 평가자다")
    void picksRatingHabit() {
        Analysis analysis = plainAnalysis();

        assertThat(codes(selector.evaluate(new BigDecimal("4.3"), analysis)))
                .contains("GENEROUS_RATER");
        assertThat(codes(selector.evaluate(new BigDecimal("3.0"), analysis)))
                .contains("STRICT_RATER");
        assertThat(codes(selector.evaluate(new BigDecimal("3.5"), analysis)))
                .doesNotContain("GENEROUS_RATER", "STRICT_RATER");
        // 한 칸 차이로 갈린다.
        assertThat(codes(selector.evaluate(new BigDecimal("4.2"), analysis)))
                .doesNotContain("GENEROUS_RATER");
        assertThat(codes(selector.evaluate(new BigDecimal("3.1"), analysis)))
                .doesNotContain("STRICT_RATER");
    }

    @Test
    @DisplayName("T9 장르 편애는 편수 3편과 평균 4.0을 모두 넘겨야 한다")
    void picksGenreFavorite() {
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopGenre(3, "4.0"))))
                .contains("GENRE_FAVORITE");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopGenre(2, "4.5"))))
                .doesNotContain("GENRE_FAVORITE");
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, withTopGenre(5, "3.9"))))
                .doesNotContain("GENRE_FAVORITE");
    }

    @Test
    @DisplayName("T10 값이 없는 축은 건너뛰고, 연대 라벨이 형식과 달라도 비중은 그대로 쓴다")
    void ignoresMissingValuesButKeepsUnknownEraLabel() {
        Analysis broken =
                new Analysis(
                        List.of(genre("THRILLER", "스릴러", 8, null, null)),
                        List.of(),
                        List.of(era("옛날", 5, "0.50"), era(null, 3, "0.30")),
                        List.of());

        List<TasteAnalysis.Keyword> keywords = selector.evaluate(null, broken);

        // 비중·평균이 없으면 그 규칙만 빠진다. 평균 별점이 null이면 평가 습관도 빠진다.
        assertThat(codes(keywords))
                .doesNotContain(
                        "GENRE_DEVOTEE",
                        "GENRE_FAVORITE",
                        "COUNTRY_FOCUS",
                        "GENEROUS_RATER",
                        "STRICT_RATER");
        // 라벨 형식(2010s)을 못 읽으면 연대를 셈에서 빼므로 고전 발굴가는 뜨지 않는다.
        assertThat(codes(keywords)).doesNotContain("CLASSIC_DIGGER");
        // 다만 1위 연대의 비중은 실제 값이라 규칙은 돌고, 라벨은 원래 값을 그대로 쓴다.
        assertThat(codes(keywords)).containsExactly("ERA_LOVER");
        assertThat(labelOf(keywords, "ERA_LOVER")).isEqualTo("옛날 애호가");

        assertThat(selector.evaluate(null, new Analysis(null, null, null, null))).isEmpty();

        // 이름·라벨이 없는 축이 1위여도 "null 애호가" 같은 라벨을 만들지 않고 그 규칙을 건너뛴다.
        Analysis nameless =
                new Analysis(
                        List.of(genre("THRILLER", null, 9, "4.5", "0.50")),
                        List.of(country("KR", "  ", 9, "0.60")),
                        List.of(era(null, 9, "0.50")),
                        List.of(director(1, null, 5)));
        assertThat(selector.evaluate(NEUTRAL_SCORE, nameless)).isEmpty();
    }

    @Test
    @DisplayName("T11 같은 입력은 언제나 같은 코드와 순서를 준다")
    void producesStableResultForSameInput() {
        Analysis analysis = everything();

        List<TasteAnalysis.Keyword> first = selector.select(40, new BigDecimal("4.5"), analysis);
        List<TasteAnalysis.Keyword> second = selector.select(40, new BigDecimal("4.5"), analysis);

        assertThat(first).isEqualTo(second);
        // 같은 데이터라도 배열 순서가 바뀌어 올 수 있다. 1위를 목록 첫 원소로 고르면 여기서 깨진다.
        assertThat(selector.select(40, new BigDecimal("4.5"), reversed(analysis))).isEqualTo(first);
    }

    @Test
    @DisplayName("T12 동점인 1위는 이름 순으로 갈라 결과가 흔들리지 않는다")
    void breaksTiesDeterministically() {
        Analysis analysis =
                new Analysis(
                        List.of(
                                genre("THRILLER", "스릴러", 9, "4.5", "0.45"),
                                genre("ACTION", "액션", 9, "4.5", "0.45")),
                        List.of(country("KR", "한국", 12, "0.60")),
                        List.of(era("2010s", 9, "0.45")),
                        List.of(director(1, "봉준호", 4)));

        assertThat(labelOf(selector.evaluate(NEUTRAL_SCORE, analysis), "GENRE_DEVOTEE"))
                .isEqualTo("액션 편식");
    }

    @Test
    @DisplayName("T13 규칙마다 라벨이 계약의 예시 형태로 채워진다")
    void fillsEveryLabelFromNames() {
        // 몰림형이 나가는 입력: 편식·집중과 뒤 순위 라벨을 본다.
        assertThat(selector.evaluate(new BigDecimal("4.5"), everything()))
                .extracting(TasteAnalysis.Keyword::label)
                .containsExactly(
                        "봉준호 몰아보기", "장르0 편식", "국가0 영화 집중", "고전 발굴가", "2010년대 애호가", "후한 평가자");
        assertThat(labelOf(selector.evaluate(new BigDecimal("2.5"), everything()), "STRICT_RATER"))
                .isEqualTo("깐깐한 평가자");

        // 넓이형이 나가는 입력: 잡식·탐험과 편애 라벨을 본다.
        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, broadAnalysis())))
                .containsExactly("GENRE_OMNIVORE", "WORLD_EXPLORER", "ERA_LOVER", "GENRE_FAVORITE");
        assertThat(selector.evaluate(NEUTRAL_SCORE, broadAnalysis()))
                .extracting(TasteAnalysis.Keyword::label)
                .containsExactly("장르 잡식", "세계 영화 탐험", "2010년대 애호가", "장르0 편애");
    }

    @Test
    @DisplayName("T14 같은 축에서 몰림형이 나가면 넓이형과 편애는 건너뛴다")
    void keepsOnlyOneKeywordPerAxis() {
        // 편식·집중이 모두 조건을 넘기는 입력. 잡식·탐험·편애는 조건을 넘겨도 나가지 않는다.
        Analysis concentrated =
                new Analysis(
                        genres(12, 20, "4.5", "0.50"),
                        countries(8, 24, "0.60"),
                        List.of(era("2010s", 18, "0.45")),
                        List.of(director(1, "봉준호", 2)));

        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, concentrated)))
                .containsExactly("GENRE_DEVOTEE", "COUNTRY_FOCUS", "ERA_LOVER");

        // 몰림형이 한 칸 차이로 미달이면 같은 입력에서 넓이형과 편애가 되살아난다.
        Analysis broad =
                new Analysis(
                        genres(12, 20, "4.5", "0.44"),
                        countries(8, 24, "0.54"),
                        List.of(era("2010s", 18, "0.45")),
                        List.of(director(1, "봉준호", 2)));

        assertThat(codes(selector.evaluate(NEUTRAL_SCORE, broad)))
                .containsExactly("GENRE_OMNIVORE", "WORLD_EXPLORER", "ERA_LOVER", "GENRE_FAVORITE");
    }

    // --- 픽스처 -----------------------------------------------------------

    /**
     * 세 규칙 이상을 넘겨 빈 목록이 되지 않는 기본 분석. 평가 15편 기준이며 비중은 계약대로 {@code count / ratingCount}와 맞춘다. 장르는 한
     * 영화가 여러 장르를 가져 합이 1을 넘을 수 있다.
     */
    private static Analysis strongAnalysis() {
        return new Analysis(
                List.of(genre("THRILLER", "스릴러", 15, "4.2", "1.00")),
                List.of(country("KR", "한국", 9, "0.60")),
                List.of(era("2010s", 8, "0.53")),
                List.of(director(1, "봉준호", 4)));
    }

    /** 평가 15편 기준. 장르 편식만 늘 넘기고, 1위 연대 비중으로 두 번째 규칙을 켠다. */
    private static Analysis withEraShare(int count, String share) {
        return new Analysis(
                List.of(genre("THRILLER", "스릴러", 7, "3.5", "0.47")),
                List.of(country("KR", "한국", 5, "0.33")),
                List.of(era("2010s", count, share)),
                List.of(director(1, "봉준호", 2)));
    }

    /** 넓이형만 넘기는 분석. 잡식·탐험·편애 라벨을 볼 때 쓴다. */
    private static Analysis broadAnalysis() {
        return new Analysis(
                genres(12, 20, "4.5", "0.44"),
                countries(8, 24, "0.54"),
                List.of(era("2010s", 18, "0.45")),
                List.of(director(1, "봉준호", 2)));
    }

    /** 같은 데이터를 배열 순서만 뒤집어 준다. */
    private static Analysis reversed(Analysis analysis) {
        return new Analysis(
                reverse(analysis.genres()),
                reverse(analysis.countries()),
                reverse(analysis.eras()),
                reverse(analysis.directors()));
    }

    private static <T> List<T> reverse(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    /** 어떤 규칙도 넘기지 않는 밋밋한 분석. 평가 습관만 따로 볼 때 쓴다. */
    private static Analysis plainAnalysis() {
        return new Analysis(
                List.of(genre("THRILLER", "스릴러", 3, "3.5", "0.20")),
                List.of(country("KR", "한국", 4, "0.30")),
                List.of(era("2010s", 4, "0.25")),
                List.of(director(1, "봉준호", 2)));
    }

    /** 아홉 규칙을 모두 넘기는 분석. 잘라내기와 순서를 볼 때 쓴다. */
    private static Analysis everything() {
        return new Analysis(
                genres(12, 20, "4.5", "0.50"),
                countries(8, 24, "0.60"),
                List.of(era("1980s", 8, "0.20"), era("1990s", 6, "0.15"), era("2010s", 18, "0.45")),
                List.of(director(1, "봉준호", 6)));
    }

    private static Analysis base(List<Era> eras) {
        return new Analysis(
                List.of(genre("THRILLER", "스릴러", 3, "3.5", "0.20")),
                List.of(country("KR", "한국", 4, "0.30")),
                eras,
                List.of(director(1, "봉준호", 2)));
    }

    private static Analysis withDirector(int films) {
        return new Analysis(
                strongAnalysis().genres(),
                strongAnalysis().countries(),
                strongAnalysis().eras(),
                List.of(director(1, "봉준호", films)));
    }

    private static Analysis withTopGenreShare(String share) {
        return new Analysis(
                List.of(genre("THRILLER", "스릴러", 9, "3.5", share)),
                strongAnalysis().countries(),
                strongAnalysis().eras(),
                strongAnalysis().directors());
    }

    private static Analysis withTopGenre(int count, String averageScore) {
        return new Analysis(
                List.of(genre("THRILLER", "스릴러", count, averageScore, "0.20")),
                strongAnalysis().countries(),
                strongAnalysis().eras(),
                strongAnalysis().directors());
    }

    private static Analysis withGenreKinds(int kinds) {
        return new Analysis(
                genres(kinds, 4, "3.5", "0.20"),
                strongAnalysis().countries(),
                strongAnalysis().eras(),
                strongAnalysis().directors());
    }

    private static Analysis withTopCountryShare(String share) {
        return new Analysis(
                strongAnalysis().genres(),
                List.of(country("KR", "한국", 9, share)),
                strongAnalysis().eras(),
                strongAnalysis().directors());
    }

    private static Analysis withCountryKinds(int kinds) {
        return new Analysis(
                strongAnalysis().genres(),
                countries(kinds, 3, "0.20"),
                strongAnalysis().eras(),
                strongAnalysis().directors());
    }

    private static List<Genre> genres(int kinds, int topCount, String score, String topShare) {
        List<Genre> genres = new ArrayList<>();
        genres.add(genre("G00", "장르0", topCount, score, topShare));
        for (int i = 1; i < kinds; i++) {
            genres.add(genre("G%02d".formatted(i), "장르" + i, 1, "3.0", "0.05"));
        }
        return List.copyOf(genres);
    }

    private static List<Country> countries(int kinds, int topCount, String topShare) {
        List<Country> countries = new ArrayList<>();
        countries.add(country("C0", "국가0", topCount, topShare));
        for (int i = 1; i < kinds; i++) {
            countries.add(country("C" + i, "국가" + i, 1, "0.05"));
        }
        return List.copyOf(countries);
    }

    private static Genre genre(
            String code, String name, int count, String averageScore, String share) {
        return new Genre(code, name, count, decimal(averageScore), decimal(share));
    }

    private static Country country(String code, String name, int count, String share) {
        return new Country(code, name, count, new BigDecimal("3.5"), decimal(share));
    }

    private static Era era(String label, int count, String share) {
        return new Era(label, count, new BigDecimal("3.5"), decimal(share));
    }

    private static Director director(long personId, String name, int count) {
        return new Director(personId, name, count, new BigDecimal("4.0"));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static List<String> codes(List<TasteAnalysis.Keyword> keywords) {
        return keywords.stream().map(TasteAnalysis.Keyword::code).toList();
    }

    private static String labelOf(List<TasteAnalysis.Keyword> keywords, String code) {
        return keywords.stream()
                .filter(keyword -> keyword.code().equals(code))
                .map(TasteAnalysis.Keyword::label)
                .findFirst()
                .orElse(null);
    }
}
