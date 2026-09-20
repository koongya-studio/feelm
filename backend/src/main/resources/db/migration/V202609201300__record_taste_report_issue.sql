-- 홈에서 최초 인화를 완료한 시각. 계산 완료(status) 및 다음 반기 자동 목록 공개와 분리한다.
ALTER TABLE taste_reports ADD COLUMN issued_at timestamptz;
