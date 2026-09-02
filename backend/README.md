# Backend

FEELM의 회원, 영화, 평가, 파티, OTT 등 서비스 API를 제공하는 Spring Boot
애플리케이션입니다.

## 기술 스택

### 현재 적용

| 구분 | 기술 | 용도 |
| --- | --- | --- |
| Language | Java 21 | 백엔드 애플리케이션 개발 및 실행 |
| Framework | Spring Boot 4.1.1 | REST API 서버 구성 |
| Web | Spring Web MVC | HTTP 요청 처리 |
| Validation | Jakarta Validation | 요청 데이터 검증 |
| Monitoring | Spring Boot Actuator | 애플리케이션 상태 확인 |
| Persistence | Spring Data JPA, PostgreSQL | 서비스 데이터 저장 |
| Migration | Flyway | DB 스키마 버전 관리 |
| Build | Gradle 9.7.1 Wrapper | 빌드, 테스트 및 의존성 관리 |
| Test | JUnit 5, Spring Boot Test, Testcontainers | 단위·통합 테스트, 마이그레이션 검증 |
| Utility | Lombok | 반복 코드 축소 |
| Container | Docker, Eclipse Temurin 21 | 운영 이미지 빌드 및 실행 |

Spring Boot DevTools는 개발 환경에서만 사용하며 운영 실행 이미지에는 포함하지
않습니다.

## 패키지 구조

패키지 구조는 [백엔드 개발 컨벤션 3장](../docs/backend-convention.md)을 따릅니다.
최상위는 담당자나 화면이 아니라 **도메인 기준**으로 나누고, 도메인 패키지와
빈 패키지는 실제 코드가 생길 때 만듭니다.

```text
com.feelm.backend
├── domain            # 도메인 기준 분할 (아래 담당 표 참고)
│   ├── auth, user, onboarding, ott
│   ├── search, movie, watch, social, report, trend
│   └── taste, recommendation, party
├── global            # 여러 도메인이 공유하는 공통 코드
│   ├── config
│   ├── entity
│   ├── error
│   ├── response
│   └── security      # 토큰 발급·검증, 인증 필터, 인가·CORS 정책
└── infrastructure    # Kafka, Redis, 외부 API 등 외부 시스템 연결(필요 시 생성)
```

담당자별 도메인 매핑은 [백엔드 역할 분배](../docs/backend-role-assignment.md)를
기준으로 합니다.

| 담당 | 도메인 패키지 |
| --- | --- |
| A (계정·온보딩) | `auth`, `user`, `onboarding`, `ott` |
| B (콘텐츠·감상·소셜) | `search`, `movie`, `watch`, `social`, `report`, `trend` |
| C (취향·추천·파티) | `taste`, `recommendation`, `party` |

공통 엔티티는 필요한 감사 컬럼에 따라 `BaseTimeEntity`(createdAt) 또는
`BaseUpdatableEntity`(createdAt, updatedAt)를 선택해 상속하며, 모든 엔티티에 강제하지 않습니다.

공통 오류는 `ErrorCode`에 HTTP 상태와 사용자용 한국어 메시지를 추가하고 이름을
UPPER_SNAKE_CASE로 작성합니다. API 계약에 없는 공통 코드를 추가할 때는
`docs/api/README.md`의 API-000 오류 코드 표를 먼저 함께 갱신합니다.

## 실행 프로파일

기본 프로파일은 `local`이며, 배포 개발 환경은 `dev`를 사용합니다. JPA 스키마 자동 변경은
모든 프로파일에서 비활성화되어 있습니다.

| 환경 변수 | local 기본값 | dev |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/feelm` | 필수 |
| `DB_USERNAME` | `feelm` | 필수 |
| `DB_PASSWORD` | `feelm` | 필수 |
| `REDIS_HOST` | `localhost` | 필수 |
| `REDIS_PORT` | `6379` | `6379` |
| `REDIS_PASSWORD` | **필수(기본값 없음)** | 필수 |
| `JWT_SECRET` | **필수(기본값 없음)** | 필수 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 필수 |
| `AUTH_MOCK_ENABLED` | `true` | `true` |
| `AUTH_MOCK_USER_ID` | `1` | `1` |

변수 목록과 용도는 [`.env.example`](./.env.example)에서 관리합니다. 실제 값은 커밋하지
않는 개인 `.env`에만 둡니다([Secret 및 환경변수 관리 규칙](../docs/secret-environment-variables.md)).

환경변수 이름과 로컬 접속 예시는 `backend/.env.example`을 기준으로 합니다. local 프로파일은
실행 작업 디렉터리의 `.env`를 자동으로 읽습니다. dev 프로파일과 컨테이너 실행은 저장소 밖의
환경변수 또는 Secret을 명시적으로 주입합니다.

dev 실행 예시: `./gradlew bootRun --args='--spring.profiles.active=dev'`

## 인증

인증 방식은 [ADR-0009](../docs/adr/0009-jwt-auth-httponly-refresh-redis.md)와
[API 공통 규약](../docs/api/README.md#인증-및-권한)을 따릅니다. 구현은
`global/security`(토큰·필터·정책)와 `domain/auth`(재발급·폐기)로 나뉩니다.

### 보안 설정값

| 설정 | 기본값 | 근거 |
| --- | --- | --- |
| `feelm.jwt.access-token-ttl` | `30m` | 무상태라 취소할 수 없어 짧게 유지 |
| `feelm.jwt.refresh-token-ttl` | `14d` | Redis에서 폐기 가능하므로 길게 허용 |
| `feelm.jwt.refresh-cookie.name` | `refresh_token` | API-000 |
| `feelm.jwt.refresh-cookie.path` | `/api/v1/auth` | 다른 API 요청에는 쿠키를 싣지 않음 |
| `feelm.jwt.refresh-cookie.secure` | `true` | 평문 전송 차단. `localhost`는 브라우저가 예외로 허용 |
| `feelm.jwt.refresh-cookie.same-site` | `Lax` | 크로스 사이트 요청의 CSRF 차단 |
| `feelm.auth.allowed-origins` | 비어 있음 | 쿠키 전송에 `credentials`가 필요해 와일드카드를 쓸 수 없음 |

서명은 HS256 대칭키를 사용하며 `JWT_SECRET`은 최소 32바이트여야 합니다. 소스에 기본값을
두지 않으므로 값이 없으면 기동에 실패합니다.

Refresh Token은 사용될 때마다 회전하며, 폐기된 토큰이 다시 들어오면 탈취로 간주해 해당
사용자의 Refresh Token을 모두 폐기합니다(API-006 처리 규칙).

### 개발용 mock 인증

로그인(API-004)이 구현되기 전까지 회원 API를 호출할 수 있도록, `AUTH_MOCK_ENABLED=true`인
환경에서는 인증 없는 요청에 고정 사용자를 주입합니다. **인증을 무력화하는 임시 장치이므로
로그인이 병합되면 제거합니다.** 실제 토큰 흐름을 확인하려면 `AUTH_MOCK_ENABLED=false`로 끕니다.

### 공개 경로 추가

`SecurityConfig`는 기본 정책을 "인증 필요"로 두고, 비회원도 호출할 수 있는 **공개**·**선택**
구분의 API만 허용 목록에 등록합니다. 규칙을 빠뜨린 새 API가 조용히 열리지 않도록 한 것이므로,
도메인의 공개 API를 추가할 때는 `SecurityConfig`의 허용 경로에 함께 등록해야 합니다. 이
파일은 전원이 공유하는 공통 계약 파일이라 변경 시 `AGENTS.md` 5장에 따라 영향 범위를 알립니다.

## DB 마이그레이션

스키마는 Flyway가 관리합니다. 규칙과 정책은
[물리 스키마 규칙과 마이그레이션 정책](../docs/db-schema-convention.md)을 따릅니다.

- 위치: `src/main/resources/db/migration`
- 파일명: `V<YYYYMMDDHHmm>__<설명>.sql` — 담당 3인이 병렬로 작성하므로 순번 대신
  타임스탬프를 씁니다.
- 모든 프로파일에서 애플리케이션 기동 시 자동 적용됩니다. `ddl-auto`는 `none`을 유지하며
  스키마의 유일한 출처는 마이그레이션입니다.
- **이미 적용된 마이그레이션 파일은 수정하지 않습니다.** 체크섬 검증에 걸려 다른 사람의
  애플리케이션이 기동에 실패합니다. 잘못된 내용은 새 마이그레이션으로 고칩니다.
- 병합 순서와 버전 순서가 어긋나 로컬 기동이 실패하면 DB 볼륨을 지우고 다시 만듭니다.

Spring Boot 4의 Flyway 자동 설정은 `spring-boot-starter-flyway`가 제공하고 PostgreSQL
지원은 `flyway-database-postgresql` 모듈을 사용합니다. 마이그레이션 자체는
`V1MigrationTest`가 Testcontainers의 실제 PostgreSQL에도 적용해 검증합니다.

### 로컬 PostgreSQL 기동

독립 `docker run` 대신 인프라의 공통 Compose를 사용합니다. 순정 `postgres` 이미지에는
pgvector가 없어 확장 생성이 실패하므로 이미지나 실행 옵션을 별도로 바꾸지 않습니다.
저장소 루트에서 다음을 실행합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  config --quiet
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/local.yml \
  up -d postgres
docker ps --filter name=feelm-postgres
```

상태가 `healthy`여야 하며 로컬 포트는 `127.0.0.1:5432`에만 바인딩됩니다. `backend/.env`의
접속 정보는 `infra/.env`와 일치시킵니다.

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/feelm
DB_USERNAME=feelm
DB_PASSWORD=<infra/.env의 POSTGRES_PASSWORD와 같은 값>
```

백엔드 컨테이너가 같은 Compose 네트워크에서 실행될 때는
`jdbc:postgresql://postgres:5432/feelm`을 사용합니다.

### Flyway 기동 검증

`backend`를 작업 디렉터리로 애플리케이션을 실행합니다. 빈 DB의 최초 기동에서는 Migration이
적용되고 이후 기동에서는 `Schema "public" is up to date. No migration necessary.`가
출력되어야 합니다.

```bash
./gradlew bootRun
```

적용 이력, 확장과 공통 선행 테이블을 확인합니다.

```bash
docker exec feelm-postgres psql -U feelm -d feelm \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
docker exec feelm-postgres psql -U feelm -d feelm \
  -c "SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector', 'pg_trgm') ORDER BY extname;"
docker exec feelm-postgres psql -U feelm -d feelm \
  -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;"
```

`202609021030`이 성공 상태여야 하고 `vector`, `pg_trgm`, `users`, `movies`, `genres`,
`otts`, `people`, `popcorn_flavors`가 조회되어야 합니다. 애플리케이션 재기동 뒤 성공한
Migration 수가 증가하지 않는지도 확인합니다.

```bash
docker exec feelm-postgres psql -U feelm -d feelm \
  -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;"
```

기존 데이터를 지우지 않고 최초 적용을 다시 검증하려면 별도 DB를 사용합니다.

```bash
docker exec feelm-postgres createdb -U feelm feelm_183_verify
./gradlew bootRun \
  --args='--spring.profiles.active=local --spring.datasource.url=jdbc:postgresql://localhost:5432/feelm_183_verify'
docker exec feelm-postgres dropdb -U feelm feelm_183_verify
```

검증용 DB는 애플리케이션을 종료하고 결과 확인을 마친 뒤에만 삭제합니다.

### 연결 실패와 복구

잘못된 비밀번호 검증에서는 애플리케이션이 `password authentication failed`와 함께 기동에
실패해야 합니다. 실제 Secret 대신 명시적인 검증 문자열을 일회성 실행 인자로 사용합니다.

```bash
./gradlew bootRun \
  --args='--spring.profiles.active=local --spring.datasource.password=intentionally-wrong-password'
```

연결 실패 시 PostgreSQL 상태와 준비 상태를 먼저 확인한 뒤 `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`가 `infra/.env`와 일치하는지 확인합니다.

```bash
docker ps --filter name=feelm-postgres
docker exec feelm-postgres sh -c \
  'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker logs --tail 100 feelm-postgres
```

PostgreSQL 볼륨을 만든 뒤 `infra/.env`의 비밀번호만 변경해도 DB 내부 사용자 비밀번호는
바뀌지 않습니다. 기존 데이터가 있으면 `psql`의 `\password`로 사용자의 비밀번호를 먼저
변경한 뒤 환경 파일을 갱신합니다. `docker compose down -v`는 PostgreSQL과 Redis 볼륨을
삭제할 수 있으므로 명시적인 데이터 초기화 외에는 사용하지 않습니다.

## 로컬 실행

Java 21이 설치된 환경에서 `backend` 디렉터리를 기준으로 실행합니다.

`gradlew`는 `JAVA_HOME`의 JDK를 사용합니다. 시스템 기본 JDK가 21이 아니라면
실행 전에 `JAVA_HOME`을 JDK 21 설치 경로로 지정합니다.

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "C:\경로\jdk-21"
```

```bash
# Linux/macOS
export JAVA_HOME=/경로/jdk-21
```

### 환경변수 주입

local 프로파일은 작업 디렉터리의 `.env`를 Spring 설정 파일로 자동으로 읽습니다. IntelliJ,
STS와 Gradle 모두 실행 작업 디렉터리를 `backend`로 지정합니다. 그러면 IDE 실행 설정에
환경변수를 중복 등록할 필요가 없습니다.

다른 작업 디렉터리에서 실행해야 하면 `LOCAL_ENV_FILE`에 `.env`의 경로를 지정합니다.
`LOCAL_ENV_FILE`은 `.env` 자체를 찾기 위한 부트스트랩 값이므로 IDE 실행 설정이나 셸에서
주입합니다. dev 프로파일은 `.env`를 자동으로 읽지 않습니다.

### Windows

```powershell
.\gradlew.bat bootRun
```

### Linux/macOS

```bash
./gradlew bootRun
```

애플리케이션은 기본적으로 `8080` 포트에서 실행됩니다.

## 테스트와 빌드

### Windows

```powershell
.\gradlew.bat test
.\gradlew.bat clean bootJar
```

### Linux/macOS

```bash
./gradlew test
./gradlew clean bootJar
```

빌드 결과는 `build/libs`에 생성됩니다.

## Docker 실행

Docker 이미지는 JDK 21 빌드 단계와 JRE 21 실행 단계가 분리된 멀티 스테이지
방식으로 구성됩니다. 최종 이미지에는 빌드 도구와 개발용 의존성을 포함하지 않습니다.

```bash
docker build -t feelm-backend .
docker run --rm -p 8080:8080 --env-file .env --name feelm-backend feelm-backend
```

컨테이너도 `.env`를 자동으로 읽지 않으므로 `--env-file`로 전달합니다. 호스트의
PostgreSQL과 Redis에 붙는 경우 `.env`의 `DB_URL`과 `REDIS_HOST`는 `localhost` 대신
`host.docker.internal`을 가리켜야 합니다.

`.dockerignore`를 통해 Secret, 로컬 빌드 산출물과 IDE 설정 파일이 빌드 컨텍스트에서
제외됩니다.

## 상태 확인

애플리케이션 실행 후 Actuator 헬스 엔드포인트를 호출합니다.

```bash
curl http://localhost:8080/actuator/health
```

정상 상태에서는 다음 응답을 반환합니다.

```json
{"status":"UP"}
```
