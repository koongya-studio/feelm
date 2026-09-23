-- 파티 추천 이력(API-083)의 정본 목록이다(S15P21E106-815). 개인 이력의 user_movie_exposures(S15P21E106-671)와 같은 구조를
-- 파티 단위로 둔다.
--
-- 파티원 누가 평가하면 그 사람의 후보 세대가 바뀌어 파티 입력이 달라지고 새 세트가 생긴다. 후보 상위권은 잘 바뀌지 않아
-- 같은 영화가 세트마다 다시 노출된다. 이력을 읽을 때마다 파티의 노출 전체를 훑어 영화별 최신 1건을 고르면 비용이
-- 파티가 활발할수록 자라므로, 노출은 append-only라는 점을 이용해 노출 시점에 한 번 정리한다.
--
-- (party_id, movie_id)가 기본키라 같은 영화의 재노출은 행을 늘리지 않고 최신 노출로 갱신된다.
-- 행 수는 "그 파티가 본 고유 영화 수"이며 노출 횟수가 아니다.
-- 쓰기 지점은 PartyRecommendationRepository.expose() 하나이며 exposed_at과 같은 트랜잭션(파티 행 잠금 안)이다.
--
-- 되돌리기: 이전 이미지로 롤백한 채 운영하면 그동안의 노출이 이 표에 반영되지 않는다.
-- 재배포 전에 반드시 다음을 실행해 백필이 다시 돌게 한다.
--   DROP TABLE party_movie_exposures;
--   DELETE FROM flyway_schema_history WHERE version = '202609231400';
-- 원본인 recommendation_items.exposed_at은 어느 경우에도 바꾸지 않는다.
--
-- 복구: 아래 백필 INSERT는 멱등이다(ON CONFLICT ... WHERE set_id < EXCLUDED.set_id). 항목·세트를
-- 삭제해 이력 행이 CASCADE로 사라졌거나 드리프트가 의심되면 INSERT 문만 다시 실행하면 원본 기준의
-- 승자로 복원된다.
CREATE TABLE party_movie_exposures (
    party_id               bigint      NOT NULL,
    movie_id               bigint      NOT NULL,
    recommendation_item_id bigint      NOT NULL,
    set_id                 bigint      NOT NULL,
    exposed_at             timestamptz NOT NULL,
    PRIMARY KEY (party_id, movie_id),
    CONSTRAINT fk_party_movie_exposures_party_id FOREIGN KEY (party_id)
        REFERENCES parties (id) ON DELETE CASCADE,
    CONSTRAINT fk_party_movie_exposures_movie_id FOREIGN KEY (movie_id)
        REFERENCES movies (id) ON DELETE RESTRICT,
    CONSTRAINT fk_party_movie_exposures_recommendation_item_id FOREIGN KEY (recommendation_item_id)
        REFERENCES recommendation_items (id) ON DELETE CASCADE,
    CONSTRAINT fk_party_movie_exposures_set_id FOREIGN KEY (set_id)
        REFERENCES recommendation_sets (id) ON DELETE CASCADE
);

-- 이력 조회는 이 인덱스만 타고 세트를 최신순으로 훑는다. set_id 내림차순이 곧 최신순이다.
CREATE INDEX ix_party_movie_exposures_party_id_set_id
    ON party_movie_exposures (party_id, set_id DESC);

-- 기존 노출분 백필. 영화별로 set_id가 가장 큰 노출 하나만 남긴다. 재실행하면 더 최신 승자만 갱신한다.
INSERT INTO party_movie_exposures (party_id, movie_id, recommendation_item_id, set_id, exposed_at)
SELECT DISTINCT ON (s.party_id, i.movie_id)
       s.party_id, i.movie_id, i.id, i.set_id, i.exposed_at
FROM recommendation_items i
JOIN recommendation_sets s ON s.id = i.set_id
WHERE s.party_id IS NOT NULL AND i.type = 'PARTY' AND i.exposed_at IS NOT NULL
ORDER BY s.party_id, i.movie_id, i.set_id DESC
ON CONFLICT (party_id, movie_id) DO UPDATE SET
    recommendation_item_id = EXCLUDED.recommendation_item_id,
    set_id = EXCLUDED.set_id,
    exposed_at = EXCLUDED.exposed_at
WHERE party_movie_exposures.set_id < EXCLUDED.set_id;
