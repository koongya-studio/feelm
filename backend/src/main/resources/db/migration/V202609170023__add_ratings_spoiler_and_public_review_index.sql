ALTER TABLE ratings
    ADD COLUMN is_spoiler boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN ratings.is_spoiler
    IS '작성자가 한줄평을 스포일러로 표시했는지 여부';

CREATE INDEX ix_ratings_public_reviews_movie_created_id
    ON ratings (movie_id, created_at DESC, id DESC)
    WHERE comment IS NOT NULL
      AND btrim(
              comment,
              U&'\0009\000A\000B\000C\000D\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
          ) <> '';
