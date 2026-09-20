-- 같은 Redis generation 안에서 소진 후 생성한 다음 회차를 구분한다(S15P21E106-219).
-- 기존 세트는 모두 첫 회차이므로 batch_no 0으로 해석한다.
-- 되돌리기: batch_no > 0인 세트가 하나라도 생기면 구 인덱스 (generation_id) 부분 고유를
-- 다시 만들 수 없다. 되돌리려면 batch_no > 0인 세트와 그 아이템을 먼저 삭제해야 한다.
ALTER TABLE recommendation_sets
    ADD COLUMN batch_no integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_recommendation_sets_batch_no CHECK (batch_no >= 0);

DROP INDEX IF EXISTS uix_recommendation_sets_generation;

CREATE UNIQUE INDEX uix_recommendation_sets_generation_batch
    ON recommendation_sets (generation_id, batch_no)
    WHERE generation_id IS NOT NULL;
