# Pipeline

FEELM의 데이터 수집, 전처리, 추천 계산 코드를 관리합니다.

AI 코딩 도구는 루트 [`AGENTS.md`](../AGENTS.md)의 공통 절차와
[`pipeline/AGENTS.md`](AGENTS.md)의 파이프라인 전용 작업·검증 절차를 따릅니다.

- `contracts/`: 파이프라인 내부 HDFS 경로, Parquet 스키마, 모델 산출물 계약
- `spark/`: Spark Batch와 Structured Streaming 작업
- `consumer/`: Fold-in, Candidate, Top-N 계산을 담당하는 Python Consumer
- `configs/`: 학습·평가 설정값 기록. 기존 `settings.py`를 이동하지 않습니다.
- `artifacts/`: 외부 데이터·모델의 출처, 해시, 공유 상태를 담는 JSON 목록
- `templates/`: Pipeline 작업 플랜과 리뷰 기록 양식
- `docs/`: 확정 연구 근거, 평가 설명, 재현 절차와 발표 자료

## Directory layout

```text
pipeline/
├── configs/                     # 실행·실험 설정값 기록
├── artifacts/                   # 대용량 파일 대신 목록·해시 JSON
├── templates/                   # Pipeline 작업 플랜·리뷰 양식
├── docs/                        # 근거·평가·재현·발표 자료
│   ├── assets/                  # 소형 집계 결과와 그림
│   └── presentation/            # 발표 원고와 요약
├── contracts/                   # 내부 HDFS·Parquet·모델 산출물 계약
├── spark/                       # Spark 4.1.3 / Python 3.10.12 실행환경
│   ├── common/                  # 환경변수·CLI 설정과 Spark Session 생성
│   ├── batch/                   # 실행 후 종료되는 Spark Batch 작업
│   │   ├── prepare/             # 원천 데이터 정제와 ID 매핑
│   │   ├── train/               # 오프라인 추천 모델 학습
│   │   └── evaluation/          # 오프라인 추천 품질 평가
│   ├── jobs/                    # spark-submit 진입점
│   ├── streaming/               # 향후 Structured Streaming 작업 위치
│   └── tests/                   # Spark 단위·로컬 통합 테스트
└── consumer/                    # Python 3.12 Fold-in Consumer 위치
```

확정 연구 자료는 [`docs/README.md`](docs/README.md)에서 시작합니다. 계산 참고 코드와
합성 검증 fixture를 제공하며, 운영 연결과 전체 역사 실험 재실행 범위는
[`docs/reproduction.md`](docs/reproduction.md)에 구분해 적었습니다.

## Runtime boundaries

`spark/`와 `consumer/`는 같은 EC2에서 실행될 수 있지만, 서로 다른 컨테이너와 Python
런타임을 사용하는 독립 실행 단위입니다. 정확한 이미지 태그와 배포 구성은 `infra/`에서
관리합니다.

```text
EC2
├── Spark container
│   ├── Spark 4.1.3
│   ├── Java 21
│   ├── Scala 2.13
│   └── Python 3.10.12
└── Fold-in Consumer container
    ├── python:3.12-slim
    ├── Kafka client
    ├── Redis client
    └── Fold-in code
```

## Spark setup

아래 명령은 저장소 루트에서 시작하는 기준입니다.

```bash
cd pipeline/spark
uv sync --frozen
```

시스템에 Spark가 별도로 없어도 `pyspark` 패키지가 제공하는 로컬 런타임으로 검증할 수
있습니다. Java 21은 별도로 설치되어 있어야 합니다.

## Spark configuration

CLI 인자가 같은 목적의 환경변수보다 우선합니다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `RECOMMENDATION_SPARK_MASTER` | 없음 | 직접 실행할 때 사용할 로컬 또는 `spark://...` master URL |
| `RECOMMENDATION_SPARK_APP_NAME` | `feelm-recommendation` | Spark 애플리케이션 이름 |
| `RECOMMENDATION_INPUT_URI` | 없음 | 입력 Parquet URI. 입력을 읽는 Job에서 필수 |
| `RECOMMENDATION_OUTPUT_URI` | 없음 | 출력 Parquet URI. 결과를 저장하는 Job에서 필수 |

영화 dense embedding 생성은 원본 TMDb JSON과 확정된 서비스 영화 ID 매핑을 함께 입력으로
사용한다. 생성·dry-run·트랜잭션 적재 절차는
[`spark/batch/embedding/README.md`](spark/batch/embedding/README.md)를 따른다. PostgreSQL
DSN은 `EMBEDDING_DATABASE_DSN` 환경변수로만 전달하며 저장소·CLI 인자·산출물에 남기지 않는다.

실제 내부 주소나 인증정보는 저장소에 기록하지 않습니다. HDFS URI와 Spark 자원 설정은
인프라·데이터 업무에서 확정한 값을 실행 환경에서 주입합니다.

## Parquet runtime check

입력 Parquet을 읽어 동일한 내용을 출력 경로에 기록하는 최소 실행 점검입니다. 학습이나
전처리 로직은 포함하지 않습니다.

```bash
cd pipeline/spark
uv run python -m jobs.batch.parquet_roundtrip \
  --spark-master 'local[1]' \
  --input-uri /path/to/input \
  --output-uri /path/to/output
```

클러스터에서는 `spark-submit`과 환경별 master URL을 사용합니다.

```bash
cd pipeline/spark
uv run spark-submit \
  --master "$RECOMMENDATION_SPARK_MASTER" \
  parquet_roundtrip.py \
  --input-uri "$RECOMMENDATION_INPUT_URI" \
  --output-uri "$RECOMMENDATION_OUTPUT_URI"
```

## Spark verification

Spark 코드는 `pipeline/spark`를 Python 프로젝트 루트로 사용합니다.

```bash
cd pipeline/spark
uv run pytest
uv run ruff check .
uv run python -m jobs.batch.parquet_roundtrip --help
```

`consumer/`는 Python 3.12 독립 프로젝트이며 `pyproject.toml`, `uv.lock`과 전용
`Dockerfile`을 사용합니다. 로컬 검증과 컨테이너 빌드·실행 방법은
[`consumer/README.md`](consumer/README.md)를 따릅니다.

## ALS baseline input and settings

118번 MovieLens 전처리와 영화 ID 매핑은
[`spark/batch/prepare/movie_id_mapping/`](spark/batch/prepare/movie_id_mapping/README.md)에 있습니다.
로컬 fixture 검증과 공용 서버 통합 검증 범위는
[`docs/movie-id-mapping-local-validation.md`](docs/movie-id-mapping-local-validation.md)에서 구분합니다.

ALS 배치는 S15P21E106-118 산출물의 루트 URI를 입력으로 받으며 그 아래 `ratings/`와
`service-movie-id-map/` Parquet을 읽습니다. 학습 전에 필수 컬럼·타입, 평점 범위,
사용자-영화 중복 및 서비스 매핑 상태를 검사합니다.

`als_baseline`은 기본 `validate` 모드에서 입력을 읽고 검증 건수만 출력합니다. `run` 모드는
같은 입력을 한 번 분할해 각 설정을 validation NDCG@10, Recall@10 순으로 비교합니다. 동률이면
rank, maxIter, regParam이 작은 설정을 선택합니다. 선택 설정의 train+validation 모델로 test를
한 번 평가한 뒤 전체 평점으로 SERVING 모델을 별도 학습합니다.

```bash
cd pipeline/spark
uv run python -m jobs.batch.als_baseline \
  --mode validate \
  --spark-master 'local[2]' \
  --input-uri /path/to/movie-mapping/v1 \
  --seed 42 \
  --als-config 32:0.1:10 \
  --als-config 64:0.1:10
```

`--als-config`는 `rank:regParam:maxIter` 형식이며 여러 번 지정할 수 있습니다. 생략하면
기본 비교 설정 4종을 사용합니다. 중복 설정과 signed 64-bit 범위 밖의 seed는 실행 전에
거부합니다. CLI 인자가 같은 이름의 환경변수보다 우선합니다.

전체 로컬 또는 클러스터 실행에는 재현 정보를 CLI로 명시합니다. run ID를 생략하면 고유한
값을 생성합니다. 입력과 출력의 중첩, credential 포함 URI, `latest` 입력 식별자, 기존 run과
모델 버전 덮어쓰기는 거부합니다. `run` 모드의 입력·출력·manifest는 Hadoop `defaultFS`나
현재 작업 디렉터리에 따라 의미가 바뀌지 않도록 `file:///절대/경로` 또는
`hdfs://authority/절대/경로`로 모두 명시해야 합니다. scheme 없는 경로, 상대 `file:` URI,
authority 없는 `hdfs:/` URI는 SparkSession 생성 전에 거부합니다.

118 산출물과 원천 4종을 같은 snapshot root 아래에 배치한 뒤 다음 명령으로 manifest를
생성합니다. 생성기는 보이는 118 파일과 원천 파일을 직접 해시해 no-overwrite로 기록하고,
그 결과를 생산 validator로 다시 읽어 validation report·로딩 건수까지 확인한 후 snapshot ID를
출력합니다. `--preprocessing-settings-json`에는 실제 118 실행 설정을 JSON object로 전달합니다.

```bash
cd pipeline/spark
uv run spark-submit --master "$RECOMMENDATION_SPARK_MASTER" als_input_manifest.py \
  --input-uri "$ALS_VERIFY_PREPARED_URI" \
  --manifest-uri "$ALS_VERIFY_MANIFEST_URI" \
  --ratings-source "$ALS_VERIFY_RATINGS_URI" \
  --movies-source "$ALS_VERIFY_MOVIES_URI" \
  --links-source "$ALS_VERIFY_LINKS_URI" \
  --service-movie-export "$ALS_VERIFY_SERVICE_MOVIES_URI" \
  --preprocessing-revision "$ALS_VERIFY_PREPROCESSING_REVISION" \
  --preprocessing-settings-json "$ALS_VERIFY_PREPROCESSING_SETTINGS_JSON"
```

manifest와 모든 참조 파일은 manifest parent 아래에 있어야 하며, 기존 manifest는 덮어쓰지
않습니다. 생성·검증에 실패한 새 manifest는 제거되므로 원인을 고친 뒤 같은 위치에서 다시
생성할 수 있습니다.

```bash
cd pipeline/spark
uv run spark-submit --master "$RECOMMENDATION_SPARK_MASTER" als_baseline.py \
  --mode run \
  --input-uri "$ALS_VERIFY_PREPARED_URI" \
  --output-uri "$ALS_VERIFY_OUTPUT_URI" \
  --input-manifest-uri "$ALS_VERIFY_MANIFEST_URI" \
  --input-snapshot "$ALS_VERIFY_SNAPSHOT_ID" \
  --run-id "$ALS_VERIFY_RUN_ID" \
  --implementation-revision "$ALS_VERIFY_REVISION" \
  --movie-metadata-revision "$MOVIE_METADATA_REVISION" \
  --seed 42 --train-ratio 0.8 --validation-ratio 0.1 \
  --positive-rating-threshold 4.0 --candidate-size 500 \
  --als-config 32:0.1:10 --als-config 64:0.1:10 \
  --als-config 64:0.05:10 --als-config 64:0.1:15
```

`--input-manifest-uri`는 CLI 전용이며 manifest JSON 본문의 SHA-256이
`--input-snapshot`의 `sha256:<digest>`와 같아야 합니다. manifest는 118번의 `ratings/`,
`movies/`, `movie-id-map/`, `service-movie-id-map/`, `validation-report/` 아래의 보이는 일반 파일을
빠짐없이 나열하고 각 파일의 SHA-256을 기록해야 합니다. `sources`는 원천 `ratings`, `movies`,
`links`, 실제 DB `service_movie_export` 네 역할을 각각 `files`의 해시 항목에 연결해야 합니다.
비어 있지 않은 문자열 `preprocessing_revision`과 비어 있지 않은 JSON object
`preprocessing_settings`도 필수입니다. 경로 구성요소가 `.` 또는 `_`로 시작하는 숨김 파일·디렉터리와
`_SUCCESS` 같은 완료 marker는 manifest에 기록하지 않으며 실제 파일 집합 비교에서도 제외합니다.
실행은 실제 보이는 파일 집합을 열거하고 파일을 다시 해시한 뒤 validation report의 완료 상태와
ratings·service mapping 건수를 실제 로딩 결과와 대조해야만 분할을 시작합니다.
`run` 모드는 `service-movie-id-map` 전체에서 유일한 `mapping_revision`을 검증해 모델 메타데이터에
기록합니다. `--movie-metadata-revision`에는 먼저 공개하고 검증한 영화 metadata snapshot의
`sha256:<64 소문자 hex>` revision을 전달하며, 누락되거나 형식이 잘못되면 학습 전에 거부합니다.

`ALS_VERIFY_*`는 실행 절차의 셸 변수이며 애플리케이션 환경변수 계약이 아닙니다. 모델은
`<output-uri>/als/<model-version>/`, 실행 보고서는 `<output-uri>/als-runs/<run-id>/`에
저장됩니다. 세 모델 단계와 보고서 의미, 실패·재시도 방법은
[`spark/batch/train/README.md`](spark/batch/train/README.md)를 따릅니다.

환경변수만 사용해 동일한 입력 검증을 실행할 수도 있습니다.

```bash
export RECOMMENDATION_SPARK_MASTER='local[2]'
export RECOMMENDATION_SPARK_APP_NAME='feelm-als-input-validation'
export RECOMMENDATION_INPUT_URI='/path/to/movie-mapping/v1'
export RECOMMENDATION_ALS_SEED='42'
export RECOMMENDATION_TRAIN_RATIO='0.8'
export RECOMMENDATION_VALIDATION_RATIO='0.1'
export RECOMMENDATION_POSITIVE_RATING_THRESHOLD='4.0'
export RECOMMENDATION_CANDIDATE_SIZE='500'
export RECOMMENDATION_ALS_CONFIGS='32:0.1:10,64:0.1:10'

cd pipeline/spark
uv run python -m jobs.batch.als_baseline
```

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `RECOMMENDATION_SPARK_MASTER` | 없음 | 직접 실행할 Spark master URL |
| `RECOMMENDATION_SPARK_APP_NAME` | `feelm-als-baseline` | Spark 애플리케이션 이름 |
| `RECOMMENDATION_INPUT_URI` | 없음 | 118번 산출물 루트 URI. 실행 시 필수 |
| `RECOMMENDATION_OUTPUT_URI` | 없음 | 모델과 실행 보고서 루트. `run` 모드에서 필수 |
| `RECOMMENDATION_ALS_SEED` | `42` | 분할 및 ALS 초기화 seed |
| `RECOMMENDATION_TRAIN_RATIO` | `0.8` | 사용자별 train 비율 |
| `RECOMMENDATION_VALIDATION_RATIO` | `0.1` | 사용자별 validation 비율 |
| `RECOMMENDATION_POSITIVE_RATING_THRESHOLD` | `4.0` | 선호 영화 판정 평점 |
| `RECOMMENDATION_CANDIDATE_SIZE` | `500` | 사용자별 Candidate 수 |
| `RECOMMENDATION_ALS_CONFIGS` | 기준 설정 4종 | 쉼표로 구분한 ALS 설정 목록 |
