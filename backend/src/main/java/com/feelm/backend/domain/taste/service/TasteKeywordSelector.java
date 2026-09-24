package com.feelm.backend.domain.taste.service;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * API-040 취향 키워드를 분석 집계에서 고른다(S15P21E106-834).
 *
 * <p>사전 정의한 규칙마다 임계값을 두고 <b>넘긴 것만</b> 우선순위대로 최대 {@link #MAXIMUM_KEYWORDS}개 고른다. 누구에게나 같은 칩이 뜨지 않게
 * 하려는 것이라 사람마다 개수가 갈린다. {@link #MINIMUM_KEYWORDS}개를 못 채우면 부분 결과를 내지 않고 빈 목록을 돌려준다 — 계약이 2~5개를 요구하고,
 * 비어 있으면 {@code insufficientData: true}로 나가기 때문이다.
 *
 * <p>DB를 읽지도 쓰지도 않는 순수 계산이다. {@code taste_analyses}를 채우는 경로(S15P21E106-775)가 집계를 끝낸 뒤 이 클래스를 불러
 * {@code keywords}만 채운다. 그래서 이 클래스는 저장 경로 없이 단위 테스트만으로 검증된다.
 *
 * <p><b>같은 축에서는 하나만 나간다.</b> 장르에서 {@code GENRE_DEVOTEE}(몰림)가 나가면 {@code GENRE_OMNIVORE}(넓이)를, 국가에서
 * {@code COUNTRY_FOCUS}가 나가면 {@code WORLD_EXPLORER}를 건너뛴다. {@code GENRE_FAVORITE}도 {@code
 * GENRE_DEVOTEE}가 이미 같은 장르를 말했으면 건너뛴다. 편식과 잡식을 나란히 보이면 서로 모순이고, 2026-09-24 실측에서 대상 6명 중 5명에게 그 조합이
 * 실제로 떴다. 남는 자리는 뒤 순위 규칙이 채우므로 사람마다 다른 키워드가 나온다.
 *
 * <p><b>입력 전제.</b> {@code genres}·{@code countries}·{@code eras}는 그 사용자에게 나타난 <b>모든</b> 종류를 담고, 연대
 * 버킷을 {@code 그 이전} 같은 묶음 라벨로 합치지 않는다. {@code GENRE_OMNIVORE}·{@code WORLD_EXPLORER}는 배열 크기로, {@code
 * CLASSIC_DIGGER}는 2000년 이전 버킷의 비중 합으로 판정하기 때문이다. 산출자(S15P21E106-775)가 상위 몇 개만 담거나 버킷을 합치면 이 세 규칙은
 * 예외도 실패도 없이 조용히 사라진다. {@code directors}만 상위 N명으로 자른다 — 1위만 보므로 무해하다.
 *
 * <p>규칙은 취향 리포트의 키워드(S15P21E106-774, {@code ReportContentAssembler})와 같은 틀이지만 <b>별개 규칙</b>이다. 리포트는
 * 반기 집계이고 여기는 전체 이력이라 같은 {@code code}라도 기간과 임계값이 다르다. 두 계약에 그 사실을 적었다.
 *
 * <p><b>임계값은 잠정값이다.</b> 2026-09-24 서버 실측(당시 기준 평가 10편 이상)에서 대상 사용자가 9명뿐이었고, 그중 {@code
 * COUNTRY_FOCUS}와 {@code GENRE_OMNIVORE}는 9명 전원이, {@code ERA_LOVER}는 7명이 넘겨 변별력이 낮았다. 반대로 {@code
 * CLASSIC_DIGGER}는 1명이었다. 새 값을 고를 표본도 없어 초안값을 그대로 두되, 모든 임계값을 이 클래스의 상수로 모아 데이터가 쌓인 뒤 여기만 고치면 되게
 * 했다.
 */
@Component
public class TasteKeywordSelector {

    /**
     * 이 편수 미만이면 비중이 표본을 대표하지 못해 키워드를 만들지 않는다.
     *
     * <p>온보딩 카드 수(10)보다 크게 잡았다. 온보딩(API-024)은 전 사용자 공통의 고정 10편을 일반 평가와 같은 경로로 {@code ratings}에
     * 남기므로, 10으로 두면 카드를 다 고른 사용자 전원이 같은 입력에서 같은 키워드를 받는다. 2026-09-24 실측에서 그 고정 10편은 {@code
     * COUNTRY_FOCUS}·{@code GENRE_OMNIVORE}·{@code ERA_LOVER} 세 개를 넘겨 최소 개수를 정확히 채웠다. 이 상수를 온보딩 카드
     * 수 이하로 다시 내리면 그 경로가 되살아난다.
     */
    static final int MINIMUM_RATINGS = 15;

    /**
     * 계약이 요구하는 개수 범위(API-040). 아래를 못 채우면 빈 목록이다.
     *
     * <p>처음에는 3이었다. 같은 축에서 하나만 내보내기로 하면서 통과 개수가 줄어, 한 장르·한 나라에 몰려 보는 사용자가 2개에서 멈추고 빈 결과가 되었다.
     * 2026-09-24 실측에서 평가 27편인 사용자가 그렇게 아무것도 받지 못했다. 화면을 채우려고 3을 둔 것이지 규칙상 근거가 있던 값이 아니라 2로 내렸다.
     */
    static final int MINIMUM_KEYWORDS = 2;

    static final int MAXIMUM_KEYWORDS = 5;

    /** 1위 감독을 이만큼 봤으면 몰아본 것으로 본다. */
    static final int DIRECTOR_BINGE_FILMS = 4;

    static final BigDecimal GENRE_DEVOTEE_SHARE = new BigDecimal("0.45");

    static final BigDecimal COUNTRY_FOCUS_SHARE = new BigDecimal("0.55");

    static final int GENRE_OMNIVORE_KINDS = 10;

    static final int WORLD_EXPLORER_KINDS = 6;

    /** 2000년 이전 연대 버킷의 비중 합. */
    static final BigDecimal CLASSIC_DIGGER_SHARE = new BigDecimal("0.30");

    static final int CLASSIC_ERA_BEFORE = 2000;

    static final BigDecimal ERA_LOVER_SHARE = new BigDecimal("0.40");

    static final int GENRE_FAVORITE_FILMS = 3;

    static final BigDecimal GENRE_FAVORITE_SCORE = new BigDecimal("4.0");

    static final BigDecimal GENEROUS_SCORE = new BigDecimal("4.3");

    static final BigDecimal STRICT_SCORE = new BigDecimal("3.0");

    /**
     * 우선순위대로 조건을 넘긴 키워드를 고른다.
     *
     * <p>순서는 계약(API-040 처리 규칙)의 표와 같다. 앞쪽일수록 그 사람을 남과 가르는 힘이 세다고 보았고, 평가 습관과 장르 편애는 다른 규칙을 못 넘긴
     * 사람에게도 줄 수 있어 뒤에 둔다. 리포트 키워드와 같은 판단이다.
     *
     * @param ratingCount 그 사용자의 평가 편수. 비중의 분모이기도 하다
     * @param averageScore 전체 평균 별점. {@code analysis}에는 축별 평균만 있어 호출자가 따로 넘긴다
     * @param analysis 장르·국가·시대·감독 집계. 목록 순서에 기대지 않고 1위를 직접 고른다
     * @return 2~5개, 또는 빈 목록
     */
    public List<TasteAnalysis.Keyword> select(
            int ratingCount, BigDecimal averageScore, TasteAnalysis.Analysis analysis) {
        if (analysis == null || ratingCount < MINIMUM_RATINGS) {
            return List.of();
        }
        List<TasteAnalysis.Keyword> passed = evaluate(averageScore, analysis);
        if (passed.size() < MINIMUM_KEYWORDS) {
            return List.of();
        }
        return List.copyOf(passed.subList(0, Math.min(passed.size(), MAXIMUM_KEYWORDS)));
    }

    /**
     * 조건을 넘긴 키워드를 우선순위대로 <b>모두</b> 돌려준다. 개수 규칙({@link #MINIMUM_KEYWORDS}·{@link
     * #MAXIMUM_KEYWORDS})은 적용하지 않는다.
     *
     * <p><b>테스트 전용이다.</b> {@code taste_analyses}를 채우는 경로는 반드시 {@link #select}를 부른다. 이 메서드를 직접 부르면
     * 최소 평가 수도, 2개 미만 빈 목록도, 5개 상한도 전부 건너뛰어 계약이 정한 "사용자당 2~5개"가 깨진다.
     *
     * <p>{@link #select}에서 떼어 둔 이유는 규칙 하나하나를 볼 수 있어야 하기 때문이다. 최소 개수 규칙이 앞에 있으면 규칙 한 개만 넘긴 입력이 빈 목록이
     * 되어, 그 규칙이 도는지 안 도는지 구분할 수 없다. 단위 테스트가 경계값을 이 메서드로 고정한다.
     */
    List<TasteAnalysis.Keyword> evaluate(BigDecimal averageScore, TasteAnalysis.Analysis analysis) {
        List<TasteAnalysis.Keyword> keywords = new ArrayList<>();
        Optional<TasteAnalysis.Genre> topGenre = topGenre(analysis);

        // 01 한 감독을 몰아본 사람
        topDirector(analysis)
                .filter(director -> director.count() >= DIRECTOR_BINGE_FILMS)
                .ifPresent(
                        director -> addNamed(keywords, "DIRECTOR_BINGE", director.name(), " 몰아보기"));

        // 02~03 한 장르·한 나라에 몰렸는지
        boolean genreDevotee =
                topGenre.filter(genre -> atLeast(genre.share(), GENRE_DEVOTEE_SHARE))
                        .map(genre -> addNamed(keywords, "GENRE_DEVOTEE", genre.name(), " 편식"))
                        .orElse(false);
        boolean countryFocus =
                topCountry(analysis)
                        .filter(country -> atLeast(country.share(), COUNTRY_FOCUS_SHARE))
                        .map(
                                country ->
                                        addNamed(
                                                keywords,
                                                "COUNTRY_FOCUS",
                                                country.name(),
                                                " 영화 집중"))
                        .orElse(false);

        // 04~05 반대로 넓게 봤는지. 같은 축에서 몰림형이 이미 나갔으면 건너뛴다.
        if (!genreDevotee && analysis.genres().size() >= GENRE_OMNIVORE_KINDS) {
            add(keywords, "GENRE_OMNIVORE", "장르 잡식");
        }
        if (!countryFocus && analysis.countries().size() >= WORLD_EXPLORER_KINDS) {
            add(keywords, "WORLD_EXPLORER", "세계 영화 탐험");
        }

        // 06~07 시대
        if (atLeast(classicShare(analysis), CLASSIC_DIGGER_SHARE)) {
            add(keywords, "CLASSIC_DIGGER", "고전 발굴가");
        }
        topEra(analysis)
                .filter(era -> atLeast(era.share(), ERA_LOVER_SHARE))
                .ifPresent(era -> addNamed(keywords, "ERA_LOVER", eraLabel(era.label()), " 애호가"));

        // 08~09 앞을 못 넘긴 사람에게도 줄 수 있는 마지막 자리.
        // 편식이 이미 같은 장르를 말했으면 편애는 같은 이름을 한 번 더 쓰는 셈이라 건너뛴다.
        if (!genreDevotee) {
            topGenre.filter(TasteKeywordSelector::isFavorite)
                    .ifPresent(genre -> addNamed(keywords, "GENRE_FAVORITE", genre.name(), " 편애"));
        }
        if (atLeast(averageScore, GENEROUS_SCORE)) {
            add(keywords, "GENEROUS_RATER", "후한 평가자");
        } else if (averageScore != null && averageScore.compareTo(STRICT_SCORE) <= 0) {
            add(keywords, "STRICT_RATER", "깐깐한 평가자");
        }

        return List.copyOf(keywords);
    }

    /** 1위 장르가 여러 편이고 평균도 높은지. 편식과 달리 비중이 아니라 편수와 점수를 본다. */
    private static boolean isFavorite(TasteAnalysis.Genre genre) {
        return genre.count() >= GENRE_FAVORITE_FILMS
                && genre.averageScore() != null
                && genre.averageScore().compareTo(GENRE_FAVORITE_SCORE) >= 0;
    }

    /**
     * 2000년 이전 연대 버킷의 비중 합.
     *
     * <p>라벨을 못 읽는 버킷은 더하지 않는다. 계약이 형식을 {@code 2010s}로 고정했지만, 형식이 어긋난 값이 들어와도 그 규칙만 건너뛰고 나머지 키워드는
     * 그대로 나가는 편이 낫다.
     */
    private static BigDecimal classicShare(TasteAnalysis.Analysis analysis) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TasteAnalysis.Era era : analysis.eras()) {
            Integer year = eraStartYear(era.label());
            if (year != null && year < CLASSIC_ERA_BEFORE && era.share() != null) {
                sum = sum.add(era.share());
            }
        }
        return sum;
    }

    /** {@code 2010s} → {@code 2010년대}. 읽지 못하면 원래 라벨을 그대로 쓴다. */
    private static String eraLabel(String label) {
        Integer year = eraStartYear(label);
        return year == null ? label : year + "년대";
    }

    /** {@code 2010s}의 앞 네 자리. 형식이 다르면 {@code null}이다. */
    private static Integer eraStartYear(String label) {
        if (label == null || !label.matches("\\d{4}s")) {
            return null;
        }
        return Integer.valueOf(label.substring(0, 4));
    }

    private static Optional<TasteAnalysis.Genre> topGenre(TasteAnalysis.Analysis analysis) {
        return analysis.genres().stream()
                .max(
                        Comparator.comparingInt(TasteAnalysis.Genre::count)
                                .thenComparing(TasteAnalysis.Genre::code, codeTieBreak()));
    }

    private static Optional<TasteAnalysis.Country> topCountry(TasteAnalysis.Analysis analysis) {
        return analysis.countries().stream()
                .max(
                        Comparator.comparingInt(TasteAnalysis.Country::count)
                                .thenComparing(TasteAnalysis.Country::code, codeTieBreak()));
    }

    private static Optional<TasteAnalysis.Era> topEra(TasteAnalysis.Analysis analysis) {
        return analysis.eras().stream()
                .max(
                        Comparator.comparingInt(TasteAnalysis.Era::count)
                                .thenComparing(TasteAnalysis.Era::label, codeTieBreak()));
    }

    private static Optional<TasteAnalysis.Director> topDirector(TasteAnalysis.Analysis analysis) {
        return analysis.directors().stream()
                .max(
                        Comparator.comparingInt(TasteAnalysis.Director::count)
                                .thenComparing(
                                        TasteAnalysis.Director::personId,
                                        Comparator.reverseOrder()));
    }

    /**
     * 동점을 <b>키</b>로 갈라 같은 입력이 같은 결과를 내게 한다. 순서가 흔들리면 조회할 때마다 키워드가 달라진다.
     *
     * <p>가르는 키는 보이는 이름이 아니라 장르·국가의 {@code code}와 연대의 {@code label}이다. 감독은 {@code personId}를
     * 쓴다({@link #topDirector}). 이름은 적재 데이터에 따라 바뀔 수 있어 키를 쓴다.
     *
     * <p>{@code max}에 쓰므로 자연 순서를 뒤집는다. 편수가 같으면 <b>키가 작은 쪽</b>이 이기고, 키가 없는 값은 지도록 {@code
     * nullsFirst}를 쓴다. 감독도 같은 방향이라 {@code personId}가 작은 쪽이 이긴다.
     */
    private static Comparator<String> codeTieBreak() {
        return Comparator.nullsFirst(Comparator.<String>reverseOrder());
    }

    private static boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private static void add(List<TasteAnalysis.Keyword> keywords, String code, String label) {
        keywords.add(new TasteAnalysis.Keyword(code, label));
    }

    /**
     * 이름이 들어가는 라벨을 붙인다. 이름이 없으면 <b>그 규칙을 건너뛴다</b>.
     *
     * <p>문자열을 그대로 이으면 {@code null}이 {@code "null 몰아보기"}가 되어 API-040과 공개 프로필(API-062)로 나가고, 저장된 뒤에는
     * 재집계 전까지 남는다. 비중·평균이 없을 때 규칙만 건너뛰는 것과 같은 방향이다.
     *
     * @return 실제로 붙였으면 {@code true}. 같은 축의 배타 판정이 이 값을 쓴다
     */
    private static boolean addNamed(
            List<TasteAnalysis.Keyword> keywords, String code, String name, String suffix) {
        if (name == null || name.isBlank()) {
            return false;
        }
        keywords.add(new TasteAnalysis.Keyword(code, name + suffix));
        return true;
    }
}
