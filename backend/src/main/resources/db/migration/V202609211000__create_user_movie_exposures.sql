-- 추천 이력(API-048)의 정본 목록이다(S15P21E106-671).
--
-- 평가마다 새 generation의 세트가 생기고 후보 상위권은 잘 바뀌지 않아, 같은 영화가 세트마다
-- 다시 노출된다. 이력을 읽을 때마다 사용자의 노출 전체를 훑어 영화별 최신 1건을 고르면
-- 비용이 이력 길이에 비례해 자란다. 노출은 append-only이므로 노출 시점에 한 번 정리한다.
--
-- (user_id, movie_id)가 기본키라 같은 영화의 재노출은 행을 늘리지 않고 최신 노출로 갱신된다.
-- 행 수는 "그 사용자가 본 고유 영화 수"이며 노출 횟수가 아니다.
-- 쓰기 지점은 RecommendationServingRepository.expose() 하나이며 exposed_at과 같은 트랜잭션이다.
--
-- 되돌리기: 이전 이미지로 롤백한 채 운영하면 그동안의 노출이 이 표에 반영되지 않는다.
-- 재배포 전에 반드시 다음을 실행해 백필이 다시 돌게 한다.
--   DROP TABLE user_movie_exposures;
--   DELETE FROM flyway_schema_history WHERE version = '202609211000';
-- 원본인 recommendation_items.exposed_at은 어느 경우에도 바꾸지 않는다.
--
-- 복구: 아래 백필 INSERT는 멱등이다(ON CONFLICT ... WHERE set_id < EXCLUDED.set_id). 항목·세트를
-- 삭제해 이력 행이 CASCADE로 사라졌거나 드리프트가 의심되면 INSERT 문만 다시 실행하면 원본 기준의
-- 승자로 복원된다. 드리프트 점검은 아래 SELECT(DISTINCT ON) 결과와 이 표를 비교하면 된다.
CREATE TABLE user_movie_exposures (
    user_id                bigint      NOT NULL,
    movie_id               bigint      NOT NULL,
    recommendation_item_id bigint      NOT NULL,
    set_id                 bigint      NOT NULL,
    exposed_at             timestamptz NOT NULL,
    PRIMARY KEY (user_id, movie_id),
    CONSTRAINT fk_user_movie_exposures_user_id FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_movie_exposures_movie_id FOREIGN KEY (movie_id)
        REFERENCES movies (id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_movie_exposures_recommendation_item_id FOREIGN KEY (recommendation_item_id)
        REFERENCES recommendation_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_movie_exposures_set_id FOREIGN KEY (set_id)
        REFERENCES recommendation_sets (id) ON DELETE CASCADE
);

-- 이력 조회는 이 인덱스만 타고 세트를 최신순으로 훑는다. set_id 내림차순이 곧 최신순이다.
CREATE INDEX ix_user_movie_exposures_user_id_set_id
    ON user_movie_exposures (user_id, set_id DESC);

-- 기존 노출분 백필. 영화별로 set_id가 가장 큰 노출 하나만 남긴다. 재실행하면 더 최신 승자만 갱신한다.
INSERT INTO user_movie_exposures (user_id, movie_id, recommendation_item_id, set_id, exposed_at)
SELECT DISTINCT ON (s.user_id, i.movie_id)
       s.user_id, i.movie_id, i.id, i.set_id, i.exposed_at
FROM recommendation_items i
JOIN recommendation_sets s ON s.id = i.set_id
WHERE s.user_id IS NOT NULL AND i.exposed_at IS NOT NULL
ORDER BY s.user_id, i.movie_id, i.set_id DESC
ON CONFLICT (user_id, movie_id) DO UPDATE SET
    recommendation_item_id = EXCLUDED.recommendation_item_id,
    set_id = EXCLUDED.set_id,
    exposed_at = EXCLUDED.exposed_at
WHERE user_movie_exposures.set_id < EXCLUDED.set_id;
