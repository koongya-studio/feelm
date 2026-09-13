-- API-028: 전체 단어와 부분 문자열의 시그마 문맥 차이를 없앤다.
-- Java MovieSearchCriteria의 ROOT lower + final sigma 정규화와 함께 유지한다.
CREATE FUNCTION movie_search_normalize(input_text text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN replace(lower(input_text COLLATE "und-x-icu"), 'ς', 'σ');

-- 기존 migration은 보존한다. LIKE·trigram·full-text 모두 실제 조회와 같은 식으로 교체한다.
DROP INDEX ix_movies_title_trgm;
DROP INDEX ix_movies_original_title_trgm;
DROP INDEX ix_people_name_trgm;
DROP INDEX ix_movies_title_fulltext;
DROP INDEX ix_movies_original_title_fulltext;

CREATE INDEX ix_movies_title_trgm
    ON movies USING gin (movie_search_normalize(title) gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_movies_original_title_trgm
    ON movies USING gin (movie_search_normalize(original_title) gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_people_name_trgm
    ON people USING gin (movie_search_normalize(name) gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_movies_title_fulltext
    ON movies USING gin (to_tsvector('simple', coalesce(movie_search_normalize(title), '')));
CREATE INDEX ix_movies_original_title_fulltext
    ON movies USING gin (to_tsvector('simple', coalesce(movie_search_normalize(original_title), '')));
