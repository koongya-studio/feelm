-- S15P21E106-648 온보딩 소급 보정의 읽기 전용 점검.
-- 적용 전후에 같은 명령으로 실행해 수치를 비교한다. 쓰기를 하지 않는다.
-- 절차는 backend/scripts/ONBOARDING-BACKFILL-OPERATIONS.md를 따른다.

\set ON_ERROR_STOP on
BEGIN READ ONLY;

\echo '== 1. 보정 대상 (활성 계정의 온보딩 선택 중 평가가 없는 것) =='
SELECT count(*) AS target_rows,
       count(DISTINCT preference.user_id) AS target_users,
       count(DISTINCT preference.movie_id) AS target_movies,
       count(*) FILTER (WHERE preference.preference = 'LIKE') AS like_rows,
       count(*) FILTER (WHERE preference.preference = 'DISLIKE') AS dislike_rows,
       count(*) FILTER (WHERE movie.representative_flavor_id IS NOT NULL) AS popcorn_rows
FROM onboarding_preferences preference
JOIN users account ON account.id = preference.user_id AND account.status = 'ACTIVE'
JOIN movies movie ON movie.id = preference.movie_id
WHERE NOT EXISTS (
    SELECT 1 FROM ratings rating
    WHERE rating.user_id = preference.user_id
      AND rating.movie_id = preference.movie_id
);

\echo '== 2. 보정 대상 중 활성 감상 기록이 이미 있는 행 (되돌리려면 이전 상태가 필요하다) =='
SELECT watch.id AS watch_record_id,
       watch.user_id,
       watch.movie_id,
       watch.status AS previous_status,
       watch.clicked_at,
       watch.rated_at AS previous_rated_at
FROM onboarding_preferences preference
JOIN users account ON account.id = preference.user_id AND account.status = 'ACTIVE'
JOIN watch_records watch
  ON watch.user_id = preference.user_id
 AND watch.movie_id = preference.movie_id
 AND watch.status <> 'EXPIRED'
WHERE NOT EXISTS (
    SELECT 1 FROM ratings rating
    WHERE rating.user_id = preference.user_id
      AND rating.movie_id = preference.movie_id
)
ORDER BY watch.id;

\echo '== 3. 제외 대상 (보정하지 않는 행의 사유별 건수) =='
SELECT CASE
           WHEN account.status <> 'ACTIVE' THEN '비활성 계정'
           ELSE '이미 평가 있음'
       END AS reason,
       count(*) AS rows
FROM onboarding_preferences preference
JOIN users account ON account.id = preference.user_id
WHERE account.status <> 'ACTIVE'
   OR EXISTS (
        SELECT 1 FROM ratings rating
        WHERE rating.user_id = preference.user_id
          AND rating.movie_id = preference.movie_id
   )
GROUP BY 1
ORDER BY 1;

\echo '== 4. 온보딩만 마치고 필름·팝콘이 비어 있는 활성 사용자 =='
SELECT count(*) AS users_without_frames
FROM users account
WHERE account.status = 'ACTIVE'
  AND EXISTS (SELECT 1 FROM onboarding_preferences p WHERE p.user_id = account.id)
  AND NOT EXISTS (SELECT 1 FROM frames f WHERE f.user_id = account.id);

\echo '== 5. 온보딩 영화의 집계 어긋남 (보정 후 0이어야 한다) =='
SELECT count(*) AS mismatched_movies
FROM movies movie
JOIN (
    SELECT rating.movie_id,
           count(*)::int AS rating_count,
           round(avg(rating.score), 2) AS average_rating
    FROM ratings rating
    GROUP BY rating.movie_id
) aggregate ON aggregate.movie_id = movie.id
WHERE movie.id IN (SELECT DISTINCT movie_id FROM onboarding_preferences)
  AND (movie.rating_count <> aggregate.rating_count
    OR movie.average_rating <> aggregate.average_rating);

\echo '== 6. 온보딩 선택과 같은 (사용자, 영화)의 평가를 생성 시각별로 묶는다 =='
-- 한 보정이 만든 행은 created_at이 모두 같다. 기동 로그의 마커 시각과 대조해
-- 어느 묶음이 이번 보정인지 판정한다. 시각이 겹치는 사용자 작성 평가가 섞일 수 있으므로
-- 이 묶음만으로 삭제 대상을 정하지 않는다.
SELECT rating.created_at AS created_at,
       count(*) AS ratings,
       count(DISTINCT rating.user_id) AS users,
       count(frame.id) AS frames,
       count(*) FILTER (WHERE rating.score IN (4.0, 2.0)
                          AND rating.comment IS NULL
                          AND rating.visibility IS NULL) AS backfill_shaped
FROM ratings rating
JOIN onboarding_preferences preference
  ON preference.user_id = rating.user_id
 AND preference.movie_id = rating.movie_id
LEFT JOIN frames frame ON frame.rating_id = rating.id
GROUP BY rating.created_at
ORDER BY rating.created_at DESC
LIMIT 20;

\echo '== 7. 마이그레이션 이력 =='
SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version >= '202609192100'
ORDER BY installed_rank;

COMMIT;
