package com.feelm.backend.domain.search;

import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/** API-028 전용 데이터. 각 테스트 트랜잭션에서 생성되고 종료 시 롤백된다. */
final class MovieSearchFixture {

    static final long MEMBER_ID = 91001L;
    static final long OTHER_MEMBER_ID = 91002L;

    private MovieSearchFixture() {}

    static void seed(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO users (id, email, nickname, birth_date, gender, status)
                VALUES (?, 'search-one@example.test', '검색회원하나', '1990-01-01', 'OTHER', 'ACTIVE'),
                       (?, 'search-two@example.test', '검색회원둘', '1990-01-01', 'OTHER', 'ACTIVE')
                """,
                MEMBER_ID,
                OTHER_MEMBER_ID);
        movie(jdbc, 101, "매트릭스", "The Matrix", "1999-03-31", 4.5);
        movie(jdbc, 102, "매트릭스 리로디드", "The Matrix Reloaded", "2003-05-15", 4.0);
        movie(jdbc, 103, "기생충", "Parasite", "2019-05-30", 4.6);
        movie(jdbc, 104, "범죄도시", "The Outlaws", "2017-10-03", 3.9);
        movie(jdbc, 105, "잊힌 영화", null, null, 0.0);
        jdbc.update(
                """
                INSERT INTO genres (id, code, name, tmdb_genre_id)
                VALUES (11, 'ACTION', '액션', 28), (12, 'SF', 'SF', 878),
                       (13, 'DRAMA', '드라마', 18), (14, 'THRILLER', '스릴러', 53)
                """);
        jdbc.update(
                """
                INSERT INTO movie_genres (movie_id, genre_id)
                VALUES (101, 11), (101, 12), (102, 11), (102, 12),
                       (103, 13), (103, 14), (104, 11), (104, 14), (105, 13)
                """);
        jdbc.update(
                """
                INSERT INTO movie_countries (movie_id, country_code)
                VALUES (101, 'US'), (101, 'AU'), (102, 'US'),
                       (103, 'KR'), (104, 'KR'), (105, 'JP')
                """);
        jdbc.update(
                """
                INSERT INTO otts (id, code, name, tmdb_provider_id)
                VALUES (21, 'NETFLIX', '넷플릭스', 8), (22, 'WATCHA', '왓챠', 97)
                """);
        jdbc.update(
                """
                INSERT INTO movie_ott_providers (movie_id, ott_id)
                VALUES (101, 21), (101, 22), (102, 22), (103, 21), (104, 22)
                """);
        jdbc.update(
                """
                INSERT INTO people (id, tmdb_person_id, name)
                VALUES (31, 6384, '키아누 리브스'), (32, 9339, '워쇼스키'),
                       (33, 21684, '송강호'), (34, 21630, '봉준호'),
                       (35, 990035, '검색무관인물')
                """);
        jdbc.update(
                """
                INSERT INTO movie_people (movie_id, person_id, role)
                VALUES (101, 31, 'ACTOR'), (101, 32, 'DIRECTOR'),
                       (102, 31, 'ACTOR'), (102, 32, 'DIRECTOR'),
                       (103, 33, 'ACTOR'), (103, 34, 'DIRECTOR'), (104, 35, 'ACTOR')
                """);
    }

    static void movie(
            JdbcTemplate jdbc,
            long id,
            String title,
            String originalTitle,
            String releaseDate,
            double rating) {
        jdbc.update(
                """
                INSERT INTO movies
                    (id, tmdb_id, title, original_title, release_date, average_rating, poster_url)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                900000 + id,
                title,
                originalTitle,
                releaseDate == null ? null : Date.valueOf(LocalDate.parse(releaseDate)),
                rating,
                "https://example.test/posters/" + id + ".jpg");
    }

    static void pageMovies(JdbcTemplate jdbc, int count) {
        for (int i = 1; i <= count; i++) {
            // 모든 정렬값이 같은 데이터로 페이지 경계의 movieId 보조 정렬을 검증한다.
            movie(jdbc, 200 + i, "은하의 기록", "Galactic Chronicle", "2020-01-01", 4.0);
        }
    }

    static void watch(JdbcTemplate jdbc, long userId, long movieId, String status) {
        jdbc.update(
                "INSERT INTO watch_records (user_id, movie_id, status) VALUES (?, ?, ?)",
                userId,
                movieId,
                status);
    }
}
