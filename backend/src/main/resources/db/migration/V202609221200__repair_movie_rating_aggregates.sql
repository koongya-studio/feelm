-- S15P21E106-636
-- 애플리케이션을 우회해 삽입된 ratings 때문에 어긋난 영화별 비정규화 집계를 정본에서 복구한다.
--
-- 정상 평가 쓰기는 movies 행을 FOR UPDATE로 잠근 뒤 ratings와 movies를 같은 트랜잭션에서
-- 변경한다. 직접 ratings INSERT는 반대로 ratings 잠금을 먼저 잡고 FK 확인에서 movies를 읽을
-- 수 있으므로, 두 relation lock 중 하나라도 즉시 얻지 못하면 그 시도의 잠금을 모두 되돌리고
-- 재시도한다. 둘을 함께 얻은 뒤에는 새 평가 쓰기를 막아 늦은 커밋의 덮어쓰기를 방지한다.
-- ACCESS SHARE 상세 조회는 계속 허용된다.
DO $repair_lock$
DECLARE
    attempt integer := 0;
BEGIN
    LOOP
        attempt := attempt + 1;
        BEGIN
            LOCK TABLE movies IN EXCLUSIVE MODE NOWAIT;
            LOCK TABLE ratings IN SHARE MODE NOWAIT;
            EXIT;
        EXCEPTION
            WHEN lock_not_available OR deadlock_detected THEN
                IF attempt >= 120 THEN
                    RAISE EXCEPTION
                        'Could not acquire movie rating repair locks after % attempts', attempt
                        USING ERRCODE = '55P03',
                              HINT = 'Pause rating and movie writes, including direct ratings writers, then retry application startup';
                END IF;
                PERFORM pg_sleep(0.25);
        END;
    END LOOP;
END
$repair_lock$;

WITH aggregate AS (
    SELECT movie.id AS movie_id,
           count(rating.id)::int AS rating_count,
           coalesce(round(avg(rating.score), 2), 0)::numeric(3, 2) AS average_rating
    FROM movies movie
    LEFT JOIN ratings rating ON rating.movie_id = movie.id
    GROUP BY movie.id
)
UPDATE movies movie
SET rating_count = aggregate.rating_count,
    average_rating = aggregate.average_rating
FROM aggregate
WHERE movie.id = aggregate.movie_id
  AND (
      movie.rating_count IS DISTINCT FROM aggregate.rating_count
      OR movie.average_rating IS DISTINCT FROM aggregate.average_rating
  );
