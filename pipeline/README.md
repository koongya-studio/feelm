# Pipeline

FEELM의 데이터 수집, 전처리, 추천 계산 코드를 관리합니다.

- `contracts/`: 파이프라인 내부 HDFS 경로, Parquet 스키마, 모델 산출물 계약
- `spark/`: Spark Batch와 Structured Streaming 작업
- `consumer/`: Fold-in, Candidate, Top-N 계산을 담당하는 Python Consumer

## Directory layout

```text
pipeline/
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
| `RECOMMENDATION_INPUT_URI` | 없음 | 입력 Parquet URI. Job 실행 시 필수 |
| `RECOMMENDATION_OUTPUT_URI` | 없음 | 출력 Parquet URI. Job 실행 시 필수 |

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
