-- S15P21E106-856: 취향 필름·팝콘 버킷·한줄평의 항목별 공개 범위.
-- 기본값 PUBLIC은 기존 계정 행이 지금까지와 같은 노출(공개 프로필이면 필름·팝콘·공개 한줄평이 보임)을
-- 그대로 유지하게 하는 값이다(ERD users, db-schema-convention 4장 예외).
-- 상수 DEFAULT의 ADD COLUMN은 PostgreSQL 11 이상에서 테이블을 다시 쓰지 않는다.
ALTER TABLE users
    ADD COLUMN frame_visibility   varchar(10) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN popcorn_visibility varchar(10) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN review_visibility  varchar(10) NOT NULL DEFAULT 'PUBLIC',
    ADD CONSTRAINT ck_users_frame_visibility
        CHECK (frame_visibility IN ('PUBLIC', 'PRIVATE')),
    ADD CONSTRAINT ck_users_popcorn_visibility
        CHECK (popcorn_visibility IN ('PUBLIC', 'PRIVATE')),
    ADD CONSTRAINT ck_users_review_visibility
        CHECK (review_visibility IN ('PUBLIC', 'PRIVATE'));
