-- 4컷 리포트의 감독·배우 카드에 영문 표기를 병기하기 위한 원어명 컬럼.
--
-- people.name은 TMDb를 language=ko-KR로 수집한 값이라 인물 전원이 한국어 표기다
-- (backend/scripts/collect-tmdb-raw.sh). 장르는 코드(TMDB_28)에서 영문명을 유도할 수
-- 있었지만 인물은 코드가 없어 별도 컬럼이 필요하다.
--
-- 채우는 범위
--   * TMDb credits에서 name과 다른 라틴 original_name이 확인된 인물을 승인 대상으로 삼는다.
--     (마크 해밀 -> Mark Hamill)
--   * 검토된 외국인의 TMDb person ID와 정확한 original_name 쌍
--     (backend/scripts/data/approved-foreign-person-original-names.tsv)과 일치하는 인물만 채운다.
--     목록에 없는
--     사람을 외국인으로 추정하지 않으므로 '준회' 같은 한국인도 한국어 한 줄로 표시한다.
--   * 외국인이어도 한자·가나 등 비라틴 원어명은 제외한다(宮崎駿).
--   * 승인 후 people.name이 라틴 원어명과 같아져도 canonical original_name은 보존한다.
--     리포트 표시 단계에서 name과 같으면 중복 병기를 숨긴다.
--
-- 따라서 NULL은 "영문 병기 없음"을 뜻하며, 조회 코드는 NULL을 정상 상태로 다룬다.
-- 실제 값은 다음 마이그레이션이 로컬 TMDb 원본 덤프에서 뽑아 채운다(TMDb 재호출 없음).
ALTER TABLE people ADD COLUMN original_name text;

COMMENT ON COLUMN people.original_name IS
    '검토된 외국인의 라틴 문자 원어명. name과 다를 때 리포트에 병기하며, 없으면 NULL이다.';
