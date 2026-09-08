# Pipeline

FEELM의 데이터 수집, 전처리, 추천 계산 코드를 관리합니다.

- `contracts/`: 파이프라인 내부 HDFS 경로, Parquet 스키마, 모델 산출물 계약
- `spark/`: Spark Batch와 Structured Streaming 작업
- `consumer/`: Fold-in, Candidate, Top-N 계산을 담당하는 Python Consumer
- `configs/`: 학습·평가 설정값 기록. 기존 `settings.py`를 이동하지 않습니다.
- `artifacts/`: 외부 데이터·모델의 출처, 해시, 공유 상태를 담는 JSON 목록
- `docs/`: 확정 연구 근거, 평가 설명, 재현 절차와 발표 자료

## Directory layout

```text
pipeline/
├── configs/                     # 실행·실험 설정값 기록
├── artifacts/                   # 대용량 파일 대신 목록·해시 JSON
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

`consumer/`는 현재 Python 3.12 런타임 경계만 명시합니다. 독립 `pyproject.toml`,
`uv.lock`, Kafka·Redis 의존성은 Consumer 구현 시점에 추가합니다.

## ALS baseline input and settings

118번 MovieLens 전처리와 영화 ID 매핑은
[`spark/batch/prepare/movie_id_mapping/`](spark/batch/prepare/movie_id_mapping/README.md)에 있습니다.
로컬 fixture 검증과 공용 서버 통합 검증 범위는
[`docs/movie-id-mapping-local-validation.md`](docs/movie-id-mapping-local-validation.md)에서 구분합니다.

ALS 배치는 S15P21E106-118 산출물의 루트 URI를 입력으로 받으며 그 아래 `ratings/`와
`service-movie-id-map/` Parquet을 읽습니다. 학습 전에 필수 컬럼·타입, 평점 범위,
사용자-영화 중복 및 서비스 매핑 상태를 검사합니다.

현재 `als_baseline` 진입점은 입력을 읽고 검증 건수만 출력합니다. 데이터 분할, 단일 설정
ALS 학습과 모델 산출물 저장·로딩 함수는 구현되어 있지만 진입점에는 아직 연결되지 않았으며,
평가도 수행하지 않습니다. 따라서 `--input-uri`는 필수이고 `--output-uri`는 선택값이며,
지정해도 현재 실행에서는 파일을 기록하지 않습니다.

```bash
cd pipeline/spark
uv run python -m jobs.batch.als_baseline \
  --spark-master 'local[2]' \
  --input-uri /path/to/movie-mapping/v1 \
  --seed 42 \
  --als-config 32:0.1:10 \
  --als-config 64:0.1:10
```

`--als-config`는 `rank:regParam:maxIter` 형식이며 여러 번 지정할 수 있습니다. 생략하면
기본 비교 설정 4종을 사용합니다. 현재 진입점은 학습 설정을 읽고 유효성만 검사하며,
분할·학습 함수 연결과 반복 실행은 후속 구현에서 수행합니다. CLI 인자가 같은 이름의
환경변수보다 우선합니다.

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
| `RECOMMENDATION_OUTPUT_URI` | 없음 | 후속 모델 저장용 URI. 현재는 선택값이며 사용하지 않음 |
| `RECOMMENDATION_ALS_SEED` | `42` | 분할 및 ALS 초기화 seed |
| `RECOMMENDATION_TRAIN_RATIO` | `0.8` | 사용자별 train 비율 |
| `RECOMMENDATION_VALIDATION_RATIO` | `0.1` | 사용자별 validation 비율 |
| `RECOMMENDATION_POSITIVE_RATING_THRESHOLD` | `4.0` | 선호 영화 판정 평점 |
| `RECOMMENDATION_CANDIDATE_SIZE` | `500` | 사용자별 Candidate 수 |
| `RECOMMENDATION_ALS_CONFIGS` | 기준 설정 4종 | 쉼표로 구분한 ALS 설정 목록 |
