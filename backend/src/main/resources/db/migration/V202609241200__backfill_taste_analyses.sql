-- 이미 평가가 있는 사용자에게 taste_analyses 행을 만든다(S15P21E106-775).
--
-- 지금까지 이 테이블에 쓰는 코드가 없어 모든 사용자가 빈 응답(API-040)을 받았다. 이 업무가 평가 변경
-- 시점에 갱신을 붙이지만, 기존 사용자는 다음 평가 변경 전까지 행이 생기지 않는다. 그 공백을 메운다.
--
-- keywords는 비워 둔다. 키워드 규칙(S15P21E106-834)은 Java의 TasteKeywordSelector에 있고, 그것을 SQL로
-- 옮기면 임계값이 두 곳에 살아 한쪽만 고칠 때 어긋난다. 비워 두면 API-040 계약에 따라
-- insufficientData가 true로 나가고, 그 사용자가 다음에 평가를 만들거나 고치거나 지울 때 채워진다.
--
-- 탈퇴 계정은 제외한다. 런타임 경로가 활성 계정만 판정하므로 백필이 더 많은 행을 만들면 안 된다.
-- 비중은 소수 넷째 자리까지 둔다. 둘째 자리면 CLASSIC_DIGGER가 버킷 비중을 더해 판정할 때
-- 반올림 오차가 쌓여 임계값 판정을 뒤집는다.
--
-- countries[].name에는 ISO 코드가 그대로 들어간다. 표시명 표가 Java에 있어 SQL로 옮기면 두 벌이 된다.
-- 그 사용자의 다음 평가 변경 때 제대로 된 이름으로 덮인다. 계약에 이 사실을 적었다.
--
-- 멱등하다. 행이 이미 있는 사용자는 건드리지 않는다.

INSERT INTO taste_analyses (user_id, analysis, keywords, rating_count, analyzed_at)
SELECT base.user_id,
       jsonb_build_object(
           'genres', coalesce(genre.items, '[]'::jsonb),
           'countries', coalesce(country.items, '[]'::jsonb),
           'eras', coalesce(era.items, '[]'::jsonb),
           'directors', coalesce(director.items, '[]'::jsonb)
       ),
       '[]'::jsonb,
       base.rating_count,
       now()
FROM (
    SELECT r.user_id, count(*)::int AS rating_count
    FROM ratings r
    JOIN users u ON u.id = r.user_id AND u.status <> 'WITHDRAWN'
    GROUP BY r.user_id
) base
LEFT JOIN LATERAL (
    -- 장르는 나타난 모든 종류를 담는다. 상위 N개로 자르면 GENRE_OMNIVORE가 발화하지 않는다.
    SELECT jsonb_agg(
               jsonb_build_object(
                   'code', t.code, 'name', t.name, 'count', t.film_count,
                   'averageScore', t.average_score, 'share', t.share)
               ORDER BY t.film_count DESC, t.code ASC) AS items
    FROM (
        SELECT g.code AS code, g.name AS name, count(*)::int AS film_count,
               round(avg(r.score), 2) AS average_score,
               round(count(*)::numeric / base.rating_count, 4) AS share
        FROM ratings r
        JOIN movie_genres mg ON mg.movie_id = r.movie_id
        JOIN genres g ON g.id = mg.genre_id
        WHERE r.user_id = base.user_id
        GROUP BY g.code, g.name
    ) t
) genre ON true
LEFT JOIN LATERAL (
    -- 국가 이름은 Java가 ISO 코드에서 만든다. 여기서는 코드를 이름 자리에도 넣어 두고,
    -- 다음 평가 변경 때 제대로 된 표시명으로 덮어쓰게 한다.
    SELECT jsonb_agg(
               jsonb_build_object(
                   'code', t.code, 'name', t.code, 'count', t.film_count,
                   'averageScore', t.average_score, 'share', t.share)
               ORDER BY t.film_count DESC, t.code ASC) AS items
    FROM (
        SELECT mc.country_code AS code, count(*)::int AS film_count,
               round(avg(r.score), 2) AS average_score,
               round(count(*)::numeric / base.rating_count, 4) AS share
        FROM ratings r
        JOIN movie_countries mc ON mc.movie_id = r.movie_id
        WHERE r.user_id = base.user_id
        GROUP BY mc.country_code
    ) t
) country ON true
LEFT JOIN LATERAL (
    -- 10년 버킷을 2010s 형식으로 쓰고 합치지 않는다. 개봉일이 없는 영화는 어느 버킷에도 넣지 않되
    -- rating_count 분모에는 남는다.
    SELECT jsonb_agg(
               jsonb_build_object(
                   'label', t.label, 'count', t.film_count,
                   'averageScore', t.average_score, 'share', t.share)
               ORDER BY t.film_count DESC, t.label ASC) AS items
    FROM (
        SELECT ((extract(year FROM m.release_date)::int / 10) * 10) || 's' AS label,
               count(*)::int AS film_count,
               round(avg(r.score), 2) AS average_score,
               round(count(*)::numeric / base.rating_count, 4) AS share
        FROM ratings r
        JOIN movies m ON m.id = r.movie_id
        WHERE r.user_id = base.user_id AND m.release_date IS NOT NULL
        GROUP BY 1
    ) t
) era ON true
LEFT JOIN LATERAL (
    -- 감독만 상위 5명으로 자른다. 규칙이 1위만 보므로 안전하다.
    SELECT jsonb_agg(
               jsonb_build_object(
                   'personId', t.person_id, 'name', t.name, 'count', t.film_count,
                   'averageScore', t.average_score)
               ORDER BY t.film_count DESC, t.person_id ASC) AS items
    FROM (
        SELECT p.id AS person_id, p.name AS name, count(*)::int AS film_count,
               round(avg(r.score), 2) AS average_score
        FROM ratings r
        JOIN movie_people mp ON mp.movie_id = r.movie_id AND mp.role = 'DIRECTOR'
        JOIN people p ON p.id = mp.person_id
        WHERE r.user_id = base.user_id
        GROUP BY p.id, p.name
        ORDER BY count(*) DESC, p.id ASC
        LIMIT 5
    ) t
) director ON true
WHERE NOT EXISTS (
    SELECT 1 FROM taste_analyses existing WHERE existing.user_id = base.user_id
);
