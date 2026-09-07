-- FR-19 / API-036·037: MovieLens 32M과 같은 0.5~5.0점, 0.5점 간격.
-- 기존 마이그레이션은 보존하고 저장된 정수 점수는 값 변경 없이 numeric으로 전환한다.
ALTER TABLE ratings
    DROP CONSTRAINT ck_ratings_score_range,
    ALTER COLUMN score TYPE numeric USING score::numeric,
    ADD CONSTRAINT ck_ratings_score_range CHECK (score >= 0.5 AND score <= 5.0),
    ADD CONSTRAINT ck_ratings_score_step CHECK (mod(score, 0.5) = 0);

-- numeric(2,1)은 CHECK 전에 4.49를 4.5로 반올림하므로 scale을 고정하지 않는다.
COMMENT ON COLUMN ratings.score IS '사용자 평점: 0.5~5.0, 0.5점 단위. 반올림 없이 검증';
