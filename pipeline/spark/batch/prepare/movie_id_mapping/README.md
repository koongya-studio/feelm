# Movie ID Mapping

MovieLens 32M의 평점·영화 데이터를 정제하고 MovieLens `movieId`, TMDb ID, 서비스 DB
`movies.id`의 매핑 Parquet을 생성한다.

TMDb 메타데이터 수집·정제는 S15P21E106-132에서 수행한다. 이 파이프라인은 제목이나 감독
정보로 영화를 추측해 연결하지 않고 MovieLens가 제공하는 `links.csv`의 `tmdbId`만 사용한다.

실행환경은 [공통 분산 런타임](../../../../../infra/README.md)을 사용한다. 이 파이프라인은 Spark,
Java 또는 Python 의존성을 별도로 설치하거나 버전을 고정하지 않는다.

## 입력

| 입력 | 필수 컬럼 |
| --- | --- |
| MovieLens `ratings.csv` | `userId`, `movieId`, `rating`, `timestamp` |
| MovieLens `movies.csv` | `movieId`, `title`, `genres` |
| MovieLens `links.csv` | `movieId`, `tmdbId` |
| 백엔드 영화 export CSV | `service_movie_id`, `tmdb_id` |

백엔드 export는 필수다. PostgreSQL 영화 테이블을 먼저 구축한 뒤
[서비스 영화 ID 매핑 계약](../../../../contracts/movie_id_mapping/service-movie-id-map.md)에 맞춰
생성한다. 파일이 없거나 비어 있거나 ID가 잘못됐거나 중복이면 아무 산출물도 쓰지 않고
실패한다.

MovieLens 사용자 ID는 ALS 학습에만 사용하며 서비스 사용자 ID와 연결하지 않는다. 서비스
API에서 사용하는 영화 ID는 `service_movie_id`, 즉 PostgreSQL `movies.id`다.

## 정제와 중복 처리 기준

숫자로 변환할 수 없거나 허용 범위를 벗어난 MovieLens 필수값은 해당 행을 제외하고
검증 보고서의 무효 건수에 포함한다. 평점은 0.5~5.0의 0.5 단위를 유지한다.
`links.csv`의 선택값인 `tmdbId`만 잘못된 경우에는 MovieLens 영화 ID를 유지하고
TMDb ID를 누락값으로 처리한다. 백엔드 export의 잘못된 ID는 기존 계약에 따라 실행을 중단한다.

같은 사용자·영화의 평점은 **MovieLens에 기록된 평가 시각(`timestamp`)이 가장 최신인
유효 평점**을 사용한다. 파일 입력 순서나 배치 수집 시각으로 판단하지 않는다.
최신 시각과 별점이 모두 같은 행은 하나만 남기고, 최신 시각은 같은데 별점이 다르면
원본을 확인하도록 오류로 중단한다. 더 오래된 시각의 평점은 최신 평점을 선택하는 데
사용하지 않는다.

장르는 TMDb 메타데이터가 아닌 **MovieLens `movies.csv`의 복수 장르 목록**을 처리한다.
공백·중복 장르를 제거하고 정렬하여 `Drama|Comedy`와 `Comedy|Drama`를 같은 목록으로
판단한다. 빈 목록과 `(no genres listed)`는 장르를 모르는 상태로 처리한다.
같은 영화 ID에 누락값과 정상 목록이 함께 있으면 정상 목록을 사용한다. 서로 다른
정상 목록이 있으면 임의로 합치거나 선택하지 않고 오류로 중단한다. 같은 영화 ID의
서로 다른 제목도 기존과 같이 오류로 처리한다.

위 상충 중복 검사는 산출물을 쓰기 전에 수행한다. 오류를 해결한 뒤 다시 실행해야 한다.

## 이번 업무의 검증 범위

S15P21E106-118의 완료 기준은 개인 PC의 로컬 Spark에서 fixture를 처리하고
산출물 및 후속 ALS 입력 계약을 확인하는 것이다. 로컬 검증에는 합성 백엔드 export를
사용하므로 실제 PostgreSQL이나 공용 EC2·HDFS 접속이 필요하지 않다.

전체 MovieLens 32M 처리, 실제 DB export 연동, 공용 서버의 분산 실행·HDFS 적재는
별도 통합 검증 범위다. 로컬 fixture 통과를 서버 검증 완료로 표현하지 않는다.
실행 결과·환경·미검증 범위는 [로컬 검증 기록](../../../../docs/movie-id-mapping-local-validation.md)에 남긴다.

## 실행

```bash
cd pipeline/spark
uv sync --frozen
uv run python -m jobs.batch.movie_id_mapping \
  --spark-master 'local[2]' \
  --ratings /data/ml-32m/ratings.csv \
  --movies /data/ml-32m/movies.csv \
  --links /data/ml-32m/links.csv \
  --service-movies /data/feelm/backend/service-movies.csv \
  --output /path/to/local-output/movie-mapping/v1
```

위 입력 경로는 실제 로컬 파일 경로로 바꾼다. 스키마와 상태를 검사하려면 아래 fixture
테스트를 먼저 실행한다. Master는 CLI가 기존 `RECOMMENDATION_SPARK_MASTER`보다 우선한다.
클러스터용 제출 진입점은 `uv run spark-submit --master <master> movie_id_mapping.py`이며
동일한 입력 인자를 사용한다. 운영 접속값·배포는 이 작업에서 추가하지 않는다.

기존 출력 경로는 덮어쓰지 않는다. 다시 생성할 때만 `--write-mode overwrite`를 추가한다.

## 산출물

| 경로 | 내용 |
| --- | --- |
| `ratings/` | 유효한 사용자·영화별 최신 평점 |
| `movies/` | MovieLens 영화와 TMDb ID, 장르, 매핑 상태 |
| `movie-id-map/` | MovieLens ID와 TMDb ID 매핑 |
| `service-movie-id-map/` | 서비스 ID, TMDb ID, MovieLens ID와 매핑 상태 |
| `validation-report/` | 원본·유효·중복·누락·상태별 매핑·출력 건수와 행 수 검사 |

`service-movie-id-map/`의 상태는 다음과 같다.

- `MATCHED`: 하나의 MovieLens ID와 연결됨
- `SERVICE_ONLY_CONTENT_BASED`: MovieLens에 없는 서비스·TMDb 영화이며 콘텐츠 기반 추천 대상
- `TMDB_ID_AMBIGUOUS`: 같은 TMDb ID가 여러 MovieLens ID와 연결되어 자동 연결에서 제외됨

## 검증

```bash
cd pipeline/spark
uv run pytest tests/batch/movie_id_mapping/test_pipeline.py -v
```

테스트 fixture는 매핑 성공, TMDb ID 누락·중복, MovieLens 미매핑 서비스 영화, 잘못된 백엔드
export 거부와 Parquet 재로딩을 검증한다. 생성된 ratings·service-movie-id-map을 기존
`batch.train.inputs.load_training_inputs`로 다시 읽어 ALS 입력 계약도 확인한다.
추가 회귀 테스트는 ANSI 모드에서 숫자 형식 오류·범위 초과 처리, 최신 평점 선택,
복수 장르 정규화와 상충 중복 거부를 확인한다. 중복 행의 입력 순서를 뒤집어도 같은
결과를 내거나 같은 오류로 중단하는지 검증한다.
