# Infrastructure

E106 프로젝트의 서비스 및 분산 처리 인프라 설정을 관리합니다.

## 운영 안전성 점검 기록

2026-09-04의 용량·로그, 백업 준비, Runner 권한 및 SSH 읽기 전용 점검 결과는
[S15P21E106-221 점검 기록](verification/S15P21E106-221.md)을 참고합니다.
미확인 범위와 후속 개선 제안을 포함하며, 운영 설정 변경이나 백업 구축 완료를 의미하지 않습니다.

S15P21E106-222의 [Runner 통신 격리 파일럿 스크립트](scripts/README.md)는 별도 준비 단계이며,
운영 적용·Runner 전환 및 격리 검증 완료를 의미하지 않습니다.

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
  - 모델·Item Factor 산출과 사용자별 추천 재구축 요청

- Python Fold-in Consumer
  - Kafka의 명시적 평가 이벤트 소비
  - 사용자 ALS Vector 갱신
  - 해당 사용자의 추천 후보 Top-K 재계산
  - Re-Ranking·XAI 생성 후 최종 추천 Top-N 저장

- Spark Streaming
  - 조회, 클릭 등 실시간 행동 이벤트 처리
  - 실시간 취향 Feature 계산
  - 여러 사용자의 영화 조회를 이벤트 시간 윈도우로 집계
  - 실시간 인기 영화 스냅샷 생성

- Redis
  - 추천 후보
  - 사용자 Vector
  - 실시간 Feature 저장
  - 최종 추천 Top-N·추천 근거 저장
  - 실시간 인기 영화 스냅샷 저장

- Spring Boot
  - Redis의 최종 추천 Top-N과 인기 영화 스냅샷 조회
  - 감상·관심없음 제외와 추천 세트 버퍼 저장
  - 최종 추천·급상승 영화 API 제공

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

## Redis Runtime

Redis는 Refresh Token, 추천 결과, 사용자 Vector 및 실시간 Feature를 저장하며
J15E106 서비스 노드에서 실행합니다. 로컬과 서비스 서버는 다음 이미지와 설정을 공유합니다.

```text
redis:8.2.9-alpine@sha256:30abb90e62f14b737010746def3ba99cc79fe19dcdb3d37b41f21fc62e7da19d
```

- 영속성: `redis_data:/data`, AOF `everysec`와 기본 RDB 정책
- 메모리: Redis `512mb`, 컨테이너 `768m`
- 축출 정책: 인증 데이터 보호를 위한 `noeviction`; 한도 도달 시 새 쓰기가 실패함
- 네트워크: 로컬은 `127.0.0.1:6379`, J15E106은 호스트 포트 없이 내부 `redis:6379`

Refresh Token Repository, 추천 Key와 TTL, Spark 적재 및 Redis Cluster·Sentinel
구성은 이 실행환경 업무의 범위에 포함하지 않습니다.

### Environment

실제 Redis 비밀번호는 저장소에 포함되지 않는 `infra/.env`에 저장합니다.

```dotenv
REDIS_PASSWORD=<필수 비밀번호>
```

Spring Boot를 호스트에서 실행할 때는 다음 값을 `backend/.env`에 설정합니다.

```dotenv
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=<infra/.env와 같은 값>
```

백엔드도 같은 Compose 네트워크에서 컨테이너로 실행하면 호스트 이름으로 Compose
서비스명인 `redis`를 사용합니다. dev 프로파일과 컨테이너에는 승인된 Secret을 별도로
주입하며 `.env`를 이미지나 저장소에 포함하지 않습니다.

```dotenv
REDIS_HOST=redis
REDIS_PORT=6379
```

### Startup

로컬은 `local.yml` 오버레이를 적용합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  config --quiet
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  up -d redis
docker port feelm-redis 6379
```

마지막 결과는 `127.0.0.1:6379`여야 합니다. J15E106에서는 `local.yml`을 제외합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  config --quiet
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  up -d redis
docker port feelm-redis 6379
```

J15E106의 마지막 명령은 출력이 없어야 하며 같은 Compose 네트워크에서는
`redis:6379`로 접근해야 합니다.

### Verification

상태와 인증을 확인합니다. 비밀번호는 명령 인자 대신 `REDISCLI_AUTH`로 전달합니다.

```bash
docker ps --filter name=feelm-redis
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning PING'
docker exec feelm-redis redis-cli PING
```

결과는 `healthy`, `PONG`, `NOAUTH Authentication required.`여야 합니다. 이어서 기본
Key 저장·조회·삭제를 확인합니다.

```bash
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning SET infra:check hello'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning GET infra:check'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning DEL infra:check'
```

각 결과는 순서대로 `OK`, `hello`, `(integer) 1`이어야 합니다.

실행 설정도 확인합니다.

```bash
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning CONFIG GET appendonly'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning CONFIG GET appendfsync'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning CONFIG GET maxmemory'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning CONFIG GET maxmemory-policy'
```

결과는 각각 `yes`, `everysec`, `536870912`, `noeviction`이어야 합니다. Redis 기본 RDB
저장 규칙은 별도로 덮어쓰지 않으므로 함께 유지됩니다.

### Persistence and Backend Verification

Key를 저장하고 재시작한 뒤 값이 남아 있는지 확인합니다. 이어서 Named Volume을 유지한
채 컨테이너를 재생성하고 같은 값을 다시 조회합니다.

```bash
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning SET infra:persistence persisted'
docker restart feelm-redis
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning GET infra:persistence'
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  up -d --force-recreate redis
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning GET infra:persistence'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning DEL infra:persistence'
```

두 조회 결과는 `persisted`여야 합니다. J15E106에서는 재생성 명령에서 `local.yml`을
제외합니다.

로컬 Spring Boot는 `backend`를 작업 디렉터리로 사용하면 local 프로파일에서
`backend/.env`를 자동으로 읽습니다. 애플리케이션 기동 후 Actuator 상태를 확인합니다.

```bash
cd backend
./gradlew bootRun
```

```bash
curl http://localhost:8080/actuator/health
```

응답의 `status`가 `UP`이어야 합니다. dev 프로파일 또는 백엔드 컨테이너는 `.env`를
자동으로 읽지 않습니다.

### Stop and Recovery

일반 종료와 재기동은 `redis_data` Named Volume을 유지합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  stop redis

docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  up -d redis
```

기동 또는 healthcheck 실패 시 상태, 로그, 메모리와 마운트를 확인합니다.

```bash
docker inspect feelm-redis --format '{{json .State.Health}}'
docker logs --tail 100 feelm-redis
docker stats feelm-redis --no-stream
docker inspect feelm-redis \
  --format '{{range .Mounts}}{{println .Name .Destination}}{{end}}'
```

`noeviction`에서 메모리가 가득 차면 새로운 쓰기가 실패합니다. 이때 기존 Key를 임의로
삭제하지 말고 불필요한 추천·Feature Key의 TTL과 메모리 상한을 담당자와 확인합니다.
AOF 손상이 의심되면 원본 볼륨을 삭제하지 않고 먼저 백업한 뒤 복구 절차를 결정합니다.

`docker compose down -v` 또는 `docker volume rm`은 같은 Compose 프로젝트의 Redis와
PostgreSQL 데이터를 삭제할 수 있으므로 데이터 초기화가 명시적으로 필요한 경우 외에는
사용하지 않습니다.

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
| `7079/tcp` | 검증 Driver RPC: 서비스 노드 → 분산 노드 |
| `7080/tcp` | 검증 Driver BlockManager: 서비스 노드 → 분산 노드 |
| `7081/tcp` | 검증 Executor BlockManager: 두 노드 사이 |

아래 검증 명령은 분산 노드에서 client mode로 실행하며 Driver와 BlockManager 포트를
고정합니다. 해당 포트는 필요한 두 서버 사이에서만 허용하고 인터넷에 공개하지 않습니다.
같은 고정 포트를 사용하는 검증 작업은 동시에 실행하지 않습니다.

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
  --total-executor-cores 4 \
  --executor-cores 2 \
  --executor-memory 2g \
  --conf spark.driver.host=spark-master \
  --conf spark.driver.bindAddress=0.0.0.0 \
  --conf spark.driver.port=7079 \
  --conf spark.driver.blockManager.port=7080 \
  --conf spark.blockManager.port=7081 \
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

Spark의 HDFS 읽기·쓰기와 Kafka 이벤트 연동은 S15P21E106-137에서 검증합니다.
2026-09-04의 정상 처리, 체크포인트 재개, 수동 중단·복구 결과와 미검증 범위는
[통합 파이프라인 검증 기록](verification/S15P21E106-137.md)을 참고합니다.
재사용 가능한 검증 명령과 로컬 테스트는 [Spark 검증 실행 안내](spark/README.md)를 참고합니다.
스크립트 저장본은 2026-09-04 서버에서 최초 10건 처리, 무신규 재실행 10건 유지,
신규 이벤트 재개 15건 및 새 출력·체크포인트의 실제 복제본 2개를 확인했습니다.

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

별도 스테이징 서버를 두지 않습니다. `J15E106`에서 사전 검증을 통과한 백엔드 이미지를
그대로 서비스하며, `J15E106A`는 분산 처리 전용으로 유지합니다. 인프라 설정 파일은
GitLab에서 관리하고 서버에서 직접 수정하지 않습니다.

### Prerequisites

- `develop`은 보호 브랜치이며 직접 Push와 Force Push가 금지되어야 합니다.
- `e106-shell` Runner가 J15E106에서 Docker를 실행할 수 있어야 합니다.
- PostgreSQL과 Redis가 J15E106의 같은 Compose 네트워크에서 healthy 상태여야 합니다.
- 배포 변수는 GitLab CI/CD Variables에 `service` 환경 범위와 Protected 속성으로 등록합니다.
- 비밀번호와 JWT 키는 Masked and hidden으로 등록하며 Pipeline 로그에 출력하지 않습니다.

백엔드가 사용하는 주요 변수는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`와
`AUTH_MOCK_ENABLED`입니다. 컨테이너에서는 `postgres:5432`와 `redis:6379`를 사용합니다.
실제 값은 저장소, 이미지와 MR에 포함하지 않습니다.

### Pipeline and Deployment

Merge Request Pipeline은 백엔드 테스트와 Spotless 검사를 실행합니다. `develop`에 병합되면
`backend-image` Job이 다음 형식의 변경 불가능한 이미지 태그를 Docker Hub에
Push합니다.

```text
<DOCKERHUB_NAMESPACE>/feelm-backend:<CI_COMMIT_SHA>
```

Docker Hub 로그인에는 GitLab CI/CD Variables의 `DOCKERHUB_USERNAME`과
`DOCKERHUB_TOKEN`을 사용합니다. 저장소 경로는 `DOCKERHUB_NAMESPACE`로 구성하며 토큰은
Masked로 관리하고 로그에 출력하지 않습니다.

이미지 빌드가 성공한 뒤 승인된 사용자가 수동 `deploy-backend` Job을 실행합니다. 배포
스크립트는 실행 중인 이미지 이름을 먼저 보관하고 후보 이미지를 기동한 뒤 최대 2분 동안
Docker healthcheck와 `http://127.0.0.1:8080/actuator/health`를 확인합니다. 성공한 경우에만
후보 이미지를 정상 배포로 인정합니다.

첫 배포에는 되돌릴 이전 이미지가 없으므로, 첫 정상 배포를 완료해 롤백 기준 이미지를 먼저
확보합니다. 이후 배포부터 헬스체크 실패 시 직전 실행 이미지가 자동으로 복구됩니다.

### Verification

J15E106에서 실행 이미지와 상태를 확인합니다.

```bash
docker inspect feelm-backend \
  --format 'image={{.Config.Image}} health={{.State.Health.Status}}'
curl --fail http://127.0.0.1:8080/actuator/health
```

PostgreSQL과 Redis가 호스트 외부에 게시되지 않고 Compose 내부에서만 접근되는지 확인합니다.

```bash
docker port feelm-postgres 5432
docker port feelm-redis 6379
```

두 명령 모두 공개 포트가 없다는 결과여야 합니다. Flyway 적용 상태를 확인하고 백엔드를
재배포한 뒤에도 같은 Migration이 중복되지 않았는지 확인합니다.

```bash
docker exec feelm-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"'
```

### Rollback

후보 이미지의 기동 또는 헬스체크가 실패하면 `deploy-backend.sh`가 직전 실행 이미지를
`rollback-backend.sh`에 전달합니다. 복구 이미지도 healthy 상태와 Actuator 응답을 통과해야
롤백 성공으로 처리하며, 후보 배포 Job은 실패 상태를 유지합니다.

승인된 환경변수를 주입할 수 있는 J15E106 셸에서 특정 정상 이미지로 직접 되돌릴 수도
있습니다.

```bash
./infra/scripts/rollback-backend.sh \
  <DOCKERHUB_NAMESPACE>/feelm-backend:<정상-CI_COMMIT_SHA>
```

롤백 후 `docker inspect`의 실행 이미지가 지정한 SHA 태그와 일치하고 상태가 healthy인지
확인합니다. 장애 로그나 캡처에는 환경변수 전체, 비밀번호, JWT 키와 내부 IP를 포함하지
않습니다.

## HTML Landing and HTTPS (S15P21E106-196)

J15E106 서비스 노드의 Nginx가 `https://j15e106.p.ssafy.io/`에서 Feelm 랜딩
페이지를 제공합니다. 정적 파일은 `frontend/landing/`에서 관리하며 HTML과 함께 JSON,
`posters/`, `art/`를 배포합니다. React 및 백엔드 기능 연동은 포함하지 않습니다.
일반 HTTP 요청은 같은 경로의 HTTPS 주소로 301 전환하고,
`/.well-known/acme-challenge/`는 인증서 갱신을 위해 HTTP로 제공합니다.

랜딩 디렉터리를 `infra/landing/`에서 `frontend/landing/`으로 옮긴 버전을 적용할 때는
Compose의 새 bind mount 경로를 반영하기 위해 Nginx 컨테이너를 재생성해야 합니다.
코드를 받기만 하거나 Nginx reload만 실행해서는 기존 컨테이너의 연결 경로가 바뀌지 않습니다.
아래 Deploy and validate 절차로 설정을 검사한 뒤 Nginx만 재생성합니다.

### Server prerequisites and certificate storage

- 도메인 DNS가 서비스 노드를 가리키고 외부 80·443 포트가 열려 있어야 합니다.
- Ubuntu의 Certbot과 `certbot.timer`, Docker Compose를 사용합니다.
- `/var/www/certbot`은 인증용 webroot이며 Nginx에 읽기 전용으로 연결합니다.
- `/etc/letsencrypt` 전체를 읽기 전용으로 연결합니다. `live/` 인증서의 심볼릭 링크가
  참조하는 `archive/`도 필요하므로 `live/`만 복사하지 않습니다.
- 인증서 개인키·계정 키는 서버에서 root 권한으로 관리하고 Git·이미지·로그에 넣지 않습니다.
  개인키 접근 권한을 완화하지 않으며, 백업이 필요하면 접근 제한된 암호화 저장소를 사용합니다.
  인증서 자동 갱신과 타이머 로그를 점검하고 백업 접근 권한·복구 방법은 운영자가 관리합니다.

```bash
sudo install -d -m 755 /var/www/certbot
sudo install -d -m 700 /etc/letsencrypt
sudo certbot certificates
```

최초 인증서 발급은 ACME 경로를 제공하는 HTTP Nginx가 먼저 실행되어 있어야 합니다.
현재 TLS 설정은 인증서가 없는 서버에서 기동되지 않습니다. 신규 서버는 Git에서 관리하는
HTTP 준비 설정(이 업무의 `9a8b8d4` 커밋)을 별도 배포 체크아웃으로 준비해 HTTP를 제공한 뒤,
인증서 발급 후 최신 HTTPS 설정으로 전환합니다. 실행 중인 저장소에 강제 checkout을 하지 않습니다.

```bash
sudo certbot certonly --webroot -w /var/www/certbot \
  --cert-name j15e106.p.ssafy.io -d j15e106.p.ssafy.io
```

이메일 및 약관 동의는 운영자가 직접 입력합니다. 실제 인증서 경로는
`/etc/letsencrypt/live/j15e106.p.ssafy.io/{fullchain,privkey}.pem`입니다.

### Deploy and validate

서버 저장소 루트에서 승인된 배포 브랜치·커밋을 받은 뒤 실행합니다. 기존 `infra/.env`와
배포 환경변수를 사용합니다. Compose는 Nginx만 실행할 때도 전체 설정을 해석하므로
`BACKEND_IMAGE`가 필요합니다. 기존 백엔드가 실행 중이면 다음처럼 실제 이미지 값을 사용합니다.
백엔드가 없는 신규 서버에서는 승인된 배포 이미지 값을 준비해야 합니다.

```bash
export BACKEND_IMAGE="$(docker inspect feelm-backend --format '{{.Config.Image}}')"
docker compose --env-file infra/.env -f infra/compose/service.yml config --quiet
docker compose --env-file infra/.env -f infra/compose/service.yml \
  run --rm --no-deps nginx nginx -t
```

검사가 성공한 경우에만 Nginx를 재생성합니다. 다른 서비스는 재기동하지 않으며 Nginx에는
짧은 접속 중단이 발생할 수 있습니다. 배포 전 정상 커밋을 기록하고 설정은 Git에서 관리합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/service.yml \
  up -d --no-deps --force-recreate nginx
curl -I http://j15e106.p.ssafy.io/
curl -I https://j15e106.p.ssafy.io/
curl -I https://j15e106.p.ssafy.io/movies.json
curl -I https://j15e106.p.ssafy.io/posters/01.jpg
```

HTTP는 HTTPS 주소를 가리키는 301, HTTPS 페이지·정적 자원은 200이어야 합니다.
인증서 검증을 건너뛰는 `curl -k`는 사용하지 않습니다. 브라우저에서 인증서 경고,
모바일 표시, 이미지 및 데이터 로딩도 확인합니다.

### Automatic renewal and reload

Ubuntu 패키지로 설치한 Certbot의 타이머를 사용합니다. 갱신 성공 후 Nginx가 새 인증서를
읽도록 서버에 다음 deploy hook을 설치합니다. 아래 파일은 서버 운영 설정이며 개인키가 아닙니다.

```bash
sudo install -d /etc/letsencrypt/renewal-hooks/deploy
sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-feelm-nginx.sh >/dev/null <<'EOF'
#!/bin/sh
set -eu
docker exec feelm-nginx nginx -t
docker exec feelm-nginx nginx -s reload
EOF
sudo chmod 750 /etc/letsencrypt/renewal-hooks/deploy/reload-feelm-nginx.sh
sudo systemctl enable --now certbot.timer
systemctl is-enabled certbot.timer
systemctl list-timers --all certbot.timer
sudo certbot renew --dry-run
sudo /etc/letsencrypt/renewal-hooks/deploy/reload-feelm-nginx.sh
```

`enabled`와 다음 실행 시간이 표시되어야 합니다. 타이머는 갱신 필요 여부를 주기적으로
확인하며 매 실행마다 인증서를 교체하지는 않습니다. `--dry-run`은 시험 갱신이며 기본적으로
실제 deploy hook 실행을 검증하지 않으므로 위에서는 hook도 별도로 실행합니다.
실제 예약 갱신 및 hook의 결합 동작은 이후 로그로 확인합니다.

```bash
sudo journalctl -u certbot.service --since '7 days ago' --no-pager
sudo certbot certificates
docker logs --tail 50 feelm-nginx
```

### Recovery and remaining checks

- 설정 검사 실패 시 실행 중인 Nginx를 재생성하지 않고 인증서 경로·권한 및 설정 오류를 확인합니다.
- 배포 후 실패하면 Git에 기록된 직전 정상 설정과 정적 파일을 복구용 변경으로 준비합니다.
  다른 작업자의 파일을 덮어쓰지 않고 승인된 복구 버전을 배포한 뒤 위 검사·재생성·접속 확인을 반복합니다.
- 기존 인증서·개인키와 데이터 볼륨은 유지합니다. 복구를 위해 `docker compose down -v`를 실행하지 않습니다.
- 인증서 문제로 HTTPS를 제공할 수 없을 때 HTTP 전용 전환은 암호화가 사라지는 별도 운영 결정입니다.
  자동으로 HTTP로 되돌리지 말고 인증서 복구·재발급을 우선 검토합니다.
- 재기동 검증은 운영자가 접속 중단 시간을 정해 Nginx 재시작 후 HTTPS와 정적 자원 응답을 확인합니다.
  서버 전체 재부팅 및 실제 이전 버전 복구 시험은 별도 일정으로 수행합니다.

### Verification record (2026-09-04)

아래 서버 결과는 운영자가 제공한 화면 및 응답에 근거하며 AI가 서버에 직접 접속한 결과는 아닙니다.

| 항목 | 결과 및 근거 |
| --- | --- |
| HTTP 랜딩 화면 | 운영자 정상 표시 확인 |
| Certbot 인증서 발급 | 운영자 발급 완료 확인 |
| HTTPS 랜딩 화면 | 설정 적용 후 운영자 정상 표시 확인 |
| 인증서 시험 갱신 | 화면에서 `all simulated renewals succeeded` 확인 |
| Nginx deploy hook 수동 실행 | `syntax is ok`, `test is successful`, `signal process started` 확인 |
| 자동 갱신 예약 | `certbot.timer` enabled 및 NEXT 실행 시각 확인 |
| 로컬 정적 검사 | JavaScript 문법, JSON, 포스터 참조 및 diff 공백 검사 통과 |
| Nginx 재기동 | 운영자 성공 확인. 재기동 후 `nginx -t` 성공 및 아래 HTTP 응답 기록 확인 |
| HTTPS 페이지·정적 자원 | 제공된 응답에서 `/`, `/movies.json`, `/posters/01.jpg` 모두 200 확인. Content-Type은 각각 text/html, application/json, image/jpeg |
| HTTP 리다이렉트 | `/` 요청의 301 및 `Location: https://j15e106.p.ssafy.io/` 확인 |
| 정상 설정 백업·복원 | 서버 커밋 `785211b`에서 nginx.conf 백업·복원, 설정 검사, Nginx 재생성, 파일 동일성 및 접속 확인 절차의 성공을 운영자가 확인 |
| 아직 미검증 | 모바일 화면, 실제 이전 버전 롤백·장애 자동 복구, 서버 전체 재부팅, 실제 예약 갱신과 hook 결합 실행 |

정상 설정 복원 시험은 현재 정상 nginx.conf를 저장소 밖에 백업한 뒤 동일한 설정을 복원하고,
`nginx -t` 성공 후 Nginx만 재생성하는 절차로 수행했습니다. `cmp` 및 `git diff`로 파일
동일성을 확인하고 HTTPS 200·HTTP 301·정적 자원 200을 재확인했습니다. 인증서는 유지했습니다.
이는 이전 버전 롤백이나 의도적으로 발생시킨 장애의 자동 복구 시험을 의미하지 않습니다.

MR의 서버 검증 상태는 위 근거와 동일하게 유지하며 미검증 항목을 완료로 표시하지 않습니다.
