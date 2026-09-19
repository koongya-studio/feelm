-- S15P21E106-648: 기존 온보딩 사용자의 평가·프레임·팝콘 소급 생성.
--
-- S15P21E106-647이 API-024 제출 경로를 LIKE 4.0점·DISLIKE 2.0점 평가와 감상 기록·프레임 생성으로
-- 바꾸기 전에 온보딩을 마친 사용자는 onboarding_preferences만 있고 평가가 없다. 그 행을 같은 규칙으로
-- 한 번 보정한다. RatingStateWriter.create와 같은 저장 규칙을 SQL로 옮긴 것이며 스키마는 바꾸지 않는다.
--
-- 규칙
--   * 대상은 users.status = 'ACTIVE'인 사용자의 온보딩 선택 중 같은 영화의 평가가 없는 것뿐이다.
--     탈퇴 계정(D-9 익명화)은 로그인할 수 없고 영화 평균만 흔들므로 제외한다.
--   * 이미 평가가 있는 영화는 점수·한줄평·감상 기록을 그대로 둔다.
--   * 활성 감상 기록이 없으면 RATED 기록을 새로 만들고, 있으면 그 기록을 RATED로 전이시켜 연결한다
--     (D-6: 사용자·영화별 활성 기록 1개).
--   * 팝콘은 저장 대상이 아니다. ratings JOIN movies.representative_flavor_id로 집계하므로 평가가
--     생기면 함께 생긴다.
--   * 되돌리기 기준은 마커 시각 하나와, 아래 NOTICE가 남기는 재사용 감상 기록의 이전 상태다.
--     절차는 backend/scripts/ONBOARDING-BACKFILL-OPERATIONS.md에 있다.
--
-- 재실행해도 중복되지 않는다. 대상 산출이 "평가 없음"을 조건으로 하고, 삽입에 ON CONFLICT DO NOTHING을 둔다.
-- 다만 사용자가 나중에 스스로 지운 평가는 "평가 없음"으로 보여 다시 생긴다. 이 마이그레이션은 한 번만
-- 적용되며, 수동 재적용의 한계는 운영 문서에 적었다.

DO $$
DECLARE
    -- now()는 트랜잭션 시작 시각이라 이 블록 안에서 변하지 않는다. Java 경로의 truncatedTo(MICROS)와 맞춘다.
    backfilled_at    timestamptz := date_trunc('microsecond', now());
    target_users     bigint := 0;
    target_movies    bigint := 0;
    created_watches  bigint := 0;
    reused_watches   bigint := 0;
    reused_detail    text := '없음';
    created_ratings  bigint := 0;
    created_frames   bigint := 0;
    created_popcorns bigint := 0;
BEGIN
    -- 1. 후보를 먼저 고정하고 잠금을 잡는다.
    --    현재 배포는 컨테이너를 교체하므로 마이그레이션 중 옛 인스턴스가 살아 있지 않다
    --    (V202609181430 5단계의 전제). 그 전제가 깨져도 대상 판정이 잠금 뒤에 오면
    --    API-024/API-036이 같은 (사용자, 영화)에 평가를 끼워 넣지 못한다.
    CREATE TEMP TABLE backfill_candidates AS
    SELECT preference.user_id,
           preference.movie_id,
           CASE preference.preference
               WHEN 'LIKE' THEN 4.0
               WHEN 'DISLIKE' THEN 2.0
           END::numeric AS score
    FROM onboarding_preferences preference
    JOIN users account ON account.id = preference.user_id
    WHERE account.status = 'ACTIVE';

    -- 잠금 순서는 라이브 경로(OnboardingSubmitService·RatingCreateService)와 같은 사용자 -> 영화
    -- 오름차순이다. 순서가 어긋나면 교착이 난다.
    --
    -- 이 두 잠금이 실제로 라이브 경로를 막는 근거는 다음과 같다.
    --   * API-024는 users를 FOR UPDATE로 잠근다. 아래 FOR NO KEY UPDATE와 충돌해 대기한다.
    --   * API-036은 users를 FOR NO KEY UPDATE로 잠근 뒤 movies를 FOR UPDATE로 잠근다.
    --     아래 movies FOR UPDATE와 충돌해 대기한다.
    -- ratings·watch_records 삽입이 movies에 거는 FK 잠금은 KEY SHARE라 FOR UPDATE와 충돌하지만,
    -- 평가 생성 경로가 이미 movies를 명시적으로 잠그므로 판정은 그 지점에서 직렬화된다.
    PERFORM 1
    FROM users account
    WHERE account.id IN (SELECT DISTINCT user_id FROM backfill_candidates)
    ORDER BY account.id
    FOR NO KEY UPDATE OF account;

    PERFORM 1
    FROM movies movie
    WHERE movie.id IN (SELECT DISTINCT movie_id FROM backfill_candidates)
    ORDER BY movie.id
    FOR UPDATE OF movie;

    -- 계정 상태도 잠금 뒤에 다시 본다. 후보 산출과 잠금 사이에 탈퇴한 계정에 새 개인 데이터를
    -- 만들면 D-9의 파기 대상과 어긋난다.
    CREATE TEMP TABLE backfill_targets AS
    SELECT candidate.user_id, candidate.movie_id, candidate.score
    FROM backfill_candidates candidate
    JOIN users account ON account.id = candidate.user_id AND account.status = 'ACTIVE'
    WHERE NOT EXISTS (
        SELECT 1
        FROM ratings rating
        WHERE rating.user_id = candidate.user_id
          AND rating.movie_id = candidate.movie_id
    );

    SELECT count(DISTINCT user_id), count(DISTINCT movie_id)
    INTO target_users, target_movies
    FROM backfill_targets;

    -- 2. 감상 기록. 활성 기록이 있으면 재사용하고 없으면 만든다.
    --    재사용 행의 이전 상태는 DB에 남지 않으므로 전이 전에 읽어 NOTICE로 남긴다.
    --    이 값이 되돌리기의 유일한 근거다.
    CREATE TEMP TABLE backfill_reused_watches AS
    SELECT watch.id,
           watch.status AS previous_status,
           watch.rated_at AS previous_rated_at
    FROM backfill_targets target
    JOIN watch_records watch
      ON watch.user_id = target.user_id
     AND watch.movie_id = target.movie_id
     AND watch.status <> 'EXPIRED';

    SELECT count(*),
           coalesce(
               string_agg(
                   format(
                       '%s:%s:%s',
                       reused.id,
                       reused.previous_status,
                       coalesce(reused.previous_rated_at::text, 'NULL')),
                   ' ' ORDER BY reused.id),
               '없음')
    INTO reused_watches, reused_detail
    FROM backfill_reused_watches reused;

    -- RatingStateWriter가 부르는 markWatchRated와 같이 rated_at을 조건 없이 덮어쓴다.
    UPDATE watch_records watch
    SET status = 'RATED',
        rated_at = backfilled_at
    FROM backfill_reused_watches reused
    WHERE watch.id = reused.id;

    WITH created AS (
        INSERT INTO watch_records (user_id, movie_id, status, rated_at)
        SELECT target.user_id, target.movie_id, 'RATED', backfilled_at
        FROM backfill_targets target
        WHERE NOT EXISTS (
            SELECT 1
            FROM watch_records watch
            WHERE watch.user_id = target.user_id
              AND watch.movie_id = target.movie_id
              AND watch.status <> 'EXPIRED'
        )
        RETURNING id
    )
    SELECT count(*) INTO created_watches FROM created;

    -- 3. 평가. 한줄평·공개 범위는 없고 스포일러는 false다(API-036의 선택 항목).
    --    부분 유일 인덱스가 활성 감상 기록을 하나로 보장하므로 조인은 1:1이다.
    --    이후 단계가 "이번에 만든 평가"만 대상으로 삼도록 생성 결과를 남긴다.
    CREATE TEMP TABLE backfill_created_ratings (rating_id bigint, movie_id bigint);

    WITH created AS (
        INSERT INTO ratings (
            user_id, movie_id, watch_record_id, score,
            comment, visibility, is_spoiler, created_at, updated_at
        )
        SELECT target.user_id, target.movie_id, watch.id, target.score,
               NULL, NULL, false, backfilled_at, backfilled_at
        FROM backfill_targets target
        JOIN watch_records watch
          ON watch.user_id = target.user_id
         AND watch.movie_id = target.movie_id
         AND watch.status <> 'EXPIRED'
        ON CONFLICT ON CONSTRAINT uk_ratings_user_id_movie_id DO NOTHING
        RETURNING id, movie_id
    )
    INSERT INTO backfill_created_ratings (rating_id, movie_id)
    SELECT created.id, created.movie_id FROM created;

    GET DIAGNOSTICS created_ratings = ROW_COUNT;

    -- 4. 프레임. 이번에 만든 평가에만 붙인다.
    WITH created AS (
        INSERT INTO frames (rating_id, user_id, movie_id, created_at)
        SELECT rating.id, rating.user_id, rating.movie_id, backfilled_at
        FROM ratings rating
        JOIN backfill_created_ratings backfilled ON backfilled.rating_id = rating.id
        ON CONFLICT ON CONSTRAINT uk_frames_rating_id DO NOTHING
        RETURNING id
    )
    SELECT count(*) INTO created_frames FROM created;

    -- 5. 이번에 평가가 생긴 영화의 집계 재계산. RatingCommandRepository.refreshMovieRatingAggregate와
    --    같은 식이라 증분 가산이 아니라 전체 재계산이며, 그 영화에 남아 있던 어긋남도 함께 맞는다.
    --    보정이 건드리지 않은 영화는 그대로 둔다.
    UPDATE movies movie
    SET rating_count = aggregate.rating_count,
        average_rating = aggregate.average_rating
    FROM (
        SELECT rating.movie_id,
               count(*)::int AS rating_count,
               coalesce(round(avg(rating.score), 2), 0) AS average_rating
        FROM ratings rating
        WHERE rating.movie_id IN (SELECT DISTINCT movie_id FROM backfill_created_ratings)
        GROUP BY rating.movie_id
    ) aggregate
    WHERE movie.id = aggregate.movie_id;

    -- 6. 기록. 대표 맛이 있는 영화에 이번에 생긴 평가 수가 새로 생긴 팝콘 알 수다.
    SELECT count(*)
    INTO created_popcorns
    FROM backfill_created_ratings backfilled
    JOIN movies movie ON movie.id = backfilled.movie_id
    WHERE movie.representative_flavor_id IS NOT NULL;

    DROP TABLE backfill_created_ratings;
    DROP TABLE backfill_reused_watches;
    DROP TABLE backfill_targets;
    DROP TABLE backfill_candidates;

    RAISE NOTICE
        'S15P21E106-648 온보딩 소급 보정: 마커=% 대상 사용자=% 대상 영화=% 생성 평가=% 생성 프레임=% 생성 팝콘=% 신규 감상 기록=% 재사용 감상 기록=% 재사용 이전 상태(id:status:rated_at)=%',
        backfilled_at, target_users, target_movies,
        created_ratings, created_frames, created_popcorns,
        created_watches, reused_watches, reused_detail;
END $$;
