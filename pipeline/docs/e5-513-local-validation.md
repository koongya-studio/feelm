# S15P21E106-513: E5 산출물 로컬 격리 검증

검증일: 2026-09-19. 공유 HDFS·DB에는 접속하지 않았다. 기존 로컬 원본을 `/tmp`에 복사해
봉인했고, 임시 `pgvector/pgvector:0.8.6-pg17-bookworm` 컨테이너만 사용한 뒤 종료했다.
이 결과는 Jira 513의 로컬 검증 범위에 대한 증거이며 운영 환경의 상태를 뜻하지 않는다.

## 산출물과 전체 대상

| 항목 | 결과 |
| --- | --- |
| 원본 embedding version | `e5-base-gpu-20260916T073404Z` |
| 검증용 새 version | `e5-base-gpu-20260916T073404Z-sealed-513` (`/tmp`에만 생성) |
| 원본 embedding Parquet SHA-256 | `3a45661e3add11755534718c7bee91da3a5ee7061a51fdb7ea46f2c716c66bc3` (복사·봉인 전후 동일) |
| 모델 revision | `d128750597153bb5987e10b1c3493a34e5a4502a` |
| 차원·입력 snapshot SHA-256 | 768 · `5b30e1658e77c6cbcc99a0c0247e4bf119d1411408354e37f01cafd573e69fd5` |
| 서비스 영화 매핑 대상 / 봉인본 | 237,817 / 237,817 |
| 영화 ID·TMDb ID 누락 / 초과 | 0 / 0 |

따라서 이 로컬 대상 집합에서 산출물 보유 237,817건, 제외 0건, 실패·누락 0건이다.
이는 생성 당시의 작업 로그나 공유 DB 적재 건수를 독립 감사한 수치는 아니다.
봉인 명령은 새 버전의 `embeddings/`, `metadata/`, `manifest/`를 작성한 뒤 다시 읽어
파일 SHA-256, metadata, 행 수와 입력 snapshot 해시를 검증하고 게시했다.
별도 복사본의 Parquet 1바이트를 바꾸자 `embedding manifest file checksum mismatch`로 거부했다.

## 격리 PostgreSQL 검증

서비스 DB와 분리한 일회용 컨테이너에 `movie_embeddings`의 768차원·영화 ID 유일 제약·FK와
HNSW 인덱스를 만들어 다음을 확인했다.

| 경우 | 결과 |
| --- | --- |
| dry-run | 임시 staging 트랜잭션 rollback, 본 테이블 0건 유지 |
| SQL 삽입 실패 주입 | transaction rollback, 본 테이블 0건 유지 |
| 같은 산출물의 로더 적용 재시도 | 행 수 3건 유지, `updated_at` 불변 |
| 같은 model version의 변경 벡터 | 거부, 기존 행 불변 |
| 다른 model version 혼입 | 거부 |
| 영화의 TMDb ID 변경 | 거부 |
| 동일 산출물 동시 재시도 | 둘 다 성공, 행 수 3건 유지 |
| 동일 산출물과 변경 산출물 동시 실행 | 정상 실행 하나만 성공, 변경 산출물 거부 |

실제 E5 산출물의 벡터 512개를 같은 임시 DB에 넣어 기준 영화 5편의 자기 자신 제외
cosine Top-10을 비교했다. 정확 검색과 HNSW 검색의 집합은 다섯 번 모두 10/10 일치했고,
`EXPLAIN`은 HNSW 인덱스 사용을 보였다. 평균 응답 시간은 정확 검색 2.863ms,
HNSW 2.376ms였다. 이 수치는 작은 로컬 표본의 참고값이며 운영 DB의 recall·지연을
대변하지 않는다.

격리 DB는 `pgvector/pgvector:0.8.6-pg17-bookworm`의 새 일회용 컨테이너로 만들었다.
`movies`에 `(id, tmdb_id)` 3쌍 `(1,101)`, `(2,102)`, `(3,103)`을 넣고,
419 migration과 같은 `movie_embeddings`의 `movie_id` 유일 제약·FK·`vector(768)`을
구성했다. 각 영화에 서로 직교하는 단위 벡터를 넣어 로더를 재실행하고,
`BEFORE INSERT`에서 예외를 던지는 임시 trigger로 SQL 실패를 주입했다. 모델 버전·
TMDb ID·세 번째 벡터를 각각 변경한 뒤 거부와 원복을 확인했다. 검색 표본은 봉인본에서
처음 읽힌 512개 벡터를 넣고, 정확 검색에서는 index scan을, HNSW에서는 seq scan을
세션 안에서 끈 뒤 Top-10 결과와 `EXPLAIN`을 비교했다. 일회성 실행 스크립트는 Git에
포함하지 않는다.

## 재실행 경계

- `movie_embeddings` 생성 Job은 산출물만 만들며 DB에 연결하지 않는다. 같은 산출물 버전의
  재게시 시도는 기존 경로 존재 검사에서 거부된다. 다만 현재 구현은 생성 계산 뒤에
  게시 함수에서 검사하므로 같은 버전 재실행이 추론 자원을 먼저 사용할 수 있다.
- DB 적재는 별도 `load_movie_embeddings` Job에 `--apply`를 지정해야 일어난다. 기본
  dry-run은 임시 staging을 rollback한다.
- 로더는 `movie_id` 유일 제약, 테이블 잠금, 기존 행의 model version·영화 집합·벡터
  대조를 사용한다. 동일 데이터 재시도는 중복 행을 만들지 않고, 다른 데이터나 버전은
  rollback한다. 공유 DB 전체에 대한 재시도는 이번에 실행하지 않았다.

## 실행과 한계

원본 사본에서 아래 명령의 `output-uri`만 로컬 작업 디렉터리로 설정해 봉인했다.

```bash
python -m jobs.batch.seal_legacy_movie_embeddings \
  --output-uri 'file:///절대/로컬/검증/경로' \
  --legacy-version e5-base-gpu-20260916T073404Z \
  --new-version e5-base-gpu-20260916T073404Z-sealed-513 \
  --spark-master 'local[2]'
```

변경 영역 `test_embedding_e5.py`, `test_embedding_postgres.py`, `test_embedding_source.py`는
Spark driver와 worker를 모두 Python 3.10으로 설정해 **31개 통과**했다. 변경 영역
Ruff도 통과했다. 초기에는 샌드박스 localhost 소켓 차단과 worker Python 3.12 / driver
3.10 불일치로 실행 환경 오류가 났고, 각각 허용된 로컬 실행과 동일 버전 지정으로 해결했다.

```bash
cd pipeline/spark
E5_TEST_PYTHON="$(uv run --frozen python -c 'import sys; print(sys.executable)')"
SPARK_LOCAL_IP=127.0.0.1 \
PYSPARK_PYTHON="$E5_TEST_PYTHON" \
PYSPARK_DRIVER_PYTHON="$E5_TEST_PYTHON" \
"$E5_TEST_PYTHON" -m pytest -q tests/batch/test_embedding_e5.py \
  tests/batch/test_embedding_postgres.py tests/batch/test_embedding_source.py
uv run ruff check batch/embedding jobs/batch/load_movie_embeddings.py \
  jobs/batch/seal_legacy_movie_embeddings.py tests/batch/test_embedding_e5.py \
  tests/batch/test_embedding_postgres.py tests/batch/test_embedding_source.py
```

실제 HDFS 게시·복제·권한, 공유 DB의 건수·FK·버전·전체 검색 성능, 새 Spark 이미지의
ALS smoke는 수행하지 않았다. 운영 데이터 재적재, DB DDL, API 변경도 하지 않았다.
