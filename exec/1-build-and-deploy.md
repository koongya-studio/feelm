# 1. 빌드 및 배포 문서

GitLab에서 소스를 클론한 뒤 feelm을 빌드하고 배포하는 절차를 정리한다. 기준 커밋은
`develop` `3b820c26`(2026-09-28)이다. 세부 운영 절차는 각 구성요소 README가 기준이며,
이 문서는 처음 보는 사람이 전체 순서를 따라갈 수 있도록 요약하고 해당 문서로 연결한다.

## 1.1 시스템 구성

서버 2대(AWS EC2, Ubuntu)에 Docker Compose로 배포한다.

| 노드 | 호스트 | 실행 구성요소 | Compose 파일 |
| --- | --- | --- | --- |
| J15E106 서비스 노드 | `j15e106.p.ssafy.io` | Nginx, Frontend, Spring Boot, PostgreSQL, Redis, HDFS DataNode 1, Spark Worker 1, 모니터링 | `infra/compose/service.yml`, `infra/compose/monitoring.yml` |
| J15E106A 분산 노드 | 사설 IP (`DISTRIBUTED_NODE_IP`) | HDFS NameNode·DataNode 2, Spark Master·Worker 2, Kafka(KRaft), 추천 Consumer, 파티 Consumer | `infra/compose/distributed.yml` |

```text
브라우저 ─HTTPS─▶ Nginx(443) ─┬─ /app/   ▶ frontend 컨테이너(Nginx, 정적 SPA)
                              ├─ /api/   ▶ backend:8080 (Spring Boot, 내장 Tomcat)
                              ├─ /media/avatars/ ▶ 호스트 프로필 사진 디렉터리
                              ├─ /tmdb-images/   ▶ image.tmdb.org 고정 프록시
                              ├─ /grafana/       ▶ grafana:3000 (Basic Auth)
                              └─ /       ▶ 정적 랜딩(frontend/landing)
backend ─▶ PostgreSQL 17 + pgvector / Redis 8 / Kafka(분산 노드)
Kafka ─▶ Python Consumer(추천 후보 계산, HDFS 모델 로딩) ─▶ Redis·PostgreSQL
Spark(학습·임베딩 배치) ─▶ HDFS 모델 산출물
```

구성 근거와 포트 표는 [`infra/README.md`](../infra/README.md) 1~3장을 따른다.

## 1.2 사용 제품과 버전

### JVM·WAS·빌드 도구

| 구분 | 제품 | 버전 | 근거 |
| --- | --- | --- | --- |
| JVM | Eclipse Temurin JDK / JRE | 21 (빌드 `eclipse-temurin:21-jdk`, 실행 `eclipse-temurin:21-jre`) | `backend/Dockerfile`, `backend/build.gradle` toolchain 21 |
| 프레임워크 | Spring Boot | 4.1.1 | `backend/build.gradle` |
| WAS | 내장 Tomcat (Spring Boot 포함) | Boot 4.1.1 BOM 관리 버전, 포트 8080 | `backend/Dockerfile` `EXPOSE 8080` |
| 빌드 | Gradle Wrapper | 9.7.1 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| DB 마이그레이션 | Flyway | Boot BOM 관리 버전 | `backend/build.gradle` |
| 주요 라이브러리 | jjwt 0.13.0, springdoc-openapi 3.1.1, webpush 1.3.0, TwelveMonkeys 3.14.0, Spring Kafka | | `backend/build.gradle` |

### 웹서버·프론트엔드

| 구분 | 제품 | 버전 | 근거 |
| --- | --- | --- | --- |
| 리버스 프록시 | Nginx | `nginx:alpine` (서비스 노드 진입점) | `infra/compose/service.yml` |
| 정적 서버 | Nginx | `nginx:1.28-alpine` (frontend 이미지) | `frontend/Dockerfile` |
| 런타임 | Node.js | 22.22.0 (`engines >=22.22.0`, 빌드 이미지 `node:22.22.0-alpine`) | `frontend/package.json`, `frontend/Dockerfile` |
| 패키지 매니저 | npm | lockfileVersion 3, 설치는 `npm ci` | `frontend/package-lock.json` |
| 라이브러리 | React 19.2.8, React Router 8.3, Vite 8.2.2, TypeScript 6.0.3 | | `frontend/package-lock.json` |

### 데이터·분산 처리

| 구분 | 제품 | 버전 | 근거 |
| --- | --- | --- | --- |
| RDBMS | PostgreSQL + pgvector | PostgreSQL 17.11, pgvector 0.8.6 (`pgvector/pgvector:0.8.6-pg17-bookworm`) | `infra/compose/service.yml` |
| DB 확장 | `vector` 0.8.6, `pg_trgm` 1.6 | | 배포 DB `pg_extension` |
| 캐시 | Redis | 8.2.9 (`redis:8.2.9-alpine`) | `infra/compose/service.yml` |
| 메시지 브로커 | Apache Kafka (KRaft) | 4.3.1 | `infra/compose/distributed.yml` |
| 분산 저장소 | Apache Hadoop HDFS | 3.4.3 | `infra/compose/distributed.yml` |
| 분산 처리 | Apache Spark | 4.1.3 (Scala 2.13, Java 21 이미지), PySpark 4.1.3 | `infra/spark/Dockerfile` |
| 추천 Consumer | Python | 3.12 (`python:3.12.14-slim-bookworm`), uv 0.12.5 | `pipeline/consumer/Dockerfile` |
| Spark 작업 | Python | 3.10.12 | `pipeline/spark/.python-version` |
| ML 라이브러리 | xgboost 2.1.4, torch 2.6.0+cpu, transformers 4.48.3, numpy 2.2.6 | | `infra/spark/requirements-ml.txt` |

### 모니터링

Prometheus 3.13.0, Grafana 13.1.0, Loki 3.7.0, Alloy 1.19.2, node-exporter 1.11.1,
postgres-exporter 0.20.1, redis_exporter 1.91.1 (`infra/compose/monitoring.yml`).

### 개발 도구

| 구분 | 버전 |
| --- | --- |
| IntelliJ IDEA (백엔드) | 2023.3.8 |
| Visual Studio Code (프론트엔드·파이프라인) | 1.138.0 |
| Docker Engine | 29.7.2 (Docker Compose v2 플러그인) |
| 서버 OS | Ubuntu (AWS EC2), 로컬 분산 실습 WSL2 Ubuntu 24.04 |

## 1.3 로컬 빌드

클론 후 저장소 루트에서 시작한다.

```bash
git clone https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E106.git
cd S15P21E106
```

### 백엔드

JDK 21이 필요하다. `JAVA_HOME`을 JDK 21로 지정한다.

```bash
cd backend
cp .env.example .env          # 값 채우기는 1.4 참고. local 프로파일이 backend/.env를 자동으로 읽는다
./gradlew clean bootJar       # 결과: backend/build/libs/*.jar  (Windows: .\gradlew.bat)
./gradlew bootRun             # 기본 프로파일 local, http://localhost:8080
./gradlew test                # 테스트는 Testcontainers를 쓰므로 Docker가 떠 있어야 한다
```

로컬 실행에는 PostgreSQL(pgvector)과 Redis가 필요하다. `infra/compose/local.yml`로 띄우거나
3장 DB 덤프를 복원한 PostgreSQL을 쓴다. 기동 시 Flyway가 스키마를 자동 적용한다.
자세한 내용은 [`backend/README.md`](../backend/README.md) 실행·빌드 장을 따른다.

### 프론트엔드

```bash
cd frontend
npm ci
cp .env.example .env.local    # 선택. 소셜 로그인 client-id 등
npm run dev                   # http://localhost:5173/app/ , /api·/media는 VITE_API_PROXY_TARGET(기본 http://localhost:8080)으로 프록시
npm run build                 # tsc -b && vite build, 결과: frontend/dist
```

로컬 백엔드 CORS 기본값이 `http://localhost:5173`이므로 개발 서버는 5173 포트로 띄운다.
빌드는 OpenAPI 스냅샷과 생성 타입이 어긋나면 실패하며, 이때 `npm run api:generate`로 맞춘다.

### Docker 이미지

```bash
docker build -t feelm-backend  backend
docker build -t feelm-frontend \
  --build-arg VITE_GOOGLE_CLIENT_ID=... --build-arg VITE_KAKAO_CLIENT_ID=... \
  --build-arg VITE_NAVER_CLIENT_ID=... --build-arg VITE_OAUTH_REDIRECT_URI=https://<도메인>/app/oauth/callback \
  frontend
docker build -t feelm-foldin-consumer pipeline/consumer
```

CI는 보호된 `main` push에서 이미지를 `$DOCKERHUB_NAMESPACE/feelm-{backend,frontend,foldin-consumer,spark-ml}:<커밋 SHA>`로
Docker Hub에 푸시한다.

## 1.4 빌드·실행 환경 변수

실제 값은 저장소에 없다. 배포 값은 GitLab CI/CD Variables(`service` 환경 범위, Protected)에
있고, 로컬 값은 각 `.env.example`을 복사해 채운다. Secret 관리 원칙은
[`docs/secret-environment-variables.md`](../docs/secret-environment-variables.md)를 따른다.

### 빌드 시점 (프론트엔드, Docker `ARG`)

| 변수 | 의미 | 비고 |
| --- | --- | --- |
| `VITE_GOOGLE_CLIENT_ID` / `VITE_KAKAO_CLIENT_ID` / `VITE_NAVER_CLIENT_ID` | 소셜 로그인 client-id | 공개값, 번들에 포함. Secret은 넣지 않는다 |
| `VITE_OAUTH_REDIRECT_URI` | 소셜 로그인 콜백 | 비우면 `{origin}/app/oauth/callback` |
| `VITE_API_PROXY_TARGET` | 개발 서버 프록시 대상 | `npm run dev` 전용 |

### 실행 시점 (백엔드, `SPRING_PROFILES_ACTIVE=dev`로 배포)

`application-dev.yml`은 아래 필수 값에 기본값을 두지 않아, 빠지면 기동에 실패한다.

| 변수 | 의미 | 예시 / 기본값 | Secret |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 프로파일 | 배포 `dev`, 로컬 `local`(기본) | |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL 접속 | `jdbc:postgresql://postgres:5432/feelm` / `feelm` / - | 비밀번호 O |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_USERNAME` / `REDIS_PASSWORD` | Redis 접속 | `redis` / `6379` / `default` / - | 비밀번호 O |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 주소 | `<DISTRIBUTED_NODE_IP>:9092` | |
| `JWT_SECRET` | JWT HS256 서명 키, 32바이트 이상 | - | O |
| `REPORT_IMAGE_SIGNING_SECRET` | 리포트 이미지 URL 서명 키, JWT와 다른 값 | - | O |
| `CORS_ALLOWED_ORIGINS` | 허용 Origin. 배포는 화면과 API가 같은 출처라 CORS가 쓰이지 않는다 | `http://localhost:5173` | |
| `FRONTEND_BASE_URL` | 메일 링크 기준 주소 | `https://j15e106.p.ssafy.io` (링크는 `/app/...`를 덧붙임) | |
| `MAIL_MODE` | `smtp` 또는 `log`(로컬 전용) | dev 기본 `smtp` | |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM` | SMTP 발송 | 배포 `smtp-relay.brevo.com` / `587` / - / - / - (코드 기본값 `smtp.gmail.com`) | 비밀번호 O |
| `OAUTH_ALLOWED_REDIRECT_URIS` | 허용 소셜 콜백 목록(쉼표 구분) | `https://j15e106.p.ssafy.io/app/oauth/callback` | |
| `GOOGLE_/KAKAO_/NAVER_CLIENT_ID`, `..._CLIENT_SECRET` | 소셜 로그인 | - | Secret O |
| `GBT_XAI_ENABLED` / `GMS_KEY` / `GEMINI_REASON_MODEL` | 추천 이유 생성(LLM) | `false` / - / `gemini-2.5-flash-lite` | 키 O |
| `WEB_PUSH_MODE` / `WEB_PUSH_VAPID_PUBLIC_KEY` / `WEB_PUSH_VAPID_PRIVATE_KEY` / `WEB_PUSH_VAPID_SUBJECT` | 웹 푸시 | `disabled` 또는 `vapid` | 개인키 O |
| `AVATAR_STORAGE_PATH` / `REPORT_IMAGE_STORAGE_PATH` | 업로드 파일 저장 경로 | 컨테이너 내부 경로 | |
| `SWAGGER_ENABLED` | Swagger 노출 | `false` | |
| `MATTERMOST_*` | 사용자 의견 알림 | 선택 | 비밀번호 O |

속도 제한(`MAIL_RATE_LIMIT_*`, `MAIL_SEND_BUDGET_*`, `SIGNUP_RATE_LIMIT_*`), Kafka outbox 조정값 등
기본값이 있는 나머지 변수는 [`backend/.env.example`](../backend/.env.example)과
[`backend/README.md`](../backend/README.md) 프로파일별 변수표에 있다.

### 실행 시점 (Compose, `infra/.env`)

`infra/compose/service.yml`은 아래 변수가 없으면 해석 자체를 거부한다(`${VAR:?}`).

```text
BACKEND_IMAGE  SERVICE_NODE_IP  DISTRIBUTED_NODE_IP
POSTGRES_DB  POSTGRES_USER  POSTGRES_PASSWORD
DB_URL  DB_USERNAME  DB_PASSWORD  REDIS_PASSWORD
JWT_SECRET  REPORT_IMAGE_SIGNING_SECRET  CORS_ALLOWED_ORIGINS  FRONTEND_BASE_URL
MAIL_USERNAME  MAIL_PASSWORD  OAUTH_ALLOWED_REDIRECT_URIS
GOOGLE_CLIENT_ID  GOOGLE_CLIENT_SECRET  KAKAO_CLIENT_ID  KAKAO_CLIENT_SECRET  NAVER_CLIENT_ID  NAVER_CLIENT_SECRET
```

그 밖에 `FRONTEND_IMAGE`, `POSTGRES_BIND_ADDRESS`/`REDIS_BIND_ADDRESS`(기본 `127.0.0.1`),
`AVATAR_HOST_PATH`/`REPORT_IMAGE_HOST_PATH`, `SWAGGER_HTPASSWD_PATH`/`GRAFANA_HTPASSWD_PATH`,
모니터링·Consumer 변수는 [`infra/.env.example`](../infra/.env.example)에 설명과 함께 있다.
Consumer는 `CONSUMER_*`·`PARTY_CONSUMER_*` 변수와 HDFS 모델 URI를 쓰며
[`pipeline/consumer/.env.example`](../pipeline/consumer/.env.example)을 따른다.

### CI 전용 변수

| 변수 | 용도 |
| --- | --- |
| `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` / `DOCKERHUB_NAMESPACE` | 이미지 푸시 |
| `CI_VARIABLE_AUDIT_TOKEN` | 필수 변수 등록 여부 사전 점검(`read_api` 권한 토큰) |
| `CONSUMER_COMPOSE_ENV_FILE` | 분산 노드 Consumer env 파일 경로 |

필수 CI 변수 계약은 [`infra/config/required-ci-variables.json`](../infra/config/required-ci-variables.json),
백엔드 변수와 CI 변수의 대응은 [`backend/config/deployment-environment.properties`](../backend/config/deployment-environment.properties)에 있다.

## 1.5 배포 절차

### 자동 배포 (평소 경로)

1. 작업 브랜치 → `develop` MR: 테스트만 실행한다.
2. `develop` → `main` 통합 MR 병합으로 보호된 `main`에 push되면, 변경 경로에 따라
   `*-test` → `*-image`(Docker Hub 푸시) → `deploy-*`가 자동 실행된다.
   대상은 backend, frontend, consumer, redis, postgresql, Spark 코드, HDFS·Kafka 설정이다.
3. 배포 Job은 서비스 노드 러너(`e106-shell`)와 분산 노드 러너(`e106-distributed`)가 실행하며,
   직전에 파이프라인 SHA가 원격 `main` HEAD와 같은지 확인한다.
4. 변수만 바꿨다면 `main`에서 Web 파이프라인을 만들고 `redeploy-current-main-*` 수동 Job을 실행한다.

### 새 서버에 수동 배포

`infra/.env.example`을 `infra/.env`로 복사해 1.4의 값을 채운 뒤 아래 순서로 올린다.
명령 세부는 [`infra/README.md`](../infra/README.md) HDFS·Spark·Kafka·서비스 장을 따른다.

1. **분산 노드 HDFS**: 최초 1회만 `--profile init run --rm hdfs-namenode-init`로 NameNode를 포맷하고
   `hdfs-namenode`, `hdfs-datanode-2`를 올린다. 이어 서비스 노드에서 `hdfs-datanode-1`을 올린다.
2. **Spark**: `spark-master`, `spark-worker-2`를 먼저, 그다음 서비스 노드 `spark-worker-1`을 올린다.
3. **Kafka**: 브로커를 올린 뒤 `infra/scripts/ensure-kafka-topics.sh`로 토픽을 만든다. 자동 생성은 꺼져 있다.
   토픽은 `feelm.rating.v1`, `feelm.dismiss.v1`, `feelm.view.v1`, `feelm.recommendation-click.v1`,
   `feelm.ott-click.v1`, `feelm.candidate-updated.v1`, `feelm.party-refresh.v1`, `feelm.party-candidate-updated.v1`이다.
4. **서비스 노드 데이터 저장소**: `postgres`, `redis`를 올린다. DB를 옮길 때는 3장 덤프를 복원한다.
5. **애플리케이션**: `backend`, `frontend`, `nginx`를 올린다. `depends_on`이 healthy 상태를 기다린다.

   ```bash
   docker compose --env-file infra/.env -f infra/compose/service.yml up -d postgres redis
   docker compose --env-file infra/.env -f infra/compose/service.yml up -d backend frontend nginx
   ```

6. **Consumer**: 분산 노드에서 `--profile consumer`, `--profile party-consumer`로 올린다.
7. **모니터링**(선택): `infra/compose/monitoring.yml`.

### 배포 시 특이사항

- **pgvector 이미지 필수**: 순정 `postgres` 이미지는 Flyway의 `CREATE EXTENSION vector`에서 실패한다.
- **Flyway 자동 적용**: 백엔드 기동 시 적용된다(`validate-on-migrate=true`, `baseline-on-migrate=false`,
  `ddl-auto=none`). 이미 적용된 마이그레이션 파일은 수정하지 않는다. 이미지를 롤백해도 스키마는 되돌아가지 않는다.
- **HTTPS 최초 발급 순서**: 인증서가 없으면 현재 TLS 설정으로 Nginx가 뜨지 않는다. HTTP 전용 설정으로 먼저 띄워
  `certbot certonly --webroot -w /var/www/certbot -d <도메인>`으로 발급한 뒤 TLS 설정으로 바꾸고,
  갱신용 Nginx reload deploy hook을 설치한다([`infra/README.md`](../infra/README.md) HTTPS 장).
- **서버에서 직접 만드는 파일**: Swagger·Grafana Basic Auth용 htpasswd 파일, `/srv/feelm/data/avatars`,
  `/srv/feelm/data/report-images` 디렉터리.
- **포트 노출**: backend 8080은 호스트에 게시하지 않고 Nginx만 80/443을 연다. PostgreSQL·Redis는 기본 루프백
  바인딩이다. 분산 노드 Consumer가 접근해야 하면 `POSTGRES_BIND_ADDRESS`/`REDIS_BIND_ADDRESS`를 서비스 노드
  사설 IP로 바꾸고 DOCKER-USER 방화벽에 분산 노드 `/32` 허용 규칙을 넣는다. 5432, 6379, 7077, 8020, 9092 등은
  사설 IP 사이에서만 연다.
- **운영 중 금지**: `docker compose down -v`, NameNode 재포맷.
- **프론트엔드 경로**: 앱은 `/app/` 아래에서 동작한다(Vite `base: '/app/'`). 루트 `/`는 정적 랜딩이다.
- **소셜 콜백 일치**: 제공자 콘솔, `OAUTH_ALLOWED_REDIRECT_URIS`, 프론트엔드가 만드는 주소가 글자까지 같아야 한다.
- **임베딩 데이터**: DB 덤프에는 `movie_embeddings` 데이터가 빠져 있다(3장). 유사 영화 추천을 쓰려면
  E5 임베딩 배치를 다시 실행해 적재한다.

### 초기 데이터 적재 (DB 덤프를 쓰지 않을 때)

| 단계 | 방법 | 문서 |
| --- | --- | --- |
| 영화 메타데이터(TMDb) → PostgreSQL | `backend/scripts/run-tmdb-ingestion.sh` (`TMDB_TOKEN` 필요) | [`backend/README.md`](../backend/README.md) 초기 적재 장 |
| MovieLens 32M·TMDb 원천 → HDFS | `run-initial-data-load.sh` (경로 규칙 `/data/raw/<dataset>/<version>`) | 같은 문서 |
| OTT 카탈로그 | 운영 절차 | [`backend/scripts/OTT-CATALOG-OPERATIONS.md`](../backend/scripts/OTT-CATALOG-OPERATIONS.md) |
| E5 임베딩 생성·pgvector 적재 | Spark 배치 후 `load_movie_embeddings` dry-run → `--apply` | [`pipeline/spark/batch/embedding/README.md`](../pipeline/spark/batch/embedding/README.md) |
| 추천 모델(GBT)·Consumer 번들 | HDFS 불변 URI를 Consumer env에 지정 | [`pipeline/docs/gbt-v26/README.md`](../pipeline/docs/gbt-v26/README.md), [`pipeline/consumer/README.md`](../pipeline/consumer/README.md) |
| 팝콘 맛 기준정보 | `pipeline/consumer/tools/apply_popcorn_flavors.py` | [`pipeline/consumer/README.md`](../pipeline/consumer/README.md) |

### 배포 확인

- `https://<도메인>/app/` 접속 → 시작 화면이 뜬다.
- `docker compose -f infra/compose/service.yml ps`에서 backend·frontend·postgres·redis가 `healthy`다.
- 백엔드 헬스체크: 컨테이너 네트워크 안에서 `http://backend:8080/actuator/health`.

## 1.6 DB 접속 정보 등 주요 계정·프로퍼티 정의 파일

| 파일 | 정의 내용 |
| --- | --- |
| `backend/src/main/resources/application.yml` | 공통 설정. Flyway, Kafka, 메일, OAuth, 속도 제한, 웹 푸시, LLM 등 `${...}` 자리표시자 |
| `backend/src/main/resources/application-dev.yml` | 배포 프로파일. DB·Redis·Kafka·메일·CORS 필수 변수 |
| `backend/src/main/resources/application-local.yml` | 로컬 프로파일. `backend/.env` 자동 로드, DB 기본값 `localhost:5432/feelm`, 계정 `feelm` |
| `backend/.env.example` | 백엔드 로컬 실행 변수 전체 목록 |
| `backend/config/deployment-environment.properties` | 백엔드 필수 변수 ↔ GitLab CI 변수 대응 |
| `frontend/.env.example` | 프론트엔드 빌드 변수 |
| `infra/.env.example` | Compose 전체 변수(노드 IP, DB·Redis 계정, 이미지, 모니터링, Consumer) |
| `infra/config/required-ci-variables.json` | 필수 GitLab CI 변수와 마스킹 규칙 |
| `infra/compose/service.yml` | PostgreSQL(`POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD`, 로케일 `C.UTF-8`, UTC), Redis, 백엔드 env 주입 |
| `infra/compose/distributed.yml` | HDFS·Spark·Kafka·Consumer. Consumer의 PostgreSQL DSN·Redis URL |
| `infra/compose/local.yml` | 로컬 개발용 데이터 저장소 |
| `infra/compose/monitoring.yml` | Grafana 관리자 계정, exporter 접속 정보 |
| `infra/redis/start.sh`, `infra/redis/*.acl` | Redis 계정(`default`, `feelm_readonly`, `feelm_local_backend`)과 권한 |
| `infra/nginx/nginx.conf` | 도메인, 인증서 경로, 프록시 경로, Basic Auth 파일 경로 |
| `infra/hadoop/core-site.xml`, `infra/hadoop/hdfs-site.xml` | `fs.defaultFS=hdfs://hdfs-namenode:8020`, 복제 수 2 |
| `pipeline/consumer/.env.example` | Consumer Kafka·DB·Redis·모델 경로 |
| `backend/src/main/resources/db/migration/` | Flyway 마이그레이션(ERD 기준 스키마) |

주요 계정은 다음과 같다. 비밀번호는 모두 GitLab CI 변수 또는 서버 env 파일에만 있다.

| 계정 | 값 | 비밀번호 출처 |
| --- | --- | --- |
| PostgreSQL | DB `feelm`, 사용자 `feelm` | `POSTGRES_PASSWORD` (= `DB_PASSWORD`) |
| Redis | `default`(백엔드), `feelm_readonly`, `feelm_local_backend` | `REDIS_PASSWORD`, `REDIS_READONLY_PASSWORD`, `REDIS_LOCAL_BACKEND_PASSWORD` |
| Grafana | `admin` | `GRAFANA_ADMIN_PASSWORD` + Nginx Basic Auth |
| Swagger UI | Nginx Basic Auth | `SWAGGER_HTPASSWD_PATH` 파일 |

ERD는 [`docs/erd.md`](../docs/erd.md), API 명세는 [`docs/api/`](../docs/api/)에 있다.
