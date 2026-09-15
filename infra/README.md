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
  - 로컬 Kafka 포트를 추가하고 PostgreSQL·Redis의 공통 루프백 바인딩을 유지하는 오버레이

### Local Kafka

Windows에서 실행하는 Spring Boot가 `localhost:9092`로 Kafka에 연결할 수 있도록 로컬
오버레이는 포트 매핑을 사용하는 단일 노드 KRaft Broker를 제공합니다. 서버용
`distributed.yml`과 Compose 프로젝트명, 컨테이너 및 데이터 볼륨을 공유하지 않습니다.

`infra/.env`에 `KAFKA_CLUSTER_ID`를 설정한 뒤 로컬 Kafka를 실행합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  up -d kafka
```

업무 Topic은 자동 생성하지 않습니다. 평점 Topic은 최초 한 번 생성합니다.

```bash
docker exec feelm-kafka-local \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic feelm.rating.v1 \
  --partitions 3 \
  --replication-factor 1
```

Spring Boot local 프로파일의 기본 Kafka 주소가 `localhost:9092`이므로 별도 환경변수 없이
연결됩니다.

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

PostgreSQL은 기본 `compose/service.yml`에서 `127.0.0.1:5432`에만 바인딩합니다.
로컬에서는 `compose/local.yml`을 함께 적용해도 같은 바인딩을 유지합니다.

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

J15E106에서는 로컬 오버레이 없이 실행합니다. 기본 `service.yml`은 PostgreSQL을
서버의 `127.0.0.1:5432`에만 게시해 SSH 터널의 접속 대상으로 사용합니다.
같은 Compose 네트워크의 서비스는 기존처럼 `postgres:5432`로 접근합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  config

docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  up -d postgres
```

### SSH를 통한 개발 DB 접속

S15P21E106-224에서 합의한 루프백 바인딩입니다. DBeaver의 SSH 연결에는 서비스 서버
주소와 SSH 계정을, DB 연결에는 `127.0.0.1:5432`와 별도 발급된 개발 DB 계정을 사용합니다.
[접속 안내](https://www.notion.so/3d2a10dfbffa81198d9fe9093bc6773b)를 참고합니다.
SSH 접속이 가능한 서버 사용자도 해당 포트에 접근할 수 있으므로 DB 계정 권한으로
조회·변경 범위를 제한합니다. 운영 전환 시 개발 계정과 포트 유지 여부를 재검토합니다.

기존 컨테이너에는 설정 파일 반영만으로 포트가 추가되지 않습니다. 병합 후 서버에서
기존 Compose 프로젝트명·환경변수·볼륨을 유지하고 PostgreSQL만 재생성해야 합니다.
재생성 중 DB 연결은 잠시 끊깁니다. `down -v`로 데이터 볼륨을 삭제하지 않습니다.
백엔드 배포 작업은 backend만 재생성하므로 PostgreSQL 포트 설정을 자동 적용하지 않습니다.

적용 후 `docker port feelm-postgres 5432`가 `127.0.0.1:5432`인지 확인하고,
DBeaver에서 `SELECT 1;`을 실행합니다. 설정 검증과 실제 서버 적용·접속 검증을 구분합니다.
이 변경의 Push만으로 서버 적용이나 DBeaver 접속 성공을 의미하지 않습니다.

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
- 네트워크: 로컬·서비스 서버 모두 `127.0.0.1:6379`, 컨테이너 간에는 기존 `redis:6379`
- 조회 계정: [Redis 조회 접속 설정](redis/README.md). 선택 비밀번호 미설정 시 비활성화

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

J15E106에서도 마지막 결과는 `127.0.0.1:6379`여야 합니다. 같은 Compose 네트워크에서는
기존 `redis:6379`로 접근합니다. 설정 적용 중 Redis 연결은 잠시 끊깁니다.

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

### Manual distributed runtime deployment

S15P21E106-413의 Spark 정상 배포·롤백·재배포 결과와 남은 Kafka·HDFS 검증 범위는
[실환경 검증 기록](verification/S15P21E106-413.md)에 구분해 기록합니다.

GitLab CD에 연결하기 전에는 두 서버에서 명시적으로 검토한 Git checkout과 직전 정상
checkout을 함께 유지합니다. 배포 스크립트는 기본적으로 계획만 출력하며
`--confirm-deploy`, 대상 commit SHA, 복구 checkout을 모두 지정해야 컨테이너를 변경합니다.
`--components`에는 실제로 변경한 `spark`, `kafka`, `hdfs`만 쉼표로 지정합니다.

배포 전에 두 checkout의 Compose 설정을 검사하고, Spark를 포함하면 실행 중인 Application과
Driver가 없는지 확인합니다. 적용은 선택한 서비스에만 `up -d --no-deps --force-recreate`를
사용합니다. NameNode 초기화 프로필, `compose down`, `down -v` 및 Volume 삭제는 실행하지
않습니다. 실패하면 직전 checkout과 실행 중이던 Spark 이미지로 해당 노드의 서비스를
복원하고 복원 후 상태 검사까지 수행합니다.

먼저 J15E106A에서 분산 노드를 배포한 뒤 J15E106 서비스 노드를 배포합니다. 아래의 경로와
SHA 및 이미지 이름은 운영자가 검토한 실제 값으로 바꿉니다. 환경 파일은 두 checkout과
별도로 유지하는 승인된 동일 파일을 사용합니다.

```bash
# J15E106A
infra/scripts/deploy-distributed.sh \
  --components spark,kafka,hdfs \
  --source-root /srv/feelm/releases/<candidate-sha> \
  --rollback-root /srv/feelm/releases/<previous-sha> \
  --env-file /srv/feelm/runtime/infra.env \
  --expected-commit <candidate-sha> \
  --expected-rollback-commit <previous-sha> \
  --spark-image feelm-spark-ml:<candidate-tag> \
  --confirm-deploy

# J15E106
infra/scripts/deploy-service.sh \
  --components spark,hdfs \
  --source-root /srv/feelm/releases/<candidate-sha> \
  --rollback-root /srv/feelm/releases/<previous-sha> \
  --env-file /srv/feelm/runtime/infra.env \
  --expected-commit <candidate-sha> \
  --expected-rollback-commit <previous-sha> \
  --spark-image feelm-spark-ml:<candidate-tag> \
  --spark-idle-checked-on-master \
  --confirm-deploy
```

두 번째 서버 배포가 실패해 첫 번째 서버도 이전 버전으로 되돌려야 하면 source와 rollback을
서로 바꾸고 이전 SHA·Spark 이미지를 명시하여 같은 스크립트를 다시 실행합니다. 이는 설정과
이미지를 이전 release checkout으로 되돌리는 절차이며 HDFS 파일, Kafka 로그, Spark
체크포인트를 과거 상태로 되돌리지 않습니다. 롤백 자체가 실패하면 추가 재시도를 반복하지
말고 출력된 실패 노드와 컨테이너 로그를 확인해 수동 복구합니다.

배포 후 스크립트는 선택한 범위에서 다음을 확인합니다.

- 컨테이너 실행 상태와 선언된 Docker healthcheck
- Kafka Metadata Quorum 응답
- HDFS의 Live DataNode 2개
- Spark Master의 ALIVE Worker 2개 및 배포된 Spark 이미지

서비스 노드에서는 Master Web UI 포트를 열지 않습니다. 분산 노드에서 Application과 Driver가
없음을 확인한 직후 서비스 노드를 배포하고 `--spark-idle-checked-on-master`로 그 확인을
명시합니다. 서비스 노드는 새 Worker 컨테이너 로그에서 Master 등록 성공을 확인합니다.
분산 노드에서 Spark 작업 중 강제 배포가 불가피한 경우에만 팀이 중단 영향을 확인한 후
`--allow-active-spark-workloads`를 사용합니다. 두 옵션 모두 작업을 안전하게 drain하거나
체크포인트를 검증해 주지 않습니다. Kafka Topic 메시지 왕복과 Kafka→Spark→HDFS 데이터
정합성 검사는 배포마다 데이터를 생성하지 않도록 별도 통합 검증 절차로 유지합니다.

Spark는 J15E106A의 Master·Worker 2와 J15E106의 Worker 1로 구성합니다. 각 Worker는
2 Core와 4 GiB 메모리를 Spark 애플리케이션에 제공하며, 컨테이너는 두 서버 사이의 직접
통신을 위해 host network를 사용합니다.

### ML Runtime Image

세 Spark 컨테이너는 `SPARK_IMAGE`에 지정한 같은 로컬 이미지를 사용합니다. 기본값은
`feelm-spark-ml:4.1.3-numpy2.2.6`입니다. `infra/spark/Dockerfile`은 기존 Apache Spark
4.1.3 Python 이미지를 digest로 고정하고, `pipeline/spark/uv.lock`과 같은 CPython 3.10
Linux x86_64 NumPy 2.2.6 wheel을 hash 검증 후 설치합니다. Spark·Java·Scala 버전과
Master·Worker 실행 명령은 변경하지 않습니다.

2026-09-10 사전 확인에서 세 컨테이너는 같은 공식 이미지
`apache/spark:4.1.3-scala2.13-java21-python3-ubuntu`를 사용했습니다. Master와 Worker 2에는
NumPy가 없었고, Worker 1도 같은 이미지 ID를 사용하며 Python 패키지 수동 설치 흔적이 없어
동일한 상태로 판단했습니다.

이미지는 Docker Hub에 Push하지 않고 두 서버에서 각각 빌드합니다. 빌드만으로 실행 중인
컨테이너가 교체되지는 않습니다. 두 서버의 저장소가 승인된 같은 commit인지 먼저 확인하고,
J15E106A에서는 Master·Worker 2를, J15E106에서는 Worker 1을 대상으로 빌드합니다.

```bash
# 두 서버에서 각각 확인
git rev-parse HEAD

# J15E106A
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  build spark-master spark-worker-2

# J15E106
docker compose --env-file infra/.env -f infra/compose/service.yml \
  build spark-worker-1
```

각 서버에서 이미지에 NumPy 2.2.6이 있고 Spark 제출 명령을 실행할 수 있는지 검사합니다.

```bash
SPARK_IMAGE="${SPARK_IMAGE:-feelm-spark-ml:4.1.3-numpy2.2.6}"
docker image inspect "$SPARK_IMAGE" --format 'image={{.Id}} user={{.Config.User}}'
docker run --rm --entrypoint python3 "$SPARK_IMAGE" \
  -c 'import numpy; print(numpy.__version__)'
docker run --rm --entrypoint /opt/spark/bin/spark-submit "$SPARK_IMAGE" --version
```

두 서버 모두 NumPy `2.2.6`, 이미지 사용자 `spark`, Spark `4.1.3`을 출력해야 합니다. 어느
검사든 실패하면 실행 중인 Spark 컨테이너를 재생성하지 않습니다.

### ML Runtime Rollout and Rollback

적용 전 Master Web UI의 Running Applications와 Drivers가 비어 있고 팀이 합의한 중단
시간인지 확인합니다. 먼저 J15E106A의 Master·Worker 2를 교체하고, 이어서 J15E106의 Worker 1을
교체합니다. HDFS·Kafka와 서비스 컨테이너는 재생성하지 않습니다.

```bash
# J15E106A
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  up -d --no-deps --force-recreate spark-master spark-worker-2

# J15E106
docker compose --env-file infra/.env -f infra/compose/service.yml \
  up -d --no-deps --force-recreate spark-worker-1
```

롤백은 두 서버의 `SPARK_IMAGE`를 직전 정상 이미지로 함께 지정한 뒤 같은 순서로 세 Spark
컨테이너만 재생성합니다. 기존 공식 이미지는 클러스터 자체를 복구할 수 있지만 NumPy가 없으므로
ML 작업은 재개하지 않습니다. `docker compose down`, `down -v`, HDFS·Kafka 볼륨 삭제는
사용하지 않습니다.

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

ML 이미지 적용 후 J15E106A에서 전용 검사도 실행합니다. Driver와 두 Worker의 NumPy 버전,
두 executor host 사용과 소규모 ALS 학습·예측을 함께 검증합니다. 기존 검사와 같은 고정 포트를
사용하므로 동시에 실행하지 않습니다.

```bash
docker cp infra/spark/ml-cluster-check.py feelm-spark-master:/tmp/ml-cluster-check.py

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
  --conf spark.port.maxRetries=0 \
  /tmp/ml-cluster-check.py
```

`DRIVER_NUMPY_VERSION=2.2.6`, `EXECUTOR_NUMPY_VERSIONS=2.2.6`, 서로 다른 executor hostname
2개, `ALS_PREDICTION_COUNT=12`와 `E106 SPARK ML CHECK: SUCCESS`를 모두 확인합니다.

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

애플리케이션과 Consumer는 다음 환경변수 이름을 사용합니다. 백엔드 컨테이너의
`KAFKA_BOOTSTRAP_SERVERS`는 Compose가 `DISTRIBUTED_NODE_IP`에 `9092` 포트를 결합해
주입하며, 저장소에 비밀정보나 서버 주소를 커밋하지 않습니다.

| 환경변수 | 값·용도 |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Compose가 `${DISTRIBUTED_NODE_IP}:9092`로 구성해 백엔드에 주입 |
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

## Distributed Consumer Deployment

추천 Consumer는 J15E106A 분산 노드에서 `distributed.yml`의 `consumer` profile로 실행한다.
이미지는 `pipeline/consumer/Dockerfile`로 분산 노드에서 수동 빌드하며 Registry 게시와 자동
CD는 별도 업무로 다룬다. profile을 지정하지 않은 기존 HDFS·Spark·Kafka 기동에는 Consumer가
추가되지 않는다.

### Cross-node datastore boundary

Consumer는 분산 노드의 Kafka·HDFS와 서비스 노드의 PostgreSQL·Redis를 함께 사용한다.
PostgreSQL과 Redis의 기본 bind address는 계속 `127.0.0.1`이다. Consumer를 배포할 때만
서비스 노드의 `infra/.env`에서 아래 두 값을 서비스 노드 사설 IP로 바꾼다.

```dotenv
POSTGRES_BIND_ADDRESS=<SERVICE_NODE_IP>
REDIS_BIND_ADDRESS=<SERVICE_NODE_IP>
```

사설 IP bind만으로 송신 주체 제한이 완성되지는 않는다. 보안 그룹과 호스트 방화벽에서
`DISTRIBUTED_NODE_IP/32`가 서비스 노드의 `5432/tcp`, `6379/tcp`에 접근하는 트래픽만
허용해야 한다. 기존 SSH 허용 규칙과 연결을 확인하지 않은 상태에서 UFW를 활성화하거나
초기화하지 않는다. 적용 전에는 운영자가 현재 규칙을 확인하고, 허용 규칙을 먼저 추가한 뒤
다른 출발지 차단을 확인한다.

```bash
sudo ufw status numbered
sudo ufw allow from "${DISTRIBUTED_NODE_IP}" to "${SERVICE_NODE_IP}" port 5432 proto tcp
sudo ufw allow from "${DISTRIBUTED_NODE_IP}" to "${SERVICE_NODE_IP}" port 6379 proto tcp
sudo ufw status numbered
```

서비스 노드에서 설정을 검사한 뒤 PostgreSQL과 Redis만 재생성한다. 두 서비스에는 짧은 연결
중단이 발생할 수 있으므로 적용 시점을 합의하고 `down -v`를 사용하지 않는다.

```bash
docker compose --env-file infra/.env -f infra/compose/service.yml config --quiet
docker compose --env-file infra/.env -f infra/compose/service.yml \
  up -d --no-deps --force-recreate postgres redis
docker port feelm-postgres 5432
docker port feelm-redis 6379
```

두 `docker port` 결과는 `<SERVICE_NODE_IP>:5432`, `<SERVICE_NODE_IP>:6379`여야 한다.

### Consumer environment

분산 노드의 `infra/.env`에는 다음 값을 추가한다. DSN과 Redis URL의 비밀번호는 URL encoding하고
실제 값은 저장소, shell history와 로그에 남기지 않는다. 세 HDFS URI는 동일한 immutable
snapshot revision을 가리켜야 하며 `latest` 별칭을 사용하지 않는다.

```dotenv
CONSUMER_IMAGE=feelm-foldin-consumer:local
CONSUMER_KAFKA_TOPIC=feelm.rating.v1
CONSUMER_KAFKA_GROUP_ID=feelm-foldin-consumer-v1
CONSUMER_POSTGRES_DSN=postgresql://<user>:<encoded-password>@<SERVICE_NODE_IP>:5432/feelm
CONSUMER_REDIS_URL=redis://:<encoded-password>@<SERVICE_NODE_IP>:6379/0
CONSUMER_MODEL_URI=hdfs://hdfs-namenode:9000/<immutable-model-path>
CONSUMER_MAPPING_URI=hdfs://hdfs-namenode:9000/<immutable-mapping-path>
CONSUMER_METADATA_URI=hdfs://hdfs-namenode:9000/<immutable-metadata-path>
```

### Build, deploy and verify

분산 노드에서 승인된 `develop`을 받은 뒤 Compose 설정과 이미지를 검사한다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  --profile consumer config --quiet
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  --profile consumer build consumer
```

서비스 노드의 두 포트가 준비된 뒤 Consumer를 기동한다. `depends_on`은 같은 Compose의 HDFS
NameNode health와 Kafka 시작 순서만 보장하며 원격 PostgreSQL·Redis 준비 상태는 보장하지 않는다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  --profile consumer up -d consumer
docker inspect --format '{{.State.Status}} exit={{.State.ExitCode}}' feelm-foldin-consumer
docker logs --tail 100 feelm-foldin-consumer
```

정상 상태는 프로세스가 `running`이고 설정·산출물·연결 오류가 반복되지 않는 상태다. 실제
검증은 백엔드의 평가 또는 온보딩 흐름으로 `feelm.rating.v1` 이벤트를 발행한 뒤 Consumer
로그의 처리 완료와 Redis의 해당 사용자 `candidate-version`, `vector`, `candidates` 세 키
갱신을 확인한다. 이벤트 payload, DSN, Redis URL과 비밀번호를 검증 기록에 남기지 않는다.

정상 종료는 다음처럼 확인한다. Compose의 `stop_grace_period`는 30초이며 Consumer가 PID 1로
SIGTERM을 받아 Kafka poll loop와 client를 닫는다.

```bash
docker compose --env-file infra/.env -f infra/compose/distributed.yml \
  --profile consumer stop consumer
docker inspect --format '{{.State.Status}} exit={{.State.ExitCode}}' feelm-foldin-consumer
```

배포 실패 시 새 Consumer만 중단하고 기존 HDFS·Spark·Kafka 및 데이터 볼륨은 유지한다. 설정을
수정한 뒤 `config --quiet`, `up -d consumer`, 로그 확인 순서로 재시도하며 `down -v`를 사용하지
않는다. 서비스 노드의 bind address를 다시 루프백으로 복구하려면 Consumer를 먼저 중단하고
두 값을 `127.0.0.1`로 되돌린 뒤 PostgreSQL과 Redis만 재생성한다.

## Deployment

별도 스테이징 서버를 두지 않습니다. `J15E106`에서 사전 검증을 통과한 백엔드와 프론트엔드
이미지를 그대로 서비스하며, `J15E106A`는 분산 처리 전용으로 유지합니다. 인프라 설정 파일은
GitLab에서 관리하고 서버에서 직접 수정하지 않습니다. 현재 프론트엔드는 실제 API·인증을
사용하지 않는 프리뷰이며 `/app/`에서만 공개합니다.

### Prerequisites

- `develop`과 `main`은 보호 브랜치이며 직접 Push와 Force Push가 금지되어야 합니다.
- `e106-shell` Runner가 J15E106에서 Docker를 실행할 수 있어야 합니다.
- PostgreSQL과 Redis가 J15E106의 같은 Compose 네트워크에서 healthy 상태여야 합니다.
- 서비스 Nginx가 frontend 컨테이너와 같은 Compose 네트워크에 있어야 합니다.
- 배포 변수는 GitLab CI/CD Variables에 `service` 환경 범위와 Protected 속성으로 등록합니다.
- 비밀번호와 JWT 키는 Masked and hidden으로 등록하며 Pipeline 로그에 출력하지 않습니다.

백엔드가 사용하는 주요 변수는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`와
`AUTH_MOCK_ENABLED`입니다. 컨테이너에서는 `postgres:5432`와 `redis:6379`를 사용합니다.
실제 값은 저장소, 이미지와 MR에 포함하지 않습니다.

### Pipeline and Deployment

`runner-check`는 보호된 `develop`의 Push Pipeline에서 `.gitlab-ci.yml`, 공통 CI Job 설정,
GitLab Runner 설치 스크립트 또는 Runner가 의존하는 systemd·네트워크 가드 파일이 변경될
때만 자동 실행합니다. 애플리케이션 코드나 문서만 변경한 Pipeline에서는 자동 실행하지 않고
비차단 수동 Job으로 남습니다. Runner 연결이나 Docker 실행 환경을 즉시 점검해야 하는 운영자는
가장 최근의 보호된 `develop` Push Pipeline에서 `runner-check`의 실행 버튼을 누릅니다.
이 Job은 호스트·실행 계정과 `docker --version`, `docker compose version`을 확인하며, 수동
실행 실패는 기능 Pipeline을 차단하지 않습니다. 별도 정기 Scheduler는 운영하지 않습니다.

데이터 저장소 관련 파일이 보호된 `main`에 반영되면 `datastore-config-check` Job이
Compose와 셸 스크립트 문법을 검사하고, 포트를 게시하지 않은 임시 Redis에서 관리자
인증·미인증 차단·조회 전용 ACL을 확인합니다. 이 Job을 통과해야 백엔드 이미지를 빌드하고
배포할 수 있습니다. 검사에는 고정된 가짜 인증정보만 사용하며 GitLab CI/CD Variable의 실제
Secret을 출력하거나 임시 Redis에 전달하지 않습니다. MR 코드를 서비스 호스트에서 실행하지
않도록 같은 검사는 MR 제출 전에 로컬에서 `infra/scripts/verify-datastore-config.sh`로 실행합니다.

develop 대상 Merge Request Pipeline은 백엔드 테스트와 Spotless 검사를 실행합니다.
develop 병합 결과에서는 서비스 이미지 빌드와 배포를 실행하지 않습니다. 검증된 develop을
main에 반영하면 보호된 main Push Pipeline의 `backend-test`를 통과한 뒤 `backend-image`
Job이 다음 형식의 변경 불가능한 이미지 태그를 Docker Hub에 Push합니다.

```text
<DOCKERHUB_NAMESPACE>/feelm-backend:<CI_COMMIT_SHA>
```

프론트엔드 변경이 포함된 보호된 `main` Push에서는 `frontend-test` 통과 후 `frontend-image`가
다음 불변 태그를 Push합니다.

```text
<DOCKERHUB_NAMESPACE>/feelm-frontend:<CI_COMMIT_SHA>
```

Docker Hub 로그인에는 GitLab CI/CD Variables의 `DOCKERHUB_USERNAME`과
`DOCKERHUB_TOKEN`을 사용합니다. 저장소 경로는 `DOCKERHUB_NAMESPACE`로 구성하며 토큰은
Masked로 관리하고 로그에 출력하지 않습니다.

이미지 빌드가 성공하면 보호된 `main`의 Push Pipeline에서 `deploy-backend` 또는
`deploy-frontend` Job이 자동 실행됩니다. MR, API 및 수동 Web Pipeline은 서비스 배포를
실행하지 않습니다. 배포 직전에
원격 `main`의 HEAD가 Pipeline의 commit SHA와 같은지 확인하므로, 재시도되거나 대기하던
과거 Pipeline이 최신 배포를 덮어쓰지 않습니다. 배포와 의존성 캐시 작업은 같은
`resource_group`으로 직렬화합니다.

새 commit이 같은 브랜치에 반영되면 이전 테스트와 이미지 빌드는 자동 취소할 수 있지만,
배포와 캐시 승격·롤백은 중간 상태 손상을 막기 위해 취소하지 않습니다. `main` 배포 전에는
main의 보호 브랜치 상태, `e106-shell` Runner의 Protected 설정과 `service` 환경 범위의
Protected CI/CD Variable을 확인합니다. 이후 추가하는 서비스 자동 배포도 보호된 main Push만
배포 기준으로 사용하며 develop, MR, API와 Web Pipeline에 배포 Job을 열지 않습니다.

백엔드 배포 스크립트는 실행 중인 이미지 이름을 먼저 보관하고 후보 이미지를 기동한 뒤 최대 2분
동안 Docker healthcheck와 컨테이너 내부 Actuator 응답을 확인합니다. 이어서 PostgreSQL과
Redis의 health, 루프백 포트, PostgreSQL `SELECT 1`, Redis 인증과 조회 전용 ACL을 검사한
경우에만 후보 이미지를 정상 배포로 인정합니다. 후보 이미지 Pull 실패 시 실행 중인
서비스를 변경하지 않으며, 컨테이너 교체 명령·이미지 일치·헬스체크 중 하나라도 실패하면
직전 이미지를 복구하고 복구된 이미지와 상태를 다시 검증합니다.

GitLab Scheduler 또는 Runner 시스템 장애는 최대 2회 자동 재시도합니다. Job timeout,
배포 스크립트 오류, 애플리케이션 기동 실패와 헬스체크 실패는 자동 재시도하지 않습니다.
배포 도중 강제 종료된 Job을 자동 재시도하면 실패한 후보를 이전 정상 이미지로 오인할 수
있으므로 timeout은 원인과 서버 상태를 확인한 뒤 수동 복구 또는 재시도합니다. 그 밖의
오류는 원인을 수정해 develop을 거쳐 `main`에 새로 반영하거나, 같은 commit을 다시 실행해야 할 명확한
이유가 있을 때 GitLab에서 Job을 수동 재시도합니다. 자동 재시도된 과거 commit도 배포 직전
HEAD 검사를 통과해야 합니다.

후보 배포가 실패하면 이전 이미지 복구 성공 여부와 관계없이 Pipeline은 실패 상태를
유지합니다. 운영 적용 전에 GitLab의 Pipeline 실패 알림 수신자를 확인해 담당자가 실패와
수동 복구 필요 여부를 Job 로그에서 확인할 수 있게 합니다.

첫 배포에는 되돌릴 이전 이미지가 없으므로, 첫 정상 배포를 완료해 롤백 기준 이미지를 먼저
확보합니다. 이후 배포부터 헬스체크 실패 시 직전 실행 이미지가 자동으로 복구됩니다.

프론트엔드 배포도 같은 방식으로 실행 중 이미지를 보관합니다. 후보 컨테이너가 healthy가
된 뒤 Nginx 설정 검사를 통과한 경우에만 Nginx를 재생성하고, 공개
`https://j15e106.p.ssafy.io/app/healthz` 응답까지 성공해야 배포를 완료합니다. 실패하면
직전 이미지를 복구하며 후보 배포 Job은 실패 상태를 유지합니다. 첫 배포 실패에는 복구할
이미지가 없으므로 실패한 컨테이너를 중지하고 운영자가 로그와 Nginx 상태를 확인합니다.

Nginx 컨테이너의 `Started` 상태는 HTTPS 요청 준비 완료를 보장하지 않습니다. 배포 스크립트는
Nginx 재생성 후 공개 `/app/healthz`, `/app/`, `/app/movies/1`을 최대 12회, 5초 간격으로
검사하며 롤백도 `/app/healthz`에 같은 대기 정책을 적용합니다. 각 요청은 연결과 전체 응답을
각각 5초로 제한합니다. 테스트나 장애 조사에서만
`FRONTEND_PUBLIC_HEALTH_ATTEMPTS`, `FRONTEND_PUBLIC_HEALTH_INTERVAL_SECONDS`,
`FRONTEND_PUBLIC_HEALTH_REQUEST_TIMEOUT_SECONDS`로 값을 재정의합니다. 제한된 검사가 모두
실패하면 기존 자동 롤백과 수동 복구 필요 처리로 진행하며 TLS 인증서 검증을 우회하지 않습니다.

Compose는 frontend나 Nginx만 대상으로 실행해도 전체 `service.yml`을 해석하므로 필수
`BACKEND_IMAGE` 값이 필요합니다. 프론트엔드 배포와 롤백 스크립트는 호출 환경에
`BACKEND_IMAGE`가 있으면 그 값을 사용하고, 없으면 실행 중인 `feelm-backend` 컨테이너의
이미지를 조회해 모든 Compose 호출에 전달합니다. 두 방법 모두로 백엔드 이미지를 확인할 수
없으면 frontend 이미지 Pull이나 컨테이너 교체 전에 종료합니다. 이 오류는 같은 Job을
재시도해도 해결되지 않으므로 백엔드 컨테이너 상태 또는 승인된 `BACKEND_IMAGE` 값을 먼저
복구한 뒤 재시도합니다. 이 과정은 백엔드 컨테이너를 재생성하지 않습니다.

Compose 전체를 수동으로 기동할 때는 `infra/.env`의 `FRONTEND_IMAGE`에 검증된 불변 SHA
이미지를 설정합니다. 프론트엔드 단독 자동 배포는 Job이 후보 값을 직접 주입합니다.

### Verification

J15E106에서 실행 이미지와 상태를 확인합니다.

```bash
docker inspect feelm-backend \
  --format 'image={{.Config.Image}} health={{.State.Health.Status}}'
curl --fail http://127.0.0.1:8080/actuator/health
docker inspect feelm-frontend \
  --format 'image={{.Config.Image}} health={{.State.Health.Status}}'
curl --fail https://j15e106.p.ssafy.io/app/
curl --fail https://j15e106.p.ssafy.io/app/healthz
curl --fail https://j15e106.p.ssafy.io/app/movies/1
```

PostgreSQL과 Redis가 호스트 루프백에만 게시되는지 확인합니다.

```bash
docker port feelm-postgres 5432
docker port feelm-redis 6379
```

각 결과가 `127.0.0.1:5432`, `127.0.0.1:6379`여야 합니다. Flyway 적용 상태를 확인하고 백엔드를
재배포한 뒤에도 같은 Migration이 중복되지 않았는지 확인합니다.

```bash
docker exec feelm-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"'
```

### Rollback

후보 이미지의 컨테이너 교체, 기동 또는 헬스체크가 실패하면 `deploy-backend.sh`가 직전
실행 이미지를 `rollback-backend.sh`에 전달합니다. 복구 이미지도 healthy 상태와 Actuator
응답을 통과해야 롤백 성공으로 처리하며, 후보 배포 Job은 실패 상태를 유지합니다. 롤백도
실패하면 Job 로그에 수동 복구가 필요함을 표시합니다.

승인된 환경변수를 주입할 수 있는 J15E106 셸에서 특정 정상 이미지로 직접 되돌릴 수도
있습니다.

```bash
./infra/scripts/rollback-backend.sh \
  <DOCKERHUB_NAMESPACE>/feelm-backend:<정상-CI_COMMIT_SHA>
```

롤백 후 `docker inspect`의 실행 이미지가 지정한 SHA 태그와 일치하고 상태가 healthy인지
확인합니다. 장애 로그나 캡처에는 환경변수 전체, 비밀번호, JWT 키와 내부 IP를 포함하지
않습니다.

이미지 롤백은 이미 적용된 Flyway Migration을 되돌리지 않습니다. Migration이 포함된 배포는
이전 애플리케이션 이미지와의 하위 호환성을 사전에 확인하며, DB 복구가 필요한 변경은 별도
Migration 및 운영 절차로 처리합니다.

프론트엔드를 특정 정상 이미지로 수동 복구할 때는 서비스 서버의 승인된 환경에서 실행합니다.
실행 중인 `feelm-backend`가 없으면 승인된 백엔드 이미지도 함께 지정합니다.

```bash
bash ./infra/scripts/rollback-frontend.sh \
  <DOCKERHUB_NAMESPACE>/feelm-frontend:<정상-CI_COMMIT_SHA>

BACKEND_IMAGE=<DOCKERHUB_NAMESPACE>/feelm-backend:<정상-CI_COMMIT_SHA> \
  bash ./infra/scripts/rollback-frontend.sh \
  <DOCKERHUB_NAMESPACE>/feelm-frontend:<정상-CI_COMMIT_SHA>
```

복구 스크립트는 frontend와 Nginx만 재생성하고 공개 `/app/healthz`, Docker healthcheck와
실행 이미지 일치를 확인합니다. 랜딩, `/api/`, Swagger 경로는 재기동 후 별도로 확인합니다.

## Reverse Proxy Client IP Trust (S15P21E106-326)

Nginx는 backend로 전달하는 모든 경로에서 클라이언트가 보낸 전달 헤더를 그대로 신뢰하지
않습니다. `X-Real-IP`와 `X-Forwarded-For`는 모두 Nginx가 확인한 `$remote_addr` 하나로
덮어쓰고, `X-Forwarded-Host`와 `X-Forwarded-Port`는 전달하지 않습니다.
`X-Forwarded-Proto`만 현재 TLS 연결의 `$scheme`으로 설정합니다. backend의 Tomcat은 신뢰
대역 안의 프록시가 보낸 `X-Real-IP`만 실제 클라이언트 주소로 사용합니다.

배포 전 정적 계약 검사를 실행합니다.

```bash
python3 -m unittest infra/scripts/test_swagger_access_policy.py
python3 -m unittest infra/scripts/test_verify_nginx_client_ip_trust.py
```

서비스 노드에서 Nginx와 backend가 실행 중일 때 공유 Docker 네트워크의 Nginx 주소와 대역
전체가 `server.tomcat.remoteip.internal-proxies`에 포함되는지 확인합니다. 이 검사는 Docker
조회 명령만 사용하며 컨테이너나 네트워크를 변경하지 않습니다.

```bash
python3 infra/scripts/verify-nginx-client-ip-trust.py
```

`NGINX_CLIENT_IP_TRUST: PASS`가 출력되어야 합니다. 실패하면 신뢰 목록을 넓히거나 Docker
네트워크를 즉시 재생성하지 말고, 실제 Compose 네트워크와 접근 경계를 확인해 변경 범위와
적용 순서를 먼저 합의합니다.

설정 적용 전 실행 중인 Nginx는 변경하지 않은 채 구문을 검사합니다.

```bash
docker compose --env-file infra/.env -f infra/compose/service.yml \
  run --rm --no-deps nginx nginx -t
```

적용 후에는 외부에서 위조한 `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Host` 및
`X-Forwarded-Port`를 붙여 인증 메일 API를 호출하고, backend에서 실제 접속 IP 기준의 동일한
IP 제한 버킷으로 집계되는지 확인합니다. 응답이나 로그에 내부 IP, 인증정보 또는 메일 주소를
검증 기록으로 남기지 않습니다.

## HTML Landing and HTTPS (S15P21E106-196)

J15E106 서비스 노드의 Nginx가 `https://j15e106.p.ssafy.io/`에서 Feelm 랜딩
페이지를 제공합니다. 정적 파일은 `frontend/landing/`에서 관리하며 HTML과 함께 JSON,
`posters/`, `art/`를 배포합니다. 시작하기 버튼은 `/app/` 프론트엔드 프리뷰로 이동합니다.
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

### Swagger 팀 전용 접근

공유 서버의 Swagger UI와 OpenAPI 문서는 HTTPS Nginx의 팀 공통 Basic Auth로 보호합니다.
인증 파일은 저장소에 포함하지 않고 서비스 노드의 `/etc/feelm/swagger.htpasswd`에서
관리합니다.

최초 설정 시 서비스 노드에서 다음을 실행합니다.

```bash
sudo apt-get update
sudo apt-get install -y apache2-utils
sudo install -d -m 700 /etc/feelm
sudo htpasswd -cB /etc/feelm/swagger.htpasswd e106

NGINX_GID="$(docker exec feelm-nginx id -g nginx)"
sudo chown root:"${NGINX_GID}" /etc/feelm/swagger.htpasswd
sudo chmod 640 /etc/feelm/swagger.htpasswd
```

비밀번호를 변경할 때는 `-c` 없이 실행합니다. 비밀번호를 명령행이나 저장소에 기록하지
않습니다.

```bash
sudo htpasswd -B /etc/feelm/swagger.htpasswd e106
```

배포용 GitLab CI/CD Variables에는 다음 값을 설정합니다.

```dotenv
SWAGGER_ENABLED=true
SWAGGER_HTPASSWD_PATH=/etc/feelm/swagger.htpasswd
```

`SWAGGER_HTPASSWD_PATH`를 지정하지 않은 비활성 환경은 `/dev/null`을 읽기 전용으로
마운트합니다. 따라서 인증 파일 없이 Compose를 기동할 수 있고, Swagger를 잘못 활성화해도
인증 가능한 계정이 없어 문서 경로가 닫힌 상태를 유지합니다.

Swagger 접속 경로는 다음과 같습니다.

```text
https://j15e106.p.ssafy.io/swagger-ui.html
```

일반 `/api/**` 요청에는 Basic Auth를 적용하지 않으며 기존 JWT 인증 정책을 유지합니다.
백엔드는 host port를 공개하지 않고 Nginx를 통해서만 접근합니다.

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
