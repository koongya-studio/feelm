package com.feelm.backend.domain.taste.repository;

import static java.util.Map.entry;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 한 사용자의 평가에서 API-040 분석 4축을 집계한다(S15P21E106-775).
 *
 * <p>{@code movies}·{@code movie_genres}·{@code movie_countries}·{@code movie_people}은 Entity가 없고
 * 콘텐츠 도메인이 적재 경로에서 직접 다루므로 Native Query를 쓴다(컨벤션 9장). 읽기만 하고 쓰지 않는다.
 *
 * <p>다섯 질의 모두 잠금을 걸지 않는 평범한 SELECT다. 스냅샷만 읽으므로 호출자의 평가 쓰기 트랜잭션에 새 교착 고리를 만들지 않는다. 다만 호출 시점에 이미
 * {@code users}·{@code movies} 행 잠금을 쥐고 있어 질의가 느리면 그 잠금을 쥔 시간이 늘어난다.
 *
 * <p><b>계약(API-040 처리 규칙)이 요구하는 전제를 여기서 지킨다.</b>
 *
 * <ul>
 *   <li>{@code genres}·{@code countries}·{@code eras}는 나타난 <b>모든</b> 종류를 담는다. 상위 N개로 자르지 않는다.
 *       {@code GENRE_OMNIVORE}·{@code WORLD_EXPLORER}가 종류 수를, {@code CLASSIC_DIGGER}가 2000년 이전 비중
 *       합을 보기 때문이다(`TasteKeywordSelector`).
 *   <li>{@code directors}만 {@link #TOP_DIRECTORS}명으로 자른다. 규칙이 1위만 보므로 안전하다.
 *   <li>비중의 분모는 평가 편수다. 영화 하나가 장르를 여럿 가져 장르 비중 합은 1을 넘을 수 있다.
 *   <li>연대는 10년 버킷을 {@code 2010s} 형식으로 쓰고 버킷을 합치지 않는다. 개봉일이 없는 영화는 어느 버킷에도 넣지 않되 분모에는 남는다.
 * </ul>
 */
@Repository
public class TasteAnalysisAggregateRepository {

    /** 감독은 규칙이 1위만 보므로 화면이 쓸 만큼만 담는다(S15P21E106-775 U3). */
    static final int TOP_DIRECTORS = 5;

    /**
     * 비중의 소수 자릿수.
     *
     * <p>두 자리로 두면 {@code CLASSIC_DIGGER}가 2000년 이전 버킷의 비중을 <b>더해서</b> 판정하므로 버킷 수만큼 반올림 오차가 쌓인다. 평가
     * 200편에 1990s·1980s·1970s 각 19편과 1960s 2편이면 참값은 0.295로 미달인데 두 자리 합은 0.31이 되어 발화한다. 네 자리면 버킷당
     * 오차가 0.00005 이하라 임계값 판정을 뒤집지 못한다.
     */
    private static final int SHARE_SCALE = 4;

    /** 평균 별점의 소수 자릿수. 저장소 선례({@code movies.average_rating})와 백필 SQL이 같은 자리를 쓴다. */
    private static final int SCORE_SCALE = 2;

    /**
     * ISO 표준에 없거나 JDK가 한국어 이름을 주지 못하는 코드. 표기는 {@code MovieFilterOptionsCountryNames}와 맞춘다. {@code
     * KR}은 API 계약이 {@code 한국}으로 고정했다.
     */
    private static final Map<String, String> COUNTRY_OVERRIDES =
            Map.ofEntries(
                    entry("KR", "한국"),
                    entry("AN", "네덜란드령 안틸레스"),
                    entry("CS", "세르비아 몬테네그로"),
                    entry("SU", "소련"),
                    entry("XC", "체코슬로바키아"),
                    entry("XG", "동독"),
                    entry("XI", "북아일랜드"),
                    entry("XK", "코소보"),
                    entry("YU", "유고슬라비아"),
                    entry("ZR", "자이르"));

    private static final Set<String> ISO_CODES = Set.of(Locale.getISOCountries());

    private static final String SELECT_BASE =
            """
            SELECT count(*) AS rating_count, avg(r.score) AS average_score
            FROM ratings r
            WHERE r.user_id = :userId
            """;

    private static final String SELECT_GENRES =
            """
            SELECT g.code AS code, g.name AS name,
                   count(*) AS film_count, avg(r.score) AS average_score
            FROM ratings r
            JOIN movie_genres mg ON mg.movie_id = r.movie_id
            JOIN genres g ON g.id = mg.genre_id
            WHERE r.user_id = :userId
            GROUP BY g.code, g.name
            ORDER BY count(*) DESC, g.code ASC
            """;

    private static final String SELECT_COUNTRIES =
            """
            SELECT mc.country_code AS code,
                   count(*) AS film_count, avg(r.score) AS average_score
            FROM ratings r
            JOIN movie_countries mc ON mc.movie_id = r.movie_id
            WHERE r.user_id = :userId
            GROUP BY mc.country_code
            ORDER BY count(*) DESC, mc.country_code ASC
            """;

    private static final String SELECT_ERAS =
            """
            SELECT (extract(year FROM m.release_date)::int / 10) * 10 AS era_start,
                   count(*) AS film_count, avg(r.score) AS average_score
            FROM ratings r
            JOIN movies m ON m.id = r.movie_id
            WHERE r.user_id = :userId AND m.release_date IS NOT NULL
            GROUP BY 1
            ORDER BY count(*) DESC, 1 ASC
            """;

    private static final String SELECT_DIRECTORS =
            """
            SELECT p.id AS person_id, p.name AS name,
                   count(*) AS film_count, avg(r.score) AS average_score
            FROM ratings r
            JOIN movie_people mp ON mp.movie_id = r.movie_id AND mp.role = 'DIRECTOR'
            JOIN people p ON p.id = mp.person_id
            WHERE r.user_id = :userId
            GROUP BY p.id, p.name
            ORDER BY count(*) DESC, p.id ASC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TasteAnalysisAggregateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 그 사용자의 평가 전체를 집계한다. 평가가 하나도 없으면 {@link Aggregate#isEmpty()}가 참인 값을 돌려준다.
     *
     * @param userId 대상 사용자
     */
    public Aggregate aggregate(long userId) {
        Map<String, Object> params = Map.of("userId", userId);
        Base base =
                jdbc.queryForObject(
                        SELECT_BASE,
                        params,
                        (row, number) ->
                                new Base(row.getInt("rating_count"), score(row, "average_score")));
        int ratingCount = base == null ? 0 : base.ratingCount();
        if (ratingCount == 0) {
            return new Aggregate(0, null, null);
        }
        BigDecimal denominator = BigDecimal.valueOf(ratingCount);

        List<TasteAnalysis.Genre> genres =
                jdbc.query(
                        SELECT_GENRES,
                        params,
                        (row, number) ->
                                new TasteAnalysis.Genre(
                                        row.getString("code"),
                                        row.getString("name"),
                                        row.getInt("film_count"),
                                        score(row, "average_score"),
                                        share(row.getInt("film_count"), denominator)));
        List<TasteAnalysis.Country> countries =
                jdbc.query(
                        SELECT_COUNTRIES,
                        params,
                        (row, number) -> {
                            String code = row.getString("code");
                            return new TasteAnalysis.Country(
                                    code,
                                    countryName(code),
                                    row.getInt("film_count"),
                                    score(row, "average_score"),
                                    share(row.getInt("film_count"), denominator));
                        });
        List<TasteAnalysis.Era> eras =
                jdbc.query(
                        SELECT_ERAS,
                        params,
                        (row, number) ->
                                new TasteAnalysis.Era(
                                        eraLabel(row.getInt("era_start")),
                                        row.getInt("film_count"),
                                        score(row, "average_score"),
                                        share(row.getInt("film_count"), denominator)));
        List<TasteAnalysis.Director> directors =
                jdbc.query(
                        SELECT_DIRECTORS,
                        Map.of("userId", userId, "limit", TOP_DIRECTORS),
                        (row, number) ->
                                new TasteAnalysis.Director(
                                        row.getLong("person_id"),
                                        row.getString("name"),
                                        row.getInt("film_count"),
                                        score(row, "average_score")));

        return new Aggregate(
                ratingCount,
                base.averageScore(),
                new TasteAnalysis.Analysis(genres, countries, eras, directors));
    }

    /** 집계 결과. {@code analysis}는 평가가 하나도 없을 때만 {@code null}이다. */
    public record Aggregate(
            int ratingCount, BigDecimal averageScore, TasteAnalysis.Analysis analysis) {

        public boolean isEmpty() {
            return ratingCount == 0 || analysis == null;
        }
    }

    private record Base(int ratingCount, BigDecimal averageScore) {}

    private static BigDecimal share(int count, BigDecimal denominator) {
        return BigDecimal.valueOf(count).divide(denominator, SHARE_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code 2010} → {@code 2010s}. 계약이 고정한 형식이고 키워드 선택기가 이 형식만 읽는다. */
    private static String eraLabel(int eraStart) {
        return eraStart + "s";
    }

    /**
     * ISO 3166-1 alpha-2 코드를 한국어 표시명으로 바꾼다.
     *
     * <p>{@code movie_countries}에 코드만 있어 이름을 만들어야 한다. 계약(API-040)이 응답 예시·키워드 예시·규칙표 세 곳에서 {@code
     * KR}을 {@code 한국}으로 쓰므로 JDK 표시명({@code 대한민국})을 그대로 쓰면 안 된다. 역사·지역 코드는 JDK가 코드를 그대로 돌려주므로({@code
     * SU}, {@code YU} 등) 표를 따로 둔다. 그러지 않으면 {@code SU 영화 집중} 같은 라벨이 저장되어 공개 프로필(API-062)까지 나간다.
     *
     * <p>저장소에 같은 표가 이미 셋 있다({@code movie}·{@code search}·{@code report}). 넷째를 만드는 셈이지만 공통 코드를
     * {@code global}로 옮기는 것은 컨벤션 3장이 백엔드 담당자와 먼저 합의하라고 정해 후속으로 남긴다. 다른 셋과 달리 <b>예외를 던지지 않는다</b> — 이
     * 경로는 평가 쓰기 트랜잭션 안이라 표시명 하나 때문에 평가 저장을 되돌릴 수 없다.
     */
    private static String countryName(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String override = COUNTRY_OVERRIDES.get(code);
        if (override != null) {
            return override;
        }
        if (!ISO_CODES.contains(code)) {
            return code;
        }
        String name = Locale.of("", code).getDisplayCountry(Locale.KOREAN);
        return name.isBlank() || name.equals(code) ? code : name;
    }

    /**
     * 평균 별점을 읽는다. Postgres {@code avg}는 나누어떨어지지 않으면 16자리 소수를 주므로 저장 전에 자른다. 백필 SQL의 {@code
     * round(...,2)}와 같은 값이어야 백필 직후와 첫 재계산 뒤의 응답이 같다.
     */
    private static BigDecimal score(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        BigDecimal value = row.getBigDecimal(column);
        return value == null ? null : value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
