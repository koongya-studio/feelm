package com.feelm.backend.domain.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;

/** API-023·024 계약 테스트가 공유하는 시드. 각 테스트의 {@code @BeforeEach}가 테이블을 비우고 다시 넣는다. */
final class OnboardingFixture {

    static final long ACTIVE = 10;
    static final long WITHDRAWN = 20;

    /** 자기 제출이 남의 행을 건드리지 않는지 보기 위한 제3자. 이 사용자의 선택은 어떤 테스트에서도 바뀌면 안 된다. */
    static final long OTHER = 30;

    static final long COMPLETED = 40;

    /** 운영 기준정보와 같은 ADR-0020 팝콘 맛 코드다. 배정 자체는 파이프라인이 한다. */
    static final String[] FLAVOR_CODES = {
        "FLAVOR_A",
        "FLAVOR_B",
        "FLAVOR_C",
        "FLAVOR_D",
        "FLAVOR_E",
        "FLAVOR_F",
        "FLAVOR_G",
        "FLAVOR_H"
    };

    private OnboardingFixture() {}

    static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE onboarding_preferences RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE outbox_events RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE movie_genres RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE genres RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE movies RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE popcorn_flavors RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        user(jdbc, ACTIVE, "active", "ACTIVE", false);
        user(jdbc, WITHDRAWN, "withdrawn", "WITHDRAWN", false);
        user(jdbc, OTHER, "other", "ACTIVE", false);
        user(jdbc, COMPLETED, "completed", "ACTIVE", true);
    }

    /** 맛 8종을 {@code sort_order} 1~8로 넣는다. 식별자는 1부터 코드 순서와 같다. */
    static void flavors(JdbcTemplate jdbc) {
        for (int index = 0; index < FLAVOR_CODES.length; index++) {
            jdbc.update(
                    "INSERT INTO popcorn_flavors(id,code,display_name,color,description,sort_order,"
                            + "cluster_id,representative_keywords)"
                            + " VALUES (?,?,?,?,'테스트 맛 설명',?,?,CAST('[]' AS jsonb))",
                    index + 1,
                    FLAVOR_CODES[index],
                    FLAVOR_CODES[index],
                    "#000000",
                    index + 1,
                    index);
        }
    }

    /**
     * 영화 한 편을 넣는다.
     *
     * @param flavorId 대표 맛 식별자. {@code null}이면 아직 배정되지 않은 영화다
     * @param ratingCount 인기도 정렬의 첫 번째 기준
     */
    static void movie(JdbcTemplate jdbc, long id, Long flavorId, int ratingCount) {
        jdbc.update(
                "INSERT INTO movies(id,tmdb_id,title,poster_url,release_date,"
                        + "representative_flavor_id,average_rating,rating_count)"
                        + " VALUES (?,?,?,?,DATE '1999-03-31',?,4.5,?)",
                id,
                id,
                "영화" + id,
                "https://image.example.test/" + id + ".jpg",
                flavorId,
                ratingCount);
    }

    static void genre(JdbcTemplate jdbc, long id, String code, String name, int tmdbGenreId) {
        jdbc.update(
                "INSERT INTO genres(id,code,name,tmdb_genre_id) VALUES (?,?,?,?)",
                id,
                code,
                name,
                tmdbGenreId);
    }

    static void movieGenre(JdbcTemplate jdbc, long movieId, long genreId) {
        jdbc.update("INSERT INTO movie_genres(movie_id,genre_id) VALUES (?,?)", movieId, genreId);
    }

    static void preference(JdbcTemplate jdbc, long userId, long movieId, String preference) {
        jdbc.update(
                "INSERT INTO onboarding_preferences(user_id,movie_id,preference)"
                        + " VALUES (?,?,?)",
                userId,
                movieId,
                preference);
    }

    private static void user(
            JdbcTemplate jdbc, long id, String nickname, String status, boolean onboardingDone) {
        jdbc.update(
                "INSERT INTO users(id,email,nickname,birth_date,gender,status,onboarding_completed)"
                        + " VALUES (?,?,?,DATE '2000-01-01','OTHER',?,?)",
                id,
                nickname + "@example.test",
                nickname,
                status,
                onboardingDone);
    }
}
