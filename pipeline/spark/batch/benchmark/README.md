# ALS distributed benchmark

발표용 합성 데이터 기반 XGBoost 분산 학습은 별도 [GBT 실행 안내](GBT-README.md)를 따른다.

이 작업은 검증된 MovieLens ratings Parquet으로 **ALS 모델 하나**를 학습해 Spark Worker
scale-out 효과를 측정한다. 운영 baseline의 모델 선택·오프라인 평가·서빙 산출물은 실행하지
않는다. 결과는 추천 품질 비교가 아니라 동일 연산의 처리 시간·처리량 비교에만 사용한다.

## 고정 조건

- 입력 크기: 1,000,000 / 10,000,000 / 32,000,204행
- ALS: `rank=64`, `regParam=0.1`, `maxIter=10`, `seed=42`
- 1 Worker: 2 executor cores / 4 GiB Worker
- 2 Workers: 총 4 executor cores / Worker당 4 GiB
- Spark application event log를 활성화하고 결과의 application ID로 History Server를 확인한다.

1M·10M 입력은 `prepare`가 `(xxhash64(seed, user, movie), user, movie)` 순서의 정확한 N행을
선택해 한 번 게시한다. 임의 `limit()` 결과나 실행마다 다시 만든 입력을 비교에 사용하지 않는다.
32M은 검증된 immutable snapshot의 `prepared/ratings`를 직접 사용한다.

## 입력 준비

모든 URI는 `file:///절대/경로` 또는 `hdfs://authority/절대/경로`로 명시한다. 출력은 기존
경로를 덮어쓰지 않으며 임시 sibling을 검증한 뒤 원자적으로 게시한다.

```bash
/opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --deploy-mode client \
  --total-executor-cores 4 \
  --executor-cores 2 \
  --executor-memory 2g \
  --conf spark.hadoop.dfs.replication=2 \
  /opt/feelm/pipeline/spark/jobs/batch/als_benchmark.py \
  prepare \
  --source-uri "$BENCHMARK_SOURCE_RATINGS_URI" \
  --output-uri "$BENCHMARK_INPUT_ROOT/ml-1m" \
  --input-id ml-1m-seed42 \
  --source-snapshot "$BENCHMARK_SOURCE_SNAPSHOT" \
  --size 1000000 \
  --seed 42
```

10M은 고유 출력 URI와 `--size 10000000`, `--input-id ml-10m-seed42`로 같은 명령을
실행한다. `<output>/ratings`와 `metadata.json`의 행 수·source snapshot을 확인한다.

## 한 번의 측정

1 Worker 실행 전 다른 Spark 앱이 없고 Worker 하나만 ALIVE인지 확인한다. 2 Workers 실행은
둘 다 ALIVE인지 확인한다. Worker 중단·복구는 팀과 합의한 점검 시간에만 수행하며 HDFS
DataNode는 중단하지 않는다.

```bash
/opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --deploy-mode client \
  --total-executor-cores "$BENCHMARK_TOTAL_CORES" \
  --executor-cores 2 \
  --executor-memory 2g \
  --conf spark.hadoop.dfs.replication=2 \
  --conf spark.driver.host=spark-master \
  --conf spark.driver.bindAddress=0.0.0.0 \
  --conf spark.driver.port=7079 \
  --conf spark.driver.blockManager.port=7080 \
  --conf spark.blockManager.port=7081 \
  --conf spark.port.maxRetries=0 \
  /opt/feelm/pipeline/spark/jobs/batch/als_benchmark.py \
  run \
  --input-uri "$BENCHMARK_RATINGS_URI" \
  --input-manifest-uri "$BENCHMARK_INPUT_MANIFEST_URI" \
  --result-uri "$BENCHMARK_RESULT_URI" \
  --input-id "$BENCHMARK_INPUT_ID" \
  --input-snapshot "$BENCHMARK_INPUT_SNAPSHOT" \
  --expected-count "$BENCHMARK_EXPECTED_COUNT" \
  --worker-count "$BENCHMARK_WORKERS" \
  --implementation-revision "$BENCHMARK_IMPLEMENTATION_REVISION" \
  --sequence "$BENCHMARK_SEQUENCE" \
  --rank 64 --reg-param 0.1 --max-iter 10 --seed 42
```

각 실행은 고유 result URI를 사용한다. JSON은 입력·ALS 설정, application ID, Worker probe,
factor 수, 검증·학습·전체 시간과 학습 rows/sec를 기록한다. Worker probe가 요청한 Worker 수를
관찰하지 못하면 성공 결과를 게시하지 않는다. ALS stage의 실제 task 참여는 History Server의
Executors와 Stages 화면에서 application ID로 별도 확인한다.

## 비교와 반복

```bash
/opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  /opt/feelm/pipeline/spark/jobs/batch/als_benchmark.py \
  compare \
  --single-result-uri "$SINGLE_RESULT_URI" \
  --distributed-result-uri "$DISTRIBUTED_RESULT_URI"
```

비교기는 manifest SHA-256과 ratings 파일별 SHA-256을 실행 전에 다시 검사하고, 입력 ID·URI·행 수,
ALS 설정과 구현 revision이 다르면 거부한다. 각 입력은 `1 → 2 → 2 → 1 → 1 → 2 Worker` 순서로
정확히 6회 실행한다. 각 실행의 `--sequence`는 1~6이며 결과 URI는 모두 달라야 한다.

18개 결과를 모두 `--report-uri`로 전달하면 완전성·순서·동일 입력·동일 코드를 검증하고 크기별
중앙값을 no-overwrite JSON으로 게시한다.

```bash
/opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  /opt/feelm/pipeline/spark/jobs/batch/als_benchmark.py \
  aggregate \
  --report-uri "$RESULT_1" \
  --report-uri "$RESULT_2" \
  # 같은 형식으로 RESULT_3부터 RESULT_17까지 추가 \
  --report-uri "$RESULT_18" \
  --output-uri "$BENCHMARK_SUMMARY_URI"
```

`speedup = 1 Worker 학습 시간 중앙값 / 2 Workers 학습 시간 중앙값`이며,
작은 입력에서 분산 오버헤드로 speedup이 1 미만일 수 있다.

## 검증 및 정리

- 실행 전후 Spark Master의 `activeapps`, `activedrivers`, Worker ALIVE 수를 기록한다.
- 결과의 ratings count와 user/item factor count가 양수인지 확인한다.
- History Server에서 application ID, Executor host, stage/task 분배와 실패 task를 확인한다.
- 원본 snapshot과 운영 ALS 경로는 읽기 전용이다.
- 정리는 담당자가 이번 실행의 정확한 benchmark root를 확인한 후에만 수행한다. 상위 공용
  경로, 운영 산출물, 임시 경로로 보이는 다른 작업자의 파일은 삭제하지 않는다.
