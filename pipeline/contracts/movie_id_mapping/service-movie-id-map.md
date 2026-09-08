# 서비스 영화 ID 매핑 계약

MovieLens `movieId`와 서비스 DB의 `movies.id`는 MovieLens `links.csv`가
제공하는 `tmdbId`로 연결한다. 제목·개봉 연도·감독 등으로 ID를 추측하지 않는다.

```text
MovieLens movieId ── links.csv ── tmdbId ── backend export ── movies.id
```

## 백엔드 선행 작업

파이프라인 실행 전에 백엔드는 다음 작업을 완료한다.

1. 서비스 대상 영화를 PostgreSQL `movies`에 적재한다.
2. `movies.id`는 PostgreSQL identity 또는 sequence로 생성한다.
3. `movies.tmdb_id`는 `UNIQUE NOT NULL`로 관리한다.
4. 영화 재적재 시 `tmdb_id`로 upsert하고 기존 `movies.id`를 유지한다.
5. 적재가 끝난 DB에서 아래 CSV를 export한다.

```sql
SELECT
    id AS service_movie_id,
    tmdb_id
FROM movies
ORDER BY id;
```

CSV header는 정확히 다음 두 컬럼이어야 한다.

```csv
service_movie_id,tmdb_id
1001,862
1002,8844
```

두 ID는 양의 정수이며 각각 유일해야 한다. 개발·운영 DB의 `movies.id`는 다를 수 있으므로
각 환경의 DB에서 export를 새로 생성한다.

## 파이프라인 검사

`--service-movies`는 필수다. 다음 경우에는 산출물을 쓰지 않고 실패한다.

- 파일을 읽을 수 없거나 데이터가 없음
- 필수 컬럼이 없음
- `NULL`, 숫자가 아닌 값 또는 0 이하 ID가 있음
- 중복 행이 있음
- 한 ID가 서로 다른 상대 ID와 연결됨

## 산출물

`service-movie-id-map/`은 백엔드 export의 모든 영화를 한 행씩 보존한다.

| 컬럼 | 타입 |
| --- | --- |
| `service_movie_id` | 64-bit integer |
| `tmdb_id` | 32-bit integer |
| `movielens_movie_id` | 32-bit integer, nullable |
| `mapping_status` | string |

| 상태 | 의미 |
| --- | --- |
| `MATCHED` | 하나의 MovieLens ID와 연결됨 |
| `SERVICE_ONLY_CONTENT_BASED` | MovieLens에 없는 서비스·TMDb 영화이며 콘텐츠 기반 추천 대상 |
| `TMDB_ID_AMBIGUOUS` | 같은 TMDb ID가 여러 MovieLens ID와 연결되어 자동 연결에서 제외됨 |

MovieLens ID가 연결되지 않은 상태의 `movielens_movie_id`는 `NULL`이다. 콘텐츠 기반 추천에
필요한 TMDb 메타데이터는 S15P21E106-132의 산출물을 사용한다.
