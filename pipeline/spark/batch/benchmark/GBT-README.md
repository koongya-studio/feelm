# 발표용 GBT 분산 학습 벤치마크

운영 v26 모델을 재학습하는 명령이 아니다. 고정된 합성 `feature_00..70`과 label로
`SparkXGBRegressor.fit()` 시간을 측정한다. 기존 ALS 벤치마크, 추천 API, 운영 모델 산출물은
읽거나 변경하지 않는다. 1대/2대의 속도 향상은 실측 전에는 알 수 없다.

## 배포 전제

- 이 변경이 `develop`에 병합된 것만으로 서버 코드는 바뀌지 않는다. 팀의 `main` 배포 절차로
  Spark 코드 이미지와 XGBoost CPU 런타임 이미지가 Master·두 Worker에 함께 반영되어야 한다.
- 세 컨테이너에서 `python3 -c 'import xgboost, pandas, pyarrow; from xgboost.spark import SparkXGBRegressor'`
  가 성공해야 한다. 코드 이미지 `.source-revision`과 런타임 digest를 기록한다.
- 다른 Spark 앱이 없는 점검 시간에 실행한다. Worker 수 변경은 Spark Worker 컨테이너만
  대상으로 하며 HDFS DataNode는 중단하지 않는다. 실제 학습 task가 각 Worker에 배정됐는지는
  Spark History Server에서 application ID로 확인한다.
- 4 GiB/2 core Worker에서는 먼저 1만 행 smoke를 실행한다. 최대 입력은 코드상 1,000만 행이지만
  이는 안전한 메모리 크기 보증이 아니다. 실패하면 작은 새 입력으로 다시 시작한다.

## 입력 한 번 준비

모든 결과는 벤치마크 전용의 **새로운** HDFS 경로에 둔다. 경로가 이미 있으면 덮어쓰지 않는다.
두 Worker가 모두 살아 있는 상태에서 먼저 입력을 준비한다.

```bash
BENCH_ROOT='hdfs://hdfs-namenode:8020/verification/S15P21E106-847/<고유-실행-ID>'
BENCH_INPUT="$BENCH_ROOT/input-10k"
BENCH_JOB='/opt/feelm/pipeline/spark/jobs/batch/gbt_benchmark.py'

docker exec feelm-spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 --deploy-mode client \
  --total-executor-cores 4 --executor-cores 2 --executor-memory 2g \
  --conf spark.hadoop.dfs.replication=2 \
  --conf spark.driver.host=spark-master --conf spark.driver.bindAddress=0.0.0.0 \
  "$BENCH_JOB" prepare \
  --output-uri "$BENCH_INPUT" --input-id gbt-10k-seed42 \
  --rows 10000 --features 71 --partitions 8 --seed 42
```

출력 JSON의 `input_snapshot`을 보관한다. 같은 seed·행 수·feature 수로 다시 준비하면
`content_fingerprint`는 같아야 한다. `input_snapshot`은 Parquet 파일명과 바이트를 포함한
무결성 해시이므로 준비 실행마다 달라질 수 있다. 본 측정에는 smoke와 별도 입력 URI를 사용해
행 수를 늘린 뒤, **그 한 저장본을 1대·2대 실행에 공통 사용**한다. 입력 생성 시간은
학습 시간에 포함하지 않는다.

## 1대·2대 학습 실행

1대 측정에는 Spark Master `/json/`에서 Worker 1대만 ALIVE이고 활성 앱이 0인지 확인한다.
2대 측정 전에 Worker를 복구하고 둘 다 ALIVE인지 확인한다. `--worker-count`만 바꾸는 것으로
실제 Worker 수가 달라지는 것은 아니다. 각 실행은 고유한 결과 URI와 sequence를 사용한다.

```bash
BENCH_SNAPSHOT='<prepare의 input_snapshot>'
BENCH_REVISION="$(docker exec feelm-spark-master cat /opt/feelm/pipeline/spark/.source-revision)"
BENCH_WORKERS=1       # 복구 후 2
BENCH_CORES=2         # 복구 후 4
BENCH_SEQUENCE=1      # 실행마다 증가
BENCH_RESULT="$BENCH_ROOT/run-$BENCH_SEQUENCE-workers-$BENCH_WORKERS"

docker exec feelm-spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 --deploy-mode client \
  --total-executor-cores "$BENCH_CORES" --executor-cores 2 --executor-memory 2g \
  --conf spark.task.cpus=2 \
  --conf spark.hadoop.dfs.replication=2 \
  --conf spark.driver.host=spark-master --conf spark.driver.bindAddress=0.0.0.0 \
  "$BENCH_JOB" run \
  --input-root "$BENCH_INPUT" --input-snapshot "$BENCH_SNAPSHOT" \
  --result-uri "$BENCH_RESULT" --worker-count "$BENCH_WORKERS" \
  --revision "$BENCH_REVISION" --sequence "$BENCH_SEQUENCE" \
  --estimators 30 --max-depth 4 --seed 42
```

`training_seconds`만 `fit()` 시간이다. `total_seconds`에는 입력 해시·행 수 검증과 Worker
probe도 포함된다. Worker probe는 사전 확인이지 fit stage의 실제 분산 증거가 아니다.
결과의 `fit_stage_workers_verified=false`는 History Server 확인 전까지 유지된다.

```bash
docker exec feelm-spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  "$BENCH_JOB" compare \
  --single-result-uri "$BENCH_ROOT/run-1-workers-1" \
  --distributed-result-uri "$BENCH_ROOT/run-2-workers-2"
```

입력 snapshot·크기·feature 수·모델 설정·코드 revision이 다르면 비교를 거부한다.
발표 수치는 1→2→2→1처럼 순서를 바꿔 반복한 학습 시간의 중앙값을 사용하는 것이 좋다.
작은 입력에서는 분산 오버헤드 때문에 2대가 더 느릴 수 있으며, 이 결과로 운영 v26의
정확도·학습 시간이나 온라인 추천 응답 속도를 주장하지 않는다.
