# Kafka → Spark → HDFS 검증

## Spark ML 런타임 검증

S15P21E106-290의 `ml-cluster-check.py`는 `spark-submit` client mode Driver와 두 Worker가
모두 NumPy 2.2.6을 사용하고, 작은 고정 rating 데이터로 ALS 학습·예측이 성공하는지 확인합니다.
이미지는 저장소 루트의 `infra/spark/Dockerfile`로 두 서버에서 각각 빌드하며 Docker Hub에는
Push하지 않습니다. `requirements-ml.txt`는 `pipeline/spark/uv.lock`에서 생성한 CPU 전용
PyTorch·Transformers·psycopg 및 NumPy wheel hash를 포함합니다. Dockerfile은 PyTorch 공식 CPU
index를 추가해 GPU/CUDA wheel을 설치하지 않습니다. 빌드·적용·롤백 및 전체 제출 명령은
[인프라 운영 문서](../README.md#ml-runtime-image)를 따릅니다.

Docker가 없는 개발 환경에서도 이미지 계약과 세 Spark 역할의 공통 빌드 설정을 검사합니다.

```bash
python3 -B -m unittest infra.spark.test_spark_ml_runtime -v
python3 -B -m py_compile infra/spark/ml-cluster-check.py
```

서버 검증 전에는 Running Applications와 Drivers가 없는지 확인합니다. 검사는 7079~7081 고정
포트를 사용하므로 기존 `cluster-check.py` 또는 Kafka-HDFS 검사와 동시에 실행하지 않습니다.
성공 기준은 Driver·executor의 NumPy `2.2.6`, 서로 다른 executor hostname 2개, 유한한 ALS
prediction 12개와 마지막 줄 `E106 SPARK ML CHECK: SUCCESS`입니다.

`kafka-hdfs-check.py`는 137의 대화 명령을 재사용할 수 있도록 정리한 소규모 검증 스크립트다.
`8f840b3`의 저장본은 2026-09-04 운영자가 서버에서 최초 처리 10건, 무신규 재개 10건 유지,
신규 이벤트 재개 15건 및 실제 HDFS 복제본 2개를 검증했다.
상세 증빙과 제한은 [검증 기록](../verification/S15P21E106-137.md)을 참고한다.
Producer, 컨테이너 중단, 데이터 삭제, 권한 변경은 수행하지 않는다.

## 준비

- 팀과 실행 시간을 조율하고 Master `/json/`의 activeapps·activedrivers가 비었는지 확인한다.
- 두 Worker·DataNode와 Kafka 정상 상태, HDFS 이름 매핑·고정 포트 허용을 확인한다.
- `spark`가 `/verification/S15P21E106-137` 아래에 쓸 수 있어야 한다.
- 첫 저장본 검증은 기존 25건 토픽·체크포인트가 아닌 새 전용 토픽·경로에서 수행한다.
- Kafka 커넥터는 기존에 검증한 4.1.3으로 고정하며 Ivy 캐시는 컨테이너 재생성 시 사라질 수 있다.

## 새 시험 준비 (분산 서버의 저장소 루트)

실제 사설 주소는 CHECK_BOOTSTRAP에 입력한다. 오류가 나면 다음 단계로 넘어가지 않는다.

```bash
CHECK_RUN="script-$(date -u +%Y%m%d%H%M%S)"
CHECK_TOPIC="e106-137-check-${CHECK_RUN}"
CHECK_BASE="hdfs://hdfs-namenode:8020/verification/S15P21E106-137/${CHECK_RUN}"
CHECK_BOOTSTRAP="<분산_서버_사설_IP>:9092"

docker cp infra/spark/kafka-hdfs-check.py feelm-spark-master:/tmp/e106-137-kafka-hdfs-check.py
docker cp infra/spark/expected-initial.json feelm-spark-master:/tmp/e106-137-expected-initial.json

docker exec feelm-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --topic "$CHECK_TOPIC" \
  --partitions 2 --replication-factor 1
```

토픽 생성 성공 후 describe로 존재를 확인하고, 새 토픽의 각 파티션 마지막 오프셋이 0인지
확인한다. 토픽이 없으면 발행하지 않는다. 이전 ConsoleProducer가 재시도 중이면 해당
검증 토픽의 프로세스만 종료하고 종료 여부를 확인한 뒤 토픽을 생성한다. Broker는 종료하지 않는다.
그 후 fixture의 10건을 한 번만 발행한다. 실패 시 일부 발행됐을 수 있으므로
같은 이벤트를 무조건 재발행하지 않는다.

```bash
set -o pipefail
python3 -c 'import json; from pathlib import Path; events=json.loads(Path("infra/spark/expected-initial.json").read_text()); [print(json.dumps(event)) for event in events]' \
  | docker exec -i feelm-kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic "$CHECK_TOPIC" --producer-property acks=all
echo "발행 종료 코드: $?"
```

종료 코드 0 확인 후 같은 셸에 다음 함수를 정의한다. 고정 포트 동시 사용은 금지한다.

```bash
run_kafka_hdfs_check() {
  docker exec feelm-spark-master /opt/spark/bin/spark-submit \
    --master spark://spark-master:7077 --deploy-mode client \
    --total-executor-cores 4 --executor-cores 2 --executor-memory 2g \
    --conf spark.driver.host=spark-master \
    --conf spark.driver.bindAddress=0.0.0.0 \
    --conf spark.driver.port=7079 \
    --conf spark.driver.blockManager.port=7080 \
    --conf spark.blockManager.port=7081 \
    --conf spark.port.maxRetries=0 \
    --conf spark.jars.ivy=/tmp/e106-137-ivy \
    --repositories https://repo.maven.apache.org/maven2 \
    --packages org.apache.spark:spark-sql-kafka-0-10_2.13:4.1.3 \
    /tmp/e106-137-kafka-hdfs-check.py \
    --topic "$CHECK_TOPIC" --bootstrap-server "$CHECK_BOOTSTRAP" \
    --base "$CHECK_BASE" "$@"
}

run_kafka_hdfs_check --mode init \
  --expected-after /tmp/e106-137-expected-initial.json
```

기대 결과는 BEFORE_COUNT=0, AFTER_COUNT=10, NEW_OFFSETS=10 및 SUCCESS다.
init은 기존 기준 경로가 있으면 쓰기 전에 실패한다.

## 체크포인트 재개

같은 셸 변수·토픽·경로를 유지하고 새 이벤트 없이 다음을 실행한다.

```bash
run_kafka_hdfs_check --mode resume \
  --expected-before /tmp/e106-137-expected-initial.json \
  --expected-after /tmp/e106-137-expected-initial.json
```

기대 결과는 10 → 10, NEW_OFFSETS=0이다. resume은 기존 출력·sink 메타데이터와 체크포인트
metadata·commits를 요구한다. payload 전체와 Kafka 고유 오프셋, 이전 행 보존을 확인한다.

신규 이벤트 재개는 별도 JSON 파일에 기존+신규 전체 기대 이벤트를 작성해 컨테이너에 복사한 뒤,
이전 배열을 expected-before, 확장 배열을 expected-after로 지정한다. 배열에는 고유 문자열
event_id와 정수 value만 포함한다. 스크립트 자체는 이벤트를 발행하지 않는다.
기존 25건 경로에 initial 10건 fixture를 적용하지 않는다.

## 새 스트리밍 출력의 복제 수 확인

코드는 `spark.hadoop.dfs.replication=2`를 지정한다. REQUESTED_REPLICATION=2는 요청값이며
실제 복제 수가 아니다. 아래로 새 파일의 목표 replication=2, 블록별 Live_repl=2,
복제 부족·누락·손상 0 및 HEALTHY를 확인한다.

```bash
docker exec feelm-hdfs-namenode hdfs fsck "$CHECK_BASE" -files -blocks -locations
```

새 시험에서도 초기 처리·무신규 재개·신규 이벤트 재개·실제 복제 수를 확인한 뒤 결과를 기록한다.
기존 테스트 파일의 복제 수를 바꾸거나 공유 런타임 설정을 수정하지 않는다.

## 안전과 제한

- 최대 기대 이벤트는 1,000개, 결과 검증은 최대 1,001행을 가져와 초과 시 실패한다. Kafka 쓰기량을
  제한하는 장치는 아니므로 전용 소규모 토픽만 사용한다.
- timeout-seconds 기본 120초는 쿼리 종료 대기만 제한한다. 다운로드·기동·최종 읽기 시간은 별도다.
- 같은 체크포인트·포트의 동시 실행 잠금은 없다. 운영자가 중복 실행을 방지한다.
- 실패 후 일부 출력이 커밋될 수 있다. 체크포인트 삭제·기대값 완화·무조건 재실행하지 않는다.
- 이 스크립트는 장애를 주입하지 않으며, 정확한 실행 Worker 수 또는 자동 복구를 검증하지 않는다.

## 로컬 테스트

PySpark 설치 없이 입력·경로 보호·정합성 로직을 검사한다. 서버 통합 검증을 대신하지 않는다.

```bash
python3 -B -m unittest discover -s infra/spark -p 'test_kafka_hdfs_check.py' -v
python3 -B infra/spark/kafka-hdfs-check.py --help
```
