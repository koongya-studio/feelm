package com.feelm.backend.domain.movie;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 Flyway 스키마에 가상 데이터를 넣는다. 각 테스트의 트랜잭션이 끝나면 모두 롤백된다.
 *
 * <p>맛·장르·OTT 같은 기준정보도 Flyway seed나 앞선 테스트가 남긴 것에 기대지 않고 직접 넣는다. 공유 PostgreSQL을 쓰는 다른 계약 테스트가 기준정보를
 * 커밋하며 갈아치우므로, 그러지 않으면 클래스 실행 순서에 따라 seed가 통째로 실패한다.
 */
final class MovieDetailFixture {

    static final long MOVIE = 1_580_001L;
    static final long OTHER_MOVIE = 1_580_002L;
    static final long EMPTY_MOVIE = 1_580_003L;
    static final long USER = 1_580_101L;
    static final long OTHER_USER = 1_580_102L;
    static final long OUTSIDER = 1_580_103L;
    static final long NETFLIX = 40L;
    static final long TVING = 30L;
    static final long DISNEY = 20L;
    static final long WAVVE = 10L;
    static final long WATCHA = 25L;
    static final long COUPANG_PLAY = 5L;
    static final long EXTRA_OTT = 35L;
    static final long RATING = 1_580_201L;
    static final long FLAVOR = 1_580_301L;

    /**
     * 같은 PostgreSQL을 공유하는 다른 계약 테스트가 자기 기준정보를 커밋한 채 끝낸다. 남은 행과 겹치지 않도록 이 fixture가 쓰는 뿌리를 비우고 필요한 것만
     * 다시 넣는다. CASCADE가 movies·people·ratings 같은 참조 표까지 함께 비운다.
     */
    private static final String CLEAR_CATALOG =
            "TRUNCATE TABLE popcorn_flavors, genres, otts, people, users CASCADE";

    static final String REASON =
            """
            {"summary":"스릴러 장르를 높게 평가하셨어요",
             "factors":[{"type":"GENRE","label":"스릴러"},
                        {"type":"DIRECTOR","label":"가상 감독"}]}
            """;
    private static final String STORED_REASON =
            """
            {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[42],"matchedGenreCodes":["THRILLER"]}],
             "summary":"스릴러 장르를 높게 평가하셨어요",
             "factors":[{"type":"GENRE","label":"스릴러"},
                        {"type":"DIRECTOR","label":"가상 감독"}]}
            """;

    private final JdbcTemplate jdbc;

    MovieDetailFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void seed() {
        // 클래스 실행 순서에 기대지 않는다. 이 테스트 트랜잭션이 비우기와 넣기를 함께 롤백한다.
        jdbc.execute(CLEAR_CATALOG);
        for (long user : new long[] {USER, OTHER_USER, OUTSIDER}) {
            jdbc.update(
                    """
                    INSERT INTO users (id, email, nickname, birth_date, gender, status)
                    VALUES (?, ?, ?, DATE '1999-03-14', 'OTHER', 'ACTIVE')
                    """,
                    user,
                    "movie" + user + "@example.com",
                    "m" + user);
        }
        // Flyway seed(V202609161115)의 FLAVOR_A를 그대로 옮겨 온다. 남아 있는지는 묻지 않는다.
        jdbc.update(
                """
                INSERT INTO popcorn_flavors (id, code, display_name, color, sort_order,
                    cluster_id, representative_keywords)
                VALUES (?, 'FLAVOR_A', '오리지널 솔트', '#FBF1CF', 1, 0, '[]'::jsonb)
                """,
                FLAVOR);
        jdbc.update(
                """
                INSERT INTO movies (id, tmdb_id, title, original_title, synopsis, release_date,
                    runtime_minutes, poster_url, backdrop_url, representative_flavor_id,
                    average_rating, rating_count)
                VALUES (?, 550, '가상 영화', 'Fixture Movie', '가상 영화 줄거리', DATE '1999-10-15',
                    139, 'https://example.com/poster.jpg', 'https://example.com/backdrop.jpg',
                    ?, 4.35, 1280)
                """,
                MOVIE,
                FLAVOR);
        jdbc.update(
                """
                INSERT INTO movies (id, tmdb_id, title) VALUES
                    (?, 551, '다른 영화'), (?, 552, '최소 정보 영화')
                """,
                OTHER_MOVIE,
                EMPTY_MOVIE);
        jdbc.update(
                """
                INSERT INTO genres (id, code, name, tmdb_genre_id) VALUES
                    (1, 'THRILLER', '스릴러', 53), (2, 'DRAMA', '드라마', 18)
                """);
        jdbc.update("INSERT INTO movie_genres VALUES (?, 1), (?, 2)", MOVIE, MOVIE);
        jdbc.update("INSERT INTO movie_countries VALUES (?, 'US'), (?, 'KR')", MOVIE, MOVIE);
        person(11, "배우 나", "ACTOR", "배역 나", 9);
        person(12, "배우 가", "ACTOR", "배역 가", 0);
        person(13, "감독 나", "DIRECTOR", null, null);
        person(14, "감독 가", "DIRECTOR", null, 0);
        ott(NETFLIX, "NETFLIX", "넷플릭스");
        ott(TVING, "TVING", "티빙");
        ott(DISNEY, "DISNEY_PLUS", "디즈니플러스");
        ott(WAVVE, "WAVVE", "웨이브");
        for (long ott : new long[] {NETFLIX, TVING, DISNEY, WAVVE}) {
            provider(MOVIE, ott);
        }
    }

    void unsupportedProviders(long movieId) {
        ott(COUPANG_PLAY, "COUPANG_PLAY", "쿠팡플레이");
        ott(EXTRA_OTT, "EXTRA_OTT", "미승인 OTT");
        provider(movieId, COUPANG_PLAY);
        provider(movieId, EXTRA_OTT);
    }

    void person(long id, String name, String role, String character, Integer order) {
        jdbc.update(
                "INSERT INTO people (id, tmdb_person_id, name) VALUES (?, ?, ?)",
                id,
                id + 5000,
                name);
        credit(id, role, character, order);
    }

    void credit(long personId, String role, String character, Integer order) {
        jdbc.update(
                """
                INSERT INTO movie_people (movie_id, person_id, role, character_name, display_order)
                VALUES (?, ?, ?, ?, ?)
                """,
                MOVIE,
                personId,
                role,
                character,
                order);
    }

    void clearCredits() {
        jdbc.update("DELETE FROM movie_people WHERE movie_id = ?", MOVIE);
    }

    void ott(long id, String code, String name) {
        jdbc.update(
                "INSERT INTO otts (id, code, name, tmdb_provider_id) VALUES (?, ?, ?, ?)",
                id,
                code,
                name,
                id + 5000);
    }

    void provider(long movieId, long ottId) {
        jdbc.update(
                "INSERT INTO movie_ott_providers (movie_id, ott_id, watch_url) VALUES (?, ?, ?)",
                movieId,
                ottId,
                "https://example.com/watch/" + ottId);
    }

    void subscribe(long userId, long ottId) {
        jdbc.update("INSERT INTO user_otts VALUES (?, ?)", userId, ottId);
    }

    void rating(
            long id, long userId, long movieId, String score, String comment, String visibility) {
        jdbc.update(
                """
                INSERT INTO ratings (id, user_id, movie_id, score, comment, visibility)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                userId,
                movieId,
                new BigDecimal(score),
                comment,
                visibility);
    }

    void watch(long userId, long movieId, String status) {
        jdbc.update(
                "INSERT INTO watch_records (user_id, movie_id, status) VALUES (?, ?, ?)",
                userId,
                movieId,
                status);
    }

    void personalize() {
        rating(RATING, USER, MOVIE, "0.5", "내 비공개 한줄평", "PRIVATE");
        rating(RATING + 1, OTHER_USER, MOVIE, "5.0", "다른 사람 비공개 한줄평", "PRIVATE");
        rating(RATING + 2, USER, OTHER_MOVIE, "3.0", "다른 영화 한줄평", "PUBLIC");
        watch(USER, MOVIE, "EXPIRED");
        watch(USER, MOVIE, "RATED");
        watch(OTHER_USER, MOVIE, "WATCHED_UNRATED");
        subscribe(USER, TVING);
        subscribe(USER, WAVVE);
        subscribe(OTHER_USER, NETFLIX);
    }

    long personalRecommendation(long userId, long movieId, String type) {
        return item(recommendationSet(userId, null), movieId, type);
    }

    long party(long ownerId) {
        long partyId =
                jdbc.queryForObject(
                        """
                INSERT INTO parties (name, owner_id, status)
                VALUES ('테스트 파티', ?, 'ACTIVE') RETURNING id
                """,
                        Long.class,
                        ownerId);
        member(partyId, ownerId, "OWNER");
        return partyId;
    }

    void member(long partyId, long userId, String role) {
        jdbc.update(
                """
                INSERT INTO party_members (party_id, user_id, role, joined_at)
                VALUES (?, ?, ?, TIMESTAMPTZ '2026-09-08 00:00:00Z')
                """,
                partyId,
                userId,
                role);
    }

    long partyRecommendation(long partyId, long movieId) {
        return item(recommendationSet(null, partyId), movieId, "PARTY");
    }

    private long recommendationSet(Long userId, Long partyId) {
        return jdbc.queryForObject(
                """
                INSERT INTO recommendation_sets
                    (user_id, party_id, input_version, model_version, generated_at)
                VALUES (?, ?, gen_random_uuid()::text, 'test-model',
                    TIMESTAMPTZ '2026-09-07 00:00:00Z') RETURNING id
                """,
                Long.class,
                userId,
                partyId);
    }

    private long item(long setId, long movieId, String type) {
        return jdbc.queryForObject(
                """
                INSERT INTO recommendation_items (set_id, movie_id, type, position, reason)
                VALUES (?, ?, ?, 1, ?::jsonb) RETURNING id
                """,
                Long.class,
                setId,
                movieId,
                type,
                STORED_REASON);
    }
}
