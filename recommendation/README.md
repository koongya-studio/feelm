# Recommendation

FEELM의 추천 시스템 소스를 실행환경별로 관리합니다.

## Runtime

| 영역 | 런타임 | 역할 |
| --- | --- | --- |
| `batch/` | Python 3.10.12, Spark/PySpark 4.1.3, Java 21 | 전처리·학습·평가 Spark Job |
| `consumer/` | Python 3.12 (`python:3.12-slim`) | Fold-in과 실시간 Candidate 갱신 |

인프라의 정확한 이미지와 클러스터 구성은 [`infra/README.md`](../infra/README.md)를
따릅니다.

두 영역은 Python 버전과 의존성 잠금 파일을 공유하지 않습니다. 현재 `consumer/`에는 향후
구조를 표시하는 `.gitkeep`만 있으며, 코드와 Kafka·Redis 의존성은 S15P21E106-199,
컨테이너 구성은 S15P21E106-129에서 추가합니다.

## Directory layout

```text
recommendation/
├── batch/
│   ├── pyproject.toml      # PySpark batch 전용 Python 3.10 프로젝트
│   ├── uv.lock
│   ├── common/             # 환경변수·CLI 설정과 Spark Session 생성
│   ├── jobs/               # 실행 가능한 Spark Job 진입점
│   ├── preprocessing/      # MovieLens·TMDb 정제와 ID 매핑
│   ├── training/           # ALS 및 오프라인 모델 학습
│   ├── evaluation/         # Recall·NDCG 등 오프라인 평가
│   └── tests/              # 단위·로컬 Spark 통합 테스트
└── consumer/
    └── .gitkeep            # S15P21E106-199/-129의 작업 위치
```

원본 데이터, Parquet, 모델, 체크포인트는 Git에서 제외합니다. 실제 산출물은 HDFS 등 외부
저장소에 기록하고 코드에는 환경별 절대 경로를 넣지 않습니다.

## Setup

아래 명령은 저장소 루트에서 시작하는 기준입니다.

```bash
cd recommendation/batch
uv sync --frozen
```

시스템에 Spark가 별도로 없어도 `pyspark` 패키지가 제공하는 로컬 런타임으로 검증할 수
있습니다. Java 21은 별도로 설치되어 있어야 합니다.

## Configuration

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
cd recommendation/batch
uv run python -m jobs.parquet_roundtrip \
  --spark-master 'local[1]' \
  --input-uri /path/to/input \
  --output-uri /path/to/output
```

클러스터에서는 `spark-submit`과 환경별 master URL을 사용합니다.

```bash
cd recommendation/batch
uv run spark-submit \
  --master "$RECOMMENDATION_SPARK_MASTER" \
  parquet_roundtrip.py \
  --input-uri "$RECOMMENDATION_INPUT_URI" \
  --output-uri "$RECOMMENDATION_OUTPUT_URI"
```

## Verification

```bash
cd recommendation/batch
uv run pytest
uv run ruff check .
```

`tests/test_parquet_roundtrip.py`는 임시 디렉터리에 샘플 Parquet을 만들고 Spark로 다시
읽어 행 내용이 유지되는지 검증합니다.
