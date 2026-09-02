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

### Local Development on WSL2

Spark, HDFS, Kafka를 로컬에서 실행하려면 Linux 환경이 필요하므로 WSL2 Ubuntu 24.04를
사용합니다. Spring Boot 백엔드와 React 프론트엔드는 Java 21과 Node가 설치된 Windows에서
그대로 개발할 수 있으며 WSL2가 필수는 아닙니다.

WSL2를 사용하는 경우 다음을 주의합니다.

- **저장소를 Linux 파일시스템에 둔다.** `/home/<user>/` 아래에 클론합니다.
  `/mnt/c/` 경로는 파일 I/O가 크게 느리고 POSIX 권한이 유지되지 않습니다.
- **같은 저장소를 Windows git과 WSL git으로 번갈아 조작하지 않는다.**
  Windows git은 `core.autocrlf=true`가 기본이라 체크아웃 시 개행문자를 CRLF로
  바꿔 씁니다. VS Code는 Remote-WSL로 접속해 WSL 안에서 git을 사용합니다.
- **`sudo git`을 사용하지 않는다.** `.git` 내부에 root 소유 파일이 생기면 이후
  일반 계정의 모든 쓰기가 `insufficient permission for adding an object to
  repository database`로 실패합니다. 이미 발생했다면 아래로 복구합니다.

```bash
sudo chown -R "$USER:$USER" <repo>/.git
```

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

- `compose/local.yml`
  - 로컬 개발에서만 필요한 포트 바인딩을 `compose/service.yml`에 추가하는 오버레이

## PostgreSQL Runtime

PostgreSQL은 서비스 영속 데이터를 저장하며 J15E106 서비스 노드에서
`pgvector/pgvector:0.8.6-pg17-bookworm` 이미지를 digest로 고정해 실행합니다. PostgreSQL
메이저 버전은 17, pgvector 버전은 0.8.6이며 데이터는 `postgres_data` Named Volume에
저장합니다. 로컬과 서비스 서버는 같은 이미지와 초기화 설정을 사용합니다.

이 구성은 PostgreSQL 런타임 제공만 담당합니다. 업무 테이블, Flyway 등 Migration,
인덱스, PostgreSQL 확장 생성과 기준정보 Seed는 백엔드 DB 스키마 업무에서 별도로
관리합니다. 이미지에는 `vector` 확장 파일이 포함되어 있으며, 후속 Flyway Migration에서
`vector`와 `pg_trgm` 확장을 `CREATE EXTENSION IF NOT EXISTS`로 생성합니다.

데이터베이스는 최초 초기화 시 `C.UTF-8` 로케일을 사용하고 서버 타임존은 UTC로
고정합니다. 로케일은 데이터 디렉터리 생성 후 변경할 수 없으므로 기존 볼륨에 설정을
소급 적용하려면 데이터를 백업한 뒤 해당 PostgreSQL 볼륨을 재생성해야 합니다.

### Environment

예시 파일을 복사해 실제 환경 파일을 준비합니다. 실제 비밀번호가 들어 있는
`infra/.env`는 Git에 추가하지 않습니다.

```bash
cp infra/.env.example infra/.env
```

```dotenv
POSTGRES_DB=feelm
POSTGRES_USER=feelm
POSTGRES_PASSWORD=<필수 비밀번호>
```

Spring Boot를 호스트에서 실행하고 로컬 PostgreSQL 컨테이너에 연결할 때는 다음 주소를
사용합니다. 백엔드 환경변수 목록은 `backend/.env.example`에서 관리합니다.

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/feelm
DB_USERNAME=feelm
DB_PASSWORD=<POSTGRES_PASSWORD와 같은 값>
```

백엔드도 PostgreSQL과 같은 Compose 네트워크의 컨테이너로 실행할 때는 호스트 이름을
Compose 서비스명인 `postgres`로 변경합니다.

```dotenv
DB_URL=jdbc:postgresql://postgres:5432/feelm
```

### Local Startup

로컬에서는 `compose/local.yml`을 함께 적용해 PostgreSQL을
`127.0.0.1:5432`에만 바인딩합니다. 외부 인터페이스에는 포트를 공개하지 않습니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  config

docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  up -d postgres
```

포트 바인딩은 다음 명령으로 확인합니다. 결과가 `127.0.0.1:5432`를 가리켜야 합니다.

```bash
docker port feelm-postgres 5432
```

### Service Server Startup

J15E106에서는 로컬 오버레이 없이 실행합니다. 기본 `service.yml`은 PostgreSQL 포트를
호스트에 게시하지 않으며, 같은 Compose 네트워크의 서비스만 `postgres:5432`로 접근합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  config

docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  up -d postgres
```

### Health and Connection Verification

컨테이너 상태와 PostgreSQL 준비 상태를 확인합니다.

```bash
docker ps --filter name=feelm-postgres

docker exec feelm-postgres sh -c \
  'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

`docker ps`에서 상태가 `healthy`이고 `pg_isready`가 `accepting connections`를 반환해야
합니다. 이어서 실제 SQL 실행을 확인합니다.

```bash
docker exec feelm-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1;"'
```

초기화 로케일과 서버 타임존을 확인합니다. `Collate`와 `Ctype`은 `C.UTF-8`, 타임존은
`UTC`여야 합니다.

```bash
docker exec feelm-postgres psql -U feelm -d feelm -c "\l feelm"
docker exec feelm-postgres psql -U feelm -d feelm -c "SHOW timezone;"
```

### Extension Availability Verification

이 인프라 업무에서는 PostgreSQL 확장을 직접 생성하지 않습니다. `vector`와 `pg_trgm`
확장의 실제 생성은 후속 Flyway Migration에서 관리합니다.

PostgreSQL 이미지에서 두 확장을 사용할 수 있는지 확인합니다.

```bash
docker exec feelm-postgres psql -U feelm -d feelm \
  -c "SELECT name, default_version, installed_version
      FROM pg_available_extensions
      WHERE name IN ('vector', 'pg_trgm')
      ORDER BY name;"
```

`vector`의 `default_version`이 0.8.6이고 `pg_trgm`도 조회되어야 합니다.
`installed_version`은 아직 확장을 생성하지 않은 환경에서는 비어 있을 수 있으며 이는
정상입니다. 확장 설치 상태의 기준은 후속 Flyway Migration입니다.

pgvector 이미지에 포함된 확장 정의를 확인합니다.

```bash
docker exec feelm-postgres \
  cat /usr/share/postgresql/17/extension/vector.control
```

`default_version = '0.8.6'`이 확인되어야 합니다. `vector.control`에는 `trusted = true`가
없으므로, 향후 애플리케이션 계정을 비슈퍼유저로 분리할 때는 확장 생성용 Migration 계정
또는 초기화 절차를 별도로 설계해야 합니다.

### Persistence Verification

검증용 테이블에 값을 저장하고 컨테이너를 재시작합니다.

```bash
docker exec feelm-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE TABLE IF NOT EXISTS infra_persistence_check (value text NOT NULL); \
      TRUNCATE infra_persistence_check; \
      INSERT INTO infra_persistence_check VALUES ('"'"'persisted'"'"');"'

docker restart feelm-postgres
```

재기동 후 컨테이너가 다시 `healthy`가 되면 저장한 값을 조회합니다.

```bash
docker exec feelm-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT value FROM infra_persistence_check;"'
```

`persisted`가 출력되면 Named Volume의 데이터 보존이 정상입니다. 확인이 끝나면 검증용
테이블을 제거합니다.

```bash
docker exec feelm-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "DROP TABLE infra_persistence_check;"'
```

### Stop and Recovery

일반 종료와 재기동은 Named Volume을 유지합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  stop postgres

docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  up -d postgres
```

기동 실패 시 컨테이너 상태와 로그를 먼저 확인합니다.

```bash
docker inspect feelm-postgres --format '{{json .State.Health}}'
docker logs --tail 100 feelm-postgres
```

`docker compose down -v` 또는 `docker volume rm`은 `postgres_data`를 삭제해 데이터 복구가
어려울 수 있으므로 데이터 초기화가 명시적으로 필요한 경우 외에는 사용하지 않습니다.

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

## Kafka KRaft Broker

Kafka는 J15E106A에서 ZooKeeper 없이 단일 노드 KRaft 모드로 실행합니다. 하나의 Kafka
프로세스가 Broker와 Controller 역할을 함께 수행하며, 컨테이너는 서버 간 직접 통신을 위해
host network를 사용합니다.

단일 Broker 구성이므로 토픽의 복제 계수는 `1`입니다. Kafka 프로세스나 서버 장애가 발생하면
서비스가 중단될 수 있으므로, 고가용성이 필요해지면 별도의 다중 Broker 구성을 검토합니다.

### Environment and Cluster ID

J15E106A의 `infra/.env`에 분산 처리 노드의 사설 IP와 KRaft Cluster ID를 설정합니다. 실제
환경 파일은 Git에 추가하지 않습니다.

```dotenv
DISTRIBUTED_NODE_IP=<J15E106A 사설 IP>
KAFKA_CLUSTER_ID=<KRaft Cluster ID>
```

Cluster ID는 클러스터를 최초로 구성할 때 한 번 생성합니다.

```bash
docker run --rm apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-storage.sh random-uuid
```

출력값을 `KAFKA_CLUSTER_ID`에 저장합니다. `kafka_data` 볼륨을 유지하는 동안 Cluster ID를
변경하지 않습니다. 기존 데이터와 다른 Cluster ID를 사용하면 Kafka가 기동하지 못할 수
있습니다.

### Network and Ports

아래 포트는 두 EC2 서버의 사설 IP 사이에서만 허용하고 인터넷에 공개하지 않습니다.

| 포트 | 용도 |
| --- | --- |
| `9092/tcp` | Producer, Consumer 및 관리용 Broker Listener |
| `9093/tcp` | KRaft Controller Listener |

Kafka는 `0.0.0.0:9092`에서 요청을 수신하고 클라이언트에는
`${DISTRIBUTED_NODE_IP}:9092`를 Broker 주소로 알립니다. 서비스 노드에서 해당 사설 IP와
포트로 접속할 수 있어야 합니다. Controller 포트는 클라이언트가 사용하지 않습니다.

### Startup and Status

J15E106A에서 Kafka를 시작하고 로그를 확인합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  up -d kafka
docker logs --tail 100 feelm-kafka
```

KRaft Metadata Quorum에서 현재 Controller의 상태를 확인합니다.

```bash
docker exec feelm-kafka \
  /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server localhost:9092 \
  describe --status
```

`ClusterId`, `LeaderId`와 `CurrentVoters`가 출력되고 `LeaderId`가 현재 노드 ID인 `1`인지
확인합니다.

### Topic and Message Verification

아래의 `e106-kafka-check`는 인프라 검증 전용 토픽이며 애플리케이션에서는 사용하지 않습니다.

```bash
docker exec feelm-kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --if-not-exists \
  --topic e106-kafka-check \
  --partitions 3 \
  --replication-factor 1
```

Producer에서 검증 메시지를 전송합니다.

```bash
echo "E106 Kafka OK" | docker exec -i feelm-kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic e106-kafka-check
```

Consumer에서 같은 메시지가 조회되는지 확인합니다.

```bash
docker exec feelm-kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic e106-kafka-check \
  --from-beginning \
  --timeout-ms 10000
```

J15E106 서비스 노드에서는 Kafka 클라이언트 컨테이너로 서버 간 접속을 검증합니다.

```bash
docker run --rm --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server <J15E106A 사설 IP>:9092 \
  --describe \
  --topic e106-kafka-check
```

### Persistence and Recovery Verification

검증 메시지를 전송한 뒤 Kafka 컨테이너를 재시작합니다.

```bash
echo "persistence-check" | docker exec -i feelm-kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic e106-kafka-check
docker restart feelm-kafka
```

재기동 후 토픽을 다시 조회하고, 앞의 Consumer 명령으로 `persistence-check` 메시지가 남아
있는지 확인합니다. 토픽 메타데이터와 메시지는 `kafka_data` Named Volume에 저장됩니다.

일반 재기동은 `docker restart` 또는 `docker compose down` 후 `up -d`로 수행합니다.
데이터를 보존해야 하는 환경에서는 `docker compose down -v`나 `docker volume rm`을 사용하지
않습니다. 장애 복구 후에는 Kafka 로그, Metadata Quorum, 토픽, 메시지 순서로 상태를
확인합니다.

### Application Connection Contract

애플리케이션과 Consumer는 다음 환경변수 이름을 사용합니다. 실제 값은 배포 환경에서
주입하며 저장소에 비밀정보나 서버 주소를 커밋하지 않습니다.

| 환경변수 | 값·용도 |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `<J15E106A 사설 IP>:9092` |
| `KAFKA_RATING_TOPIC` | `feelm.rating.v1` |
| `KAFKA_DISMISS_TOPIC` | `feelm.dismiss.v1` |
| `KAFKA_VIEW_TOPIC` | `feelm.view.v1` |
| `KAFKA_RECOMMENDATION_CLICK_TOPIC` | `feelm.recommendation-click.v1` |
| `KAFKA_OTT_CLICK_TOPIC` | `feelm.ott-click.v1` |

토픽 이름, 이벤트 봉투와 파티션 키 계약은 `docs/api/streaming-contract.md`를 기준으로 합니다.
추천 Fold-in Consumer는 강한 신호인 `feelm.rating.v1`을 소비하며, Consumer Group ID와
Offset 처리 정책은 Consumer 구현 업무에서 확정합니다.

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
