-- API-028 / ADR-0010. 검색 쿼리의 정규화 표현식과 동일하게 유지한다.
-- 제목·인물명은 조회 중심이다. 적재 직후에도 큰 pending list를 스캔하지 않도록
-- trigram 인덱스는 즉시 갱신한다. 적재 쓰기 비용과 실데이터 성능은 156 완료 후 확인한다.
CREATE INDEX ix_movies_title_trgm
    ON movies USING gin (lower(title) gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_movies_original_title_trgm
    ON movies USING gin (lower(original_title) gin_trgm_ops) WITH (fastupdate = off);
CREATE INDEX ix_people_name_trgm
    ON people USING gin (lower(name) gin_trgm_ops) WITH (fastupdate = off);

CREATE INDEX ix_movies_title_fulltext
    ON movies USING gin (to_tsvector('simple', coalesce(title, '')));
CREATE INDEX ix_movies_original_title_fulltext
    ON movies USING gin (to_tsvector('simple', coalesce(original_title, '')));
