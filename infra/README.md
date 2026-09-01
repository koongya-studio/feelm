# Infrastructure

E106 프로젝트의 서비스 및 분산 처리 인프라 설정을 관리합니다.

## Distributed Runtime Baseline

분산 처리 환경은 아래 버전과 실행 방식을 공통 기준으로 사용합니다. 구성요소의 버전을
변경할 때는 호환성을 검증하고 이 표를 먼저 갱신합니다.

| 구성요소 | 확정 버전·방식 |
| --- | --- |
| Python (Recommendation Runtime) | 3.12.x |
| Python (Spark Image Runtime) | 3.10.12 |
| Spark / PySpark | 4.1.3 |
| Spark Image | `apache/spark:4.1.3-scala2.13-java21-python3-ubuntu` |
| Spark JVM | Eclipse Temurin 21.0.11 |
| Scala | 2.13.17 (Spark Runtime) |
| Hadoop / HDFS | 3.4.3 |
| Kafka | 4.3.1 |
| Kafka Mode | KRaft |
| Python Dependencies | uv |
| Local Development | WSL2 Ubuntu 24.04 |
| Runtime | Docker / Docker Compose |

포트, 네트워크, 영속 볼륨과 서비스별 실행 설정은 이 기준을 바탕으로 HDFS, Spark,
Kafka 구성 업무에서 구체화합니다.

## Server Roles

### J15E106 - Service Node

- Nginx
- Frontend
- Spring Boot
- PostgreSQL
- Redis
- Spark Worker
- HDFS DataNode

### J15E106A - Distributed Node

- Spark Master
- Spark Worker
- Kafka
- Python Fold-in Consumer
- HDFS NameNode
- HDFS DataNode

## Recommendation Processing

추천 모델은 별도의 FastAPI Serving 서버를 두지 않고 Batch 및 Consumer 기반으로 처리합니다.

- Spark Batch
  - ALS 학습
  - 콘텐츠 기반 후보 계산
  - Hybrid 추천 후보 생성
  - 사용자별 추천 후보 Top-K 사전 계산

- Python Fold-in Consumer
  - Kafka의 명시적 평가 이벤트 소비
  - 사용자 ALS Vector 갱신
  - 해당 사용자의 추천 후보 Top-K 재계산

- Spark Streaming
  - 조회, 클릭 등 실시간 행동 이벤트 처리
  - 실시간 취향 Feature 계산

- Redis
  - 추천 후보
  - 사용자 Vector
  - 실시간 Feature 저장

- Spring Boot
  - Redis의 추천 후보 조회
  - 실시간 Feature 기반 Re-Ranking
  - 최종 추천 API 제공

## Compose

- `compose/service.yml`
  - J15E106 서비스 구성

- `compose/distributed.yml`
  - J15E106A 분산 처리 구성

## Spark 2-Worker Standalone Cluster

Spark는 J15E106A의 Master·Worker 2와 J15E106의 Worker 1로 구성합니다. 각 Worker는
2 Core와 4 GiB 메모리를 Spark 애플리케이션에 제공하며, 컨테이너는 두 서버 사이의 직접
통신을 위해 host network를 사용합니다.

### Network and Ports

아래 포트는 두 서버의 사설 IP 사이에서만 허용하고 인터넷에 공개하지 않습니다. Master
Web UI를 로컬에서 확인할 때는 SSH port forwarding을 사용합니다.

| 포트 | 용도 |
| --- | --- |
| `7077/tcp` | Spark Master RPC |
| `6066/tcp` | Spark Standalone REST submission |
| `8080/tcp` | Spark Master Web UI |
| `7078/tcp` | Spark Worker RPC |
| `8081/tcp` | Spark Worker Web UI |

`spark-master`, `spark-worker-1`, `spark-worker-2`는 두 서버에서 동일한 사설 IP로 해석되어야
합니다. Compose의 `extra_hosts`가 `infra/.env`의 IP를 사용해 이 계약을 적용합니다.

### Startup Order

먼저 J15E106A에서 Master와 Worker 2를 시작합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  up -d spark-master spark-worker-2
```

Master가 시작된 뒤 J15E106에서 Worker 1을 시작합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/service.yml \
  up -d spark-worker-1
```

각 서버에서 컨테이너와 최근 로그를 확인합니다.

```bash
docker ps --filter name=feelm-spark
docker logs --tail 100 feelm-spark-master
docker logs --tail 100 feelm-spark-worker-1
docker logs --tail 100 feelm-spark-worker-2
```

Master Web UI에서 Worker 2개가 모두 `ALIVE`이고 전체 자원이 4 Core / 8 GiB로 표시되는지
확인합니다.

### Distributed PySpark Verification

J15E106A에서 검증 스크립트를 Master 컨테이너에 복사하고 client mode로 제출합니다. Executor
2개에 각각 2 Core와 2 GiB를 할당해 두 Worker가 실제 Partition을 처리하는지 확인합니다.

```bash
docker cp infra/spark/cluster-check.py \
  feelm-spark-master:/tmp/cluster-check.py

docker exec feelm-spark-master \
  /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --deploy-mode client \
  --conf spark.executor.instances=2 \
  --conf spark.executor.cores=2 \
  --conf spark.executor.memory=2g \
  /tmp/cluster-check.py
```

출력에서 다음 항목을 확인합니다.

- `spark-worker-1`, `spark-worker-2`가 Partition 결과에 모두 표시된다.
- `UNIQUE_EXECUTOR_HOSTS=2`가 표시된다.
- `E106 SPARK DISTRIBUTED CHECK: SUCCESS`가 표시된다.
- Master Web UI에서 `E106-Spark-Distributed-Check` 애플리케이션이 `FINISHED`로 표시된다.

### Worker Failure and Recovery

J15E106에서 Worker 1을 중단합니다.

```bash
docker stop feelm-spark-worker-1
```

Master Web UI에서 상태가 `1 Alive, 1 Dead`로 바뀌고 가용 자원이 2 Core / 4 GiB로 감소하는지
확인합니다. Worker 2는 `ALIVE` 상태를 유지해야 합니다.

Worker 1을 다시 시작하고 재등록 로그를 확인합니다.

```bash
docker start feelm-spark-worker-1
docker logs --tail 100 feelm-spark-worker-1
```

로그에서 `Successfully registered with master`를 확인하고 Master Web UI에서 다시
`2 Alive, 0 Dead`와 4 Core / 8 GiB가 표시되는지 확인합니다. Worker 프로세스를 다시
시작하면 새로운 Worker ID로 등록될 수 있으며 이는 정상입니다.

Spark의 HDFS 읽기·쓰기와 Kafka 이벤트 연동은 통합 파이프라인 업무에서 별도로 구성하고
검증합니다.

## HDFS 2-Node Cluster

HDFS는 J15E106A의 NameNode·DataNode 2와 J15E106의 DataNode 1로 구성합니다.
두 서버에서 저장소 루트를 현재 디렉터리로 두고 아래 명령을 실행합니다.

### Environment

각 서버에서 예시 파일을 복사하고 두 서버가 서로 접근할 수 있는 사설 IP를 입력합니다.
실제 `infra/.env`는 Git에 추가하지 않습니다.

```bash
cp infra/.env.example infra/.env
```

```dotenv
SERVICE_NODE_IP=<J15E106 사설 IP>
DISTRIBUTED_NODE_IP=<J15E106A 사설 IP>
```

Compose 실행 시 환경 파일을 명시적으로 전달합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml config
docker compose --env-file infra/.env -f infra/compose/service.yml config
```

### Network and Ports

HDFS 컨테이너는 두 서버 사이의 직접 통신을 위해 host network를 사용합니다. 아래 포트는
두 서버의 사설 IP 사이에서만 허용하고 인터넷에 공개하지 않습니다.

| 포트 | 용도 |
| --- | --- |
| `8020/tcp` | NameNode RPC |
| `9870/tcp` | NameNode Web UI 및 헬스체크 |
| `9864/tcp` | DataNode Web UI 및 헬스체크 |
| `9866/tcp` | DataNode 데이터 전송 |

`hdfs-namenode`, `hdfs-datanode-1`, `hdfs-datanode-2`는 두 서버에서 동일한 IP로
해석되어야 합니다. Compose의 `extra_hosts`가 `infra/.env`의 두 사설 IP를 사용해 이
계약을 적용합니다.

### First-time Initialization

NameNode 포맷은 **새 클러스터에서 최초 한 번만** J15E106A에서 실행합니다. 포맷 명령은
`-nonInteractive`를 사용하므로 기존 메타데이터가 있으면 덮어쓰지 않고 실패합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  --profile init run --rm hdfs-namenode-init
```

볼륨을 삭제하거나 NameNode를 다시 포맷하면 기존 HDFS 메타데이터와 파일을 복구하지 못할
수 있습니다. 운영 중에는 `docker compose down -v`를 사용하지 않습니다.

### Startup Order

먼저 J15E106A에서 NameNode와 DataNode 2를 시작합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  up -d hdfs-namenode hdfs-datanode-2
```

NameNode가 healthy 상태가 된 뒤 J15E106에서 DataNode 1을 시작합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/service.yml \
  up -d hdfs-datanode-1
```

### Verification

J15E106A의 NameNode 컨테이너에서 두 DataNode의 등록 상태를 확인합니다.

```bash
docker exec feelm-hdfs-namenode hdfs dfsadmin -report
```

기본 파일 작업을 검증합니다.

```bash
docker exec feelm-hdfs-namenode hdfs dfs -mkdir -p /smoke
docker exec feelm-hdfs-namenode bash -c \
  'echo hdfs-smoke > /tmp/hdfs-smoke.txt && hdfs dfs -put -f /tmp/hdfs-smoke.txt /smoke/input.txt'
docker exec feelm-hdfs-namenode hdfs dfs -cat /smoke/input.txt
docker exec feelm-hdfs-namenode hdfs dfs -rm -r /smoke
```

컨테이너 재기동 후 `hdfs dfsadmin -report`와 파일 조회를 다시 실행해 NameNode 메타데이터와
DataNode 블록이 Named Volume에 유지되는지 확인합니다.

### DataNode Failure and Recovery

DataNode 하나를 중단한 뒤 NameNode 보고서에서 `Dead datanodes` 상태를 확인합니다. 복제
계수가 2이므로 DataNode 하나가 중단된 동안 새 블록은 목표 복제 수를 충족하지 못할 수
있습니다.

```bash
# 중단할 DataNode가 실행 중인 서버에서 수행
docker stop feelm-hdfs-datanode-1

# J15E106A에서 상태 확인
docker exec feelm-hdfs-namenode hdfs dfsadmin -report

# 원래 서버에서 복구
docker start feelm-hdfs-datanode-1
```

복구 후 두 DataNode가 모두 `Live datanodes`로 표시되고 기존 파일을 읽을 수 있는지
확인합니다.

## Deployment

인프라 설정 파일은 GitLab에서 관리하며, 서버에서 직접 수정하지 않는 것을 원칙으로 합니다.
