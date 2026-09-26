-- 사용자 요청 리포트는 같은 반기에도 여러 스냅샷을 보존한다.
ALTER TABLE taste_reports DROP CONSTRAINT uk_taste_reports_user_id_year_half;
ALTER TABLE taste_reports ADD COLUMN source_fingerprint varchar(64);
ALTER TABLE taste_reports ADD COLUMN source_frames jsonb;
CREATE INDEX ix_taste_reports_user_created ON taste_reports (user_id, created_at DESC, id DESC);
-- 기존 배치 코드의 과거 리포트 upsert 호환. 수동 결과는 이 제약에 포함하지 않는다.
CREATE UNIQUE INDEX uk_taste_reports_legacy_period ON taste_reports (user_id, year, half)
    WHERE source_fingerprint IS NULL;
