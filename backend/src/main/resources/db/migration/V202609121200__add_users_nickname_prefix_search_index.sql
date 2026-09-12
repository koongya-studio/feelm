-- 사용자 검색(API-019, S15P21E106-153)의 닉네임 접두 일치용 인덱스.
-- 검색은 닉네임 유일성과 같은 정규화식 lower(replace(nickname, ' ', ''))으로 판단한다.
-- 이미 있는 uix_users_nickname_normalized는 DB 기본 콜레이션이라 등호 조회에만 쓰이고
-- LIKE 'x%' 접두 조건에는 쓰이지 않는다. text_pattern_ops로 정렬 기준을 바이트 순으로
-- 바꾼 인덱스를 따로 둬야 접두 조건이 인덱스를 탄다.
-- 부분 일치 요구가 생기면 pg_trgm GIN을 별도로 검토한다. 접두 전용에는 btree가 더 싸다.
CREATE INDEX ix_users_nickname_normalized_pattern
    ON users (lower(replace(nickname, ' ', '')) text_pattern_ops);
