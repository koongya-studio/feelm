-- API-028 / ADR-0010. Java Locale.ROOT와 DB의 Unicode 소문자 변환 의미를 맞춘다.
-- 기존 migration 원문은 보존하고 검색식과 동일한 ICU root 표현식으로 인덱스를 교체한다.
DROP INDEX ix_movies_title_trgm;
DROP INDEX ix_movies_original_title_trgm;
DROP INDEX ix_people_name_trgm;

CREATE INDEX ix_movies_title_trgm
    ON movies USING gin (lower(title COLLATE "und-x-icu") gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_movies_original_title_trgm
    ON movies USING gin (lower(original_title COLLATE "und-x-icu") gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_people_name_trgm
    ON people USING gin (lower(name COLLATE "und-x-icu") gin_trgm_ops) WITH (fastupdate = off);
