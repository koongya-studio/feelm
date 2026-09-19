# Backend

FEELM의 회원, 영화, 평가, 파티, OTT 등 서비스 API를 제공하는 Spring Boot
애플리케이션입니다.

## 수동 검증 기록

자동 테스트로 덮기 어려운 기능은 실제로 돌려 본 절차와 결과를 `verification/`에 남깁니다.
Redis 상태나 시간 창에 얽혀 있어 테스트로 고정하기 어렵거나, 배포 환경에서만 확인할 수 있는
것들입니다.

2026-09-11의 로그인 시도 제한, 계정 메일 발송 제한과 전역 상한, 사용자 차단, 토큰 수명
확인 결과는 [S15P21E106-375 검증 기록](verification/S15P21E106-375.md)을 참고합니다.
재현 절차와 배포 환경에 남은 검증을 포함하며, **배포 환경 검증 완료나 운영 적용을 뜻하지
않습니다.**

## 기술 스택

### 현재 적용

| 구분 | 기술 | 용도 |
| --- | --- | --- |
| Language | Java 21 | 백엔드 애플리케이션 개발 및 실행 |
| Framework | Spring Boot 4.1.1 | REST API 서버 구성 |
| Web | Spring Web MVC | HTTP 요청 처리 |
| Validation | Jakarta Validation | 요청 데이터 검증 |
| Monitoring | Spring Boot Actuator, Micrometer Prometheus | 상태 확인 및 애플리케이션 메트릭 제공 |
| Persistence | Spring Data JPA, PostgreSQL | 서비스 데이터 저장 |
| Migration | Flyway | DB 스키마 버전 관리 |
| Build | Gradle 9.7.1 Wrapper | 빌드, 테스트 및 의존성 관리 |
| Test | JUnit 5, Spring Boot Test, Testcontainers | 단위·통합 테스트, 마이그레이션 검증 |
| Utility | Lombok | 반복 코드 축소 |
| Container | Docker, Eclipse Temurin 21 | 운영 이미지 빌드 및 실행 |

Spring Boot DevTools는 개발 환경에서만 사용하며 운영 실행 이미지에는 포함하지
않습니다.

### 모니터링 메트릭

Actuator는 컨테이너 상태 판정을 위한 `/actuator/health`와 Prometheus 수집용
`/actuator/prometheus`만 노출합니다. Prometheus 엔드포인트는 JWT 없이 수집할 수 있지만
운영 백엔드가 내부 Compose 네트워크에만 있고 Nginx가 해당 경로를 프록시하지 않는 것을 보안
전제로 합니다. 외부 프록시 경로를 추가할 때는 메트릭 엔드포인트를 공개하지 않습니다.

전체 모니터링 스택의 기동과 검증 방법은
[`infra/monitoring/README.md`](../infra/monitoring/README.md)를 따릅니다.

## 패키지 구조

패키지 구조와 도메인별 책임은 [백엔드 개발 컨벤션 3장](../docs/backend-convention.md#3-패키지-구조)을,
담당자별 도메인은 [백엔드 역할 분배](../docs/backend-role-assignment.md)를 따릅니다.
공통 엔티티 상속과 `ErrorCode` 작성 규칙은 같은 컨벤션 문서의
[6장](../docs/backend-convention.md#6-entity-작성-규칙)과
[10장](../docs/backend-convention.md#10-api-응답과-예외-처리)에 있습니다.

## Kafka 이벤트 발행

도메인 코드는 Kafka Producer나 Topic 설정을 직접 다루지 않고 공통
`KafkaEventPublisher`를 호출합니다. 도메인 데이터 저장과 아웃박스 기록이 함께 성공하거나
함께 롤백되도록 반드시 같은 `@Transactional` 서비스 메서드 안에서 호출해야 합니다.

```java
@Transactional
public void createRating(long userId, long movieId, BigDecimal score) {
    ratingRepository.save(/* 도메인 데이터 */);

    kafkaEventPublisher.publish(
            KafkaEventType.RATING,
            userId,
            new RatingEventPayload(RatingAction.CREATE, movieId, score));
}
```

`publish()`는 Kafka에 즉시 전송하지 않습니다. 공통 봉투를 생성해 `outbox_events`에
`PENDING`으로 저장하고, 별도 Relay가 이를 조회해 계약에 정의된 Topic으로 발행합니다. Kafka
장애 시 도메인 요청에서 완료된 데이터는 유지되며 이벤트는 `PENDING` 상태로 재시도됩니다.
호출 결과로 반환되는 UUID는 해당 이벤트의 `eventId`입니다.

이벤트별 Topic·payload와 멱등 처리 기준은
[스트리밍 계약](../docs/api/streaming-contract.md#4-kafka-이벤트)을 따릅니다. 새로운 이벤트 종류나
payload를 임의로 추가하지 말고 계약과 공통 타입을 먼저 합의해 변경합니다. 아웃박스의
트랜잭션·재시도·순서 보장 범위는
[ADR-0018](../docs/adr/0018-polling-transactional-outbox.md)을 참고합니다. 로컬 Kafka 기동과
Topic 확인 방법은 [인프라 README](../infra/README.md#local-kafka)를 따릅니다.

파티 전용 Consumer의 Redis 게시 완료 알림은 `feelm.party-candidate-updated.v1`에서
`feelm-party-candidate-updated-v1` group으로 소비한다. Backend는 추천 세트를 미리 만들지 않고
`party_candidate_publications`에 파티별 마지막 source partition·offset과 결과 식별자만 기록한다.
유효 알림의 DB 트랜잭션이 커밋된 뒤 수신 Offset을 manual ACK하며, 같은 source offset의 중복과
더 오래된 offset은 상태를 바꾸지 않는다. 정확한 key·payload 계약은
[스트리밍 계약](../docs/api/streaming-contract.md#43-이벤트별-payload)을 따른다.

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
| `REDIS_USERNAME` | `default` | `default` |
| `REDIS_PASSWORD` | **필수(기본값 없음)** | 필수 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | **필수(분산 처리 노드 사설 IP:9092)** |
| `KAFKA_OUTBOX_*` | `.env.example`의 초기값 | 선택(실측 후 조정) |
| `JWT_SECRET` | **필수(기본값 없음)** | 필수 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 필수 |
| `SWAGGER_ENABLED` | `false` | `false`(팀 접근 보호 구성 후 명시적으로 활성화) |
| `REPORT_GENERATION_ENABLED` | `true` | 반기 취향 리포트 집계를 수행하는 단일 실행 노드에서만 `true` |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | 필수 |
| `MAIL_MODE` | `log`(`.env`에서 지정, 기본값 없음) | `smtp` |
| `MAIL_HOST` | `smtp.gmail.com` | 선택(비우면 `smtp.gmail.com`) |
| `MAIL_PORT` | `587` | 선택(비우면 `587`) |
| `MAIL_USERNAME` | 비어 있음 | 필수 |
| `MAIL_PASSWORD` | 비어 있음 | **필수(Secret)** |
| `MAIL_FROM` | `no-reply@feelm.local` | 선택(비우면 `MAIL_USERNAME`. Gmail은 From을 계정으로 강제) |
| `AUTH_MOCK_ENABLED` | `false` | `false` |
| `AUTH_MOCK_USER_ID` | `1` | `1` |
| `OAUTH_ALLOWED_REDIRECT_URIS` | 비어 있음(소셜 로그인 사용 불가) | 필수 |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 비어 있음 | 선택(**Secret**) |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 비어 있음 | 선택(**Secret**) |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 비어 있음 | 선택(**Secret**) |
| `RECOMMENDATION_ACCEPT_V5` | `true` | Candidate v6 재게시 확인 후 `false` |
| `GMS_KEY` | 비어 있음(결정적 문구 사용) | 선택(**Secret**). GMS Gateway를 통한 Gemini 추천 이유 생성용 |
| `GEMINI_REASON_MODEL` | `gemini-2.5-flash-lite` | 선택. 추천 이유 표현 선택 모델 |

변수 목록과 용도는 [`.env.example`](./.env.example)에서 관리합니다. 실제 값은 커밋하지
않는 개인 `.env`에만 둡니다([Secret 및 환경변수 관리 규칙](../docs/secret-environment-variables.md)).

환경변수 이름과 로컬 접속 예시는 `backend/.env.example`을 기준으로 합니다. local 프로파일은
실행 작업 디렉터리의 `.env`를 자동으로 읽습니다. dev 프로파일과 컨테이너 실행은 저장소 밖의
환경변수 또는 Secret을 명시적으로 주입합니다.

dev 실행 예시: `./gradlew bootRun --args='--spring.profiles.active=dev'`

배포 필수 변수의 이름과 GitLab CI/CD Variable 또는 검토된 dev 기본값의 대응은
[`config/deployment-environment.properties`](./config/deployment-environment.properties)에서
관리합니다. `application.yml` 또는 `application-dev.yml`에 기본값 없는 변수를 추가할 때는
`.env.example`과 이 계약도 함께 변경해야 하며, develop 대상 MR의 백엔드 테스트가 누락을
차단합니다. 실제 Secret 값은 계약 파일에 기록하지 않습니다.

## OpenAPI와 Swagger UI

OpenAPI JSON과 Swagger UI는 기본적으로 비활성화되어 있습니다. 로컬에서 확인할 때만
`.env`의 `SWAGGER_ENABLED=true`로 활성화합니다.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`

문서에는 `/api/v1/**` 제품 API만 포함되며 JWT Bearer 입력을 지원합니다. 공유 서버에서는
Nginx의 팀 전용 접근 보호와 직접 포트 우회 차단이 적용되기 전에 활성화하지 않습니다.

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

`AUTH_MOCK_ENABLED=true`인 환경에서는 인증 없는 요청에 고정 사용자를 주입합니다. 로그인 없이
회원 API를 호출해 보고 싶을 때만 켜는 개발 편의 기능이며, 인증을 무력화하므로 **기본값은
`false`입니다.** 실제 인증 흐름은 로그인(API-004)으로 발급한 Access Token으로 확인합니다.

### 소셜 로그인

소셜 로그인(API-007~009)은 프론트가 받은 인가 코드를 백엔드가 제공자와 교환하는 방식입니다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `feelm.oauth.allowed-redirect-uris` | 비어 있음 | 정확히 일치하는 `redirectUri`만 허용. 비어 있으면 모든 요청을 거절 |
| `feelm.oauth.providers.<provider>.client-id` | 비어 있음 | 값이 없는 제공자는 등록되지 않음 |
| `feelm.oauth.providers.<provider>.client-secret` | 비어 있음 | **Secret** |
| `feelm.jwt.signup-token-ttl` | `10m` | 소셜 가입을 마치기 전까지만 유효한 토큰(API-007) |

- 허용 목록에 없는 `redirectUri`는 제공자를 호출하기 전에 `400 VALIDATION_FAILED`로 거절합니다.
  목록을 열어 두면 공격자가 자신의 화면으로 인가 코드를 돌려받을 수 있습니다.
- 자격 증명이 없는 제공자는 기동을 막지 않고 호출 시 `400 UNSUPPORTED_PROVIDER`로 응답합니다.
  제공자별 앱 등록이 끝나는 시점이 달라도 서비스는 뜰 수 있어야 하기 때문입니다.
- 소셜 계정의 이메일이 기존 이메일 계정과 같으면 통합하지 않고 `409 EMAIL_DUPLICATED`로 거절합니다.
  이메일로 로그인한 뒤 계정 연결(API-009)로 같은 결과를 얻습니다.
- 제공자가 이메일을 주지 않으면 가입(API-007)은 `400 OAUTH_EMAIL_REQUIRED`로 거절하고, 이미 있는
  계정에 수단을 더하는 연결(API-009)은 이메일 없이도 진행합니다.

### 인증 메일 발송

계정 메일은 `MAIL_MODE`로 발송 수단을 고릅니다. 가입 인증번호 메일(API-078)과 비밀번호 재설정 메일
(API-010)이 같은 값을 쓰므로 두 메일은 항상 같은 모드로 함께 뜹니다.

| 값 | 동작 | 용도 |
| --- | --- | --- |
| `log` | 발송하지 않고 인증번호와 재설정 링크를 로그로 남깁니다 | 로컬 수동 확인 |
| `smtp` | `spring.mail.*` 설정으로 실제 발송합니다 | dev·운영 |

`MAIL_MODE`는 **기본값이 없어** 지정하지 않으면 기동에 실패합니다. `log`는 인증번호와 재설정 링크가 로그에 남으므로
로컬·테스트 전용이며, 허용 프로파일(`local`, `test`, 프로파일을 지정하지 않은 테스트 실행의 `default`) 밖에서
`MAIL_MODE=log`이면 기동에 실패합니다. `smtp`는 `MAIL_USERNAME`과 앱 비밀번호
`MAIL_PASSWORD`가 필요하며, 값은 개인 `.env` 또는 배포 환경의 Secret으로만 주입합니다.

발송은 **응답 경로에서 분리**되어 전용 스레드 풀(`feelm.mail.async`, 기본 스레드 2개·큐 100칸)에서 처리됩니다.
계정이 있는 이메일과 없는 이메일의 응답 시간이 SMTP 왕복 때문에 갈리면, 응답이 감춘 계정 존재 여부가 시간으로
드러나기 때문입니다(API-078·API-010). 그래서 발송 결과는 응답에 영향을 주지 않습니다. 큐가 차면 그 발송은
**버리고** 경고 로그만 남깁니다(호출 스레드가 대신 보내면 응답이 다시 SMTP를 기다리게 됩니다). 유실된 메일은
사용자의 재요청(API-078·API-010)이 복구 경로입니다.

종료 시에는 진행 중인 발송과 큐에 남은 발송을 `feelm.mail.async.shutdown-await`(기본 10초) 안에 내보내려
하고, 그 시간이 지나면 남은 발송을 버립니다. 스레드가 2개이므로 그 안에 실제로 나가는 것은 몇 건뿐이고 큐에
쌓인 나머지는 대부분 버려집니다. 발송 스레드를 daemon으로 두어 이 값이 실제 상한이 되게 했습니다. Spring은
대기 시간을 넘겨도 남은 큐를 강제로 취소하지 않으므로, non-daemon 스레드라면 남은 큐를 다 비울 때까지 재시작이
지연되고 SMTP가 느린 구간에서는 그 시간이 수십 분에 이를 수 있습니다.

`spring.mail`의 `timeout`·`writetimeout`은 발송 1건의 상한이 아닙니다. 소켓 read·write마다 다시 적용되고
한 건 발송은 SMTP 왕복이 여러 번 일어나므로, 서버가 매 응답을 느리게 주면 한 건이 수십 초 이상 걸립니다. 위의
풀 크기·큐 크기와 종료 대기는 이 사실을 전제로 정했습니다.

응답 시간이 완전히 같아지는 것은 아닙니다. 계정이 있는 경로에는 토큰 발급·저장 수 ms가 더 들어가며, 이 차이는
감수합니다(S15P21E106-282 U-2). 이 작업이 없애는 것은 지배적인 차이인 SMTP 왕복(정상 수백 ms~수 초, 서버가
느리면 수십 초 이상)입니다.

메일 발송에 영속 재시도가 필요해지면 그때 아웃박스(ADR-0018)와 같은 수단을 별도로 결정합니다.

#### 전역 발송 상한

계정 메일 전체(가입 인증·재발송·재설정)에는 발송 계정의 쿼터를 지키는 전역 상한이 있습니다
(`feelm.mail.send-budget`, 기본 100건/1시간·300건/24시간). 이 상한은 **요청이 아니라 실제 발송**을 세고
발송 스레드에서 소모하므로, 상한에 걸려도 API 응답은 `202`·`201` 그대로이고 그 시점의 메일만 버려집니다.
요청 시점에 세면 메일을 한 통도 보내지 않는 요청만으로 상한을 말려 정상 사용자의 계정 복구를 막을 수 있고,
응답이 바뀌는 순간 실제 발송을 세는 카운터가 계정 존재 여부를 알려 주는 신호가 됩니다(S15P21E106-287).

상한에 걸린 상태는 **사용자에게 아무 신호도 남기지 않으므로** 다음 두 가지로 확인합니다.

1. 로그. 사용률이 상한의 80%에 닿을 때와 상한을 넘을 때 창마다 한 번씩 WARN이 남습니다. 기동 시에는 유효한
   상한을 `INFO`로 한 줄 남기므로, 설정이 비어 상한 없이 떠 있는 상태도 로그로 드러납니다.

```bash
docker logs feelm-backend | grep "전역 발송 상한"
```

2. 카운터 직접 조회. 현재 사용량은 Redis 키 `auth:mail:sent:<창 초>`에 있습니다(기본값이면 `3600`과
   `86400`). TTL은 그 창이 언제 비워지는지를 알려 줍니다.

   조회에는 읽기 전용 계정 `feelm_readonly`를 씁니다. 이 계정은 `infra/.env`의 `REDIS_READONLY_PASSWORD`가
   **비어 있으면 꺼진 상태로 기동**하므로(`infra/redis/start.sh`), 값을 채우고 Redis 컨테이너를 다시 만든
   환경에서만 동작합니다. 비밀번호는 컨테이너 안에서 확장되도록 넘겨 호스트 셸 기록에 남지 않게 합니다.

```bash
docker exec feelm-redis sh -c 'REDISCLI_AUTH="$REDIS_READONLY_PASSWORD" redis-cli --no-auth-warning --user feelm_readonly mget auth:mail:sent:3600 auth:mail:sent:86400'
docker exec feelm-redis sh -c 'REDISCLI_AUTH="$REDIS_READONLY_PASSWORD" redis-cli --no-auth-warning --user feelm_readonly ttl auth:mail:sent:3600'
```

마른 상한은 창이 만료되면 스스로 풀립니다. 시연처럼 기다릴 수 없는 상황에서는 관리 계정으로 해당 카운터를
지우면 즉시 회복됩니다. 지우기 전에 무엇이 예산을 태웠는지(로그의 경고와 카운터 값) 먼저 확인합니다.

```bash
docker exec feelm-redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning del auth:mail:sent:3600'
```

상한과 IP 단위 요청 제한은 이미지를 다시 만들지 않고 `infra/.env`의 `MAIL_SEND_BUDGET_*`,
`MAIL_RATE_LIMIT_*`로 조정합니다(값 목록은 `infra/.env.example`). 바꾼 뒤에는 backend 컨테이너를 다시
만들어야 반영되며, 창 길이가 키에 들어 있어 이전 창의 카운터가 새 설정에 섞이지 않습니다. 창 길이를 나타내는
값(`MAIL_RATE_LIMIT_IP_WINDOW`, `MAIL_RATE_LIMIT_EMAIL_INTERVAL`)에 단위를 생략하면 **초**로 읽습니다.

Redis에 닿지 못하면 계정 메일은 발송하지 않습니다(fail-closed). 인증 제한과 방향을 맞춘 선택이며, 장애 중
발송 폭탄을 허용하지 않는 대신 가입 인증번호 메일까지 함께 멈춥니다. 복구 경로는
재요청(API-078·API-010)입니다.

이때 **응답은 두 갈래**입니다. 발송 상한은 발송 스레드에서 보므로 그 확인이 실패해도 응답은 `201`·`202` 그대로이고
메일만 나가지 않습니다. 반면 IP 단위 요청 제한은 요청 경로에서 판정하고 같은 Redis를 쓰므로, 장애 중에는 그 판정이
먼저 걸려 가입(API-001)·인증번호 발송(API-078)·재설정 요청(API-010)이 `500 INTERNAL_ERROR`로 실패합니다. 즉 Redis
장애 중 사용자에게 실제로 보이는 것은 "메일만 안 오는 상태"가 아니라 **요청 실패**입니다. 가입 경로는
S15P21E106-287에서 요청 제한이 추가되기 전까지 Redis 없이도 성공했으므로, 이 성질은 그때 함께 생겼습니다.

가입 인증번호 메일에는 링크가 없습니다. 6자리 숫자를 본문에 실어 보내고 사용자가 회원가입 화면에 입력합니다
(API-078·079, S15P21E106-604). 번호는 Redis에 SHA-256 해시로만 남고 수명은 10분입니다.

비밀번호 재설정 링크는 `${FRONTEND_BASE_URL}/app/account/reset?token=...` 형식입니다. 프론트 화면으로
이동하고 프론트가 API-011을 호출합니다. **경로는 프론트엔드 라우트에서 파생된 값입니다.** 앱은
Vite base가 `/app/`이라 모든 화면이 `/app` 아래에 있고 화면별 경로는 `frontend/src/routes/paths.ts`가
가지므로, 프론트 경로가 바뀌면 `feelm.mail.password-reset-url`도 함께 바꿔야 합니다. 토큰은 불투명 난수
문자열이며 서버는 해시만 저장합니다. 수명은 30분입니다.

### 공개 경로 추가

`SecurityConfig`는 기본 정책이 "인증 필요"이므로 비회원이 호출하는 새 API는 허용 목록에
등록해야 동작합니다. 등록 규칙과 변경 시 절차는
[백엔드 개발 컨벤션 11장](../docs/backend-convention.md#11-도메인-간-참조와-공통-코드)을 따릅니다.

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
- `V202609161115__seed_popcorn_flavors.sql`은 439 `taste_id` 0~7을 `FLAVOR_A`~`FLAVOR_H`로
  초기화한다. 다른 code/cluster 조합이나 9번째 맛이 있으면 조용히 덮어쓰지 않고 migration 전체를
  실패시킨다. 영화별 대표 맛은 Flyway에 넣지 않고
  [`apply_popcorn_flavors.py`](../pipeline/consumer/tools/apply_popcorn_flavors.py)의 dry-run 검증 후
  별도 transaction으로 반영한다.

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

### Testcontainers와 Spring Context 계약

PostgreSQL·Redis 기반 계약 테스트는 테스트 클래스마다 컨테이너를 만들지 않고 JVM 수명의
공유 실행 환경을 사용합니다. 컨테이너 주소가 고정돼야 같은 Spring 설정을 사용하는 테스트가
ApplicationContext를 재사용할 수 있습니다.

- PostgreSQL 계약 테스트는 `SharedPostgresContractTest`를 상속합니다.
- Redis는 `SharedRedisContainer.instance()`를 `@ServiceConnection`에 연결하고, 각 테스트
  시작 시 `SharedRedisState.clear()`로 키를 비웁니다.
- fixture는 자신이 사용하는 DB 테이블을 테스트 전에 명시적으로 초기화합니다. 공유 DB에
  이전 클래스의 데이터가 남아 있어도 단독 실행과 같은 결과가 나와야 합니다.
- `@SpringBootTest` 속성·web environment·`@Import`가 다르면 Context cache가 분리됩니다.
  테스트 의미에 필요한 차이만 추가하고, 같은 설정은 공통 지원 클래스에 둡니다.
- database 단위 격리가 필요한 migration 테스트는 `MigrationPostgresSupport`를 사용합니다.
  병렬 실행이나 별도 장애 격리가 필요한 경우에는 공유 여부와 초기화 경계를 먼저 합의합니다.
- 허용된 공통 지원 코드 밖에서 `PostgreSQLContainer`나 `RedisContainer`를 직접 생성하면
  `SharedTestcontainerPolicyTest`가 빌드를 실패시킵니다.

JUnit은 테스트 클래스 두 개를 병렬로 실행하되, 한 클래스 안의 테스트 메서드는 순차로
실행합니다. `@SpringBootTest` 또는 공유 PostgreSQL·Redis를 사용하는 테스트는
`@SharedTestRuntime`을 직접 선언하거나 해당 annotation이 붙은 공통 지원 클래스를
상속해야 합니다. 이 테스트들은 동일한 resource lock을 사용해 서로 순차 실행되고, 공유
상태가 없는 단위 테스트와 database 단위로 격리된 migration 테스트만 병렬 실행됩니다.
`ParallelTestExecutionPolicyTest`는 병렬 설정과 Spring 통합 테스트의 lock 누락을 검사합니다.

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

## TMDb 초기 데이터 적재 스크립트

이 작업은 백엔드 애플리케이션 기능이 아니라 백엔드 또는 인프라 담당자가 합의된 환경에서
실행하는 Bash 스크립트다. `run-tmdb-ingestion.sh`는 TMDb 원본 응답을 파일로 보존하면서 PostgreSQL의 영화
메타데이터를 적재하고, `run-initial-data-load.sh`는 합의된 MovieLens·TMDb 원천 파일을 HDFS에
업로드한다. 두 저장소의 완료 상태는 서로 대신하지 않으며 각각 별도 결과 파일을 남긴다.
HDFS는 J15E106A에서 실행하고, PostgreSQL은 J15E106의 Docker 또는 SSH 터널을 통한 적재
계정으로 접속하므로 두 단계를 한 명령으로 묶지 않는다.
MovieLens 사용자는 서비스 `users`와 연결하지 않고 영화만 TMDb ID로 연결한다. Spark 학습과
Redis 반영은 후속 작업이며 이 스크립트의 범위에 포함하지 않는다.

### 사전 조건

- PostgreSQL Docker 모드: J15E106, 실행 중인 `feelm-postgres`, `bash`, `curl`, `jq`, `docker`
- PostgreSQL 직접 접속 모드: `bash`, `curl`, `jq`, `psql`, J15E106 SSH 접속 정보와 별도
  발급된 적재 계정. DB 포트는 외부에 공개하지 않고 SSH 터널을 사용한다.
- HDFS 단계: J15E106A, 실행 중인 `feelm-hdfs-namenode`, Live DataNode 2대, Safe mode OFF
- TMDb Token은 저장소나 명령 인자에 넣지 않고 실행 셸의 `TMDB_TOKEN`으로만 주입한다.
- HDFS 대상 경로, 데이터 버전·범위와 중복 정책은 관련 담당자와 합의한 값을 사용한다.
- OTT는 승인된 매핑 CSV를 받은 경우에만 동기화한다. 현재 영화 적재 매핑은
  넷플릭스·디즈니+·왓챠·웨이브·티빙 5종이며 `watch/providers.results.KR`의 정기구독
  `flatrate`만 저장한다. 파일을 생략하면 기존 OTT 관계를 삭제하지 않고 미승인 provider
  발생 건수만 보고한다. 쿠팡플레이는 영화 provider ID와 실제 영화 연결을 검증하기 전까지
  현재 매핑에 포함하지 않는다.

백엔드가 인프라에 전달할 데이터 디렉터리에는 확정 원천 파일만 두고, 다음 정보를 함께
전달한다. 값은 스크립트가 생성하는 manifest와 일치해야 한다.

| 전달 항목 | 값 |
| --- | --- |
| 전달 위치 | 인프라와 합의한 팀 전용 경로(저장소에는 대용량 원천 파일을 커밋하지 않음) |
| 버전 | MovieLens 배포명 또는 TMDb 스냅샷 일자처럼 재현 가능한 불변 값 |
| 범위 | 전체/샘플 및 포함한 파일·TMDb ID 범위 |
| 파일 수·크기·체크섬 | `hdfs-manifest.json` 또는 `input-manifest.json` 기준 |

프로젝트의 HDFS 원천 데이터 경로는 `/data/raw/<dataset>/<version>` 규칙을 사용한다.
MovieLens 32M은 `/data/raw/movielens/ml-32m`, TMDb 스냅샷은
`/data/raw/tmdb/<YYYYMMDD>`가 된다. 스크립트가 `--dataset`과 `--version`으로 이 경로를
자동 생성하며, 인프라 환경에서 다른 경로가 필요한 경우에만 `--hdfs-target`으로 재정의한다.

전달 파일 범위는 다음처럼 구분한다.

- MovieLens: 배포 ZIP을 풀어 얻은 `ratings.csv`, `movies.csv`, `tags.csv`, `links.csv`,
  `README.txt`, `checksums.txt` 원본. 즉 **데이터 CSV 4개 + 메타 파일 2개 = 총 6개**다.
  추천 매핑은 반드시 `links.csv`의 `tmdbId`를 사용한다.
- TMDb: 확정 TMDb ID 입력 파일과 수집 단계에서 보존한 `raw-tmdb/<tmdb_id>.json`.
  API 응답에는 인증 Token이 포함되지 않는다.

대용량 데이터 자체는 GitLab에 올리지 않고 합의한 팀 전용 전달 경로를 사용한다. GitLab에는
스크립트와 이 실행 안내만 포함하고, 실제 전달 위치와 manifest는 인프라 담당자에게 별도로
전달한다.

OTT 매핑 파일은 다음 헤더를 정확히 사용한다. 코드에는 `A-Z`, 숫자와 `_`만 사용할 수 있고,
`logo_path`에는 TMDb 응답의 `/...` 경로를 넣는다. 스크립트가 이미지 base URL과 결합해
`otts.logo_url`로 저장한다.

```csv
tmdb_provider_id,code,name,logo_path
```

현재 승인 코드는 `NETFLIX`, `DISNEY_PLUS`, `WATCHA`, `WAVVE`, `TVING`이다. provider ID와
`logo_path`는 추측하거나 과거 값을 복사하지 않고 적재 시점의 TMDb 영화 provider 응답으로
확인한다.

2026-09-08 KR provider 조회에서 `Netflix`(8), `Disney Plus`(337), `Watcha`(97),
`wavve`(356), `TVING`(1883)은 확인했다. 영화 provider 응답에 없는 `Coupang Play`는
TV provider ID를 대신 사용하지 않고 별도 검증 전까지 매핑을 보류한다.
`Netflix Standard with Ads`(1796)는 1차 지원 목록의 독립 OTT가 아니므로 제외한다.
현재 검증된 매핑은 `backend/scripts/samples/ott-provider-mapping.csv`에 보관한다.

```bash
{ printf 'header = "Authorization: Bearer %s"\n' "$TMDB_TOKEN"; } \
  | curl --config - --silent --show-error --fail \
  "https://api.themoviedb.org/3/watch/providers/movie?watch_region=KR" \
  | jq -r '.results[] | [.provider_id, .provider_name, .logo_path] | @tsv'
```

영화별 제공처는 `.results.KR.flatrate`에서만 선택한다. `rent`, `buy`, `free`, `ads`는
구독 중인 OTT 필터의 의미와 맞지 않아 저장하지 않는다. `movie_ott_providers.watch_url`에는
TMDb가 반환하는 `results.KR.link`를 저장한다. 이 URL은 provider별 OTT 앱 딥링크가 아니라
지역 단위 TMDb/JustWatch 안내 페이지이며, 1차 MVP에서 사용하는 절충안이다.
`otts`는 API-025 담당자의 기준정보이므로 지원 목록·code·표시명·로고를 변경할 때는 해당
담당자와 먼저 합의한다. 매핑 CSV를 바꾼 뒤 기존 checkpoint에 적용하려면 합의 후
`--refresh`로 대상 영화를 다시 동기화한다.

카탈로그는 CSV 전체 검증 후 단일 트랜잭션으로 반영한다. 기존 code/provider 쌍은 유지하고
이름·로고만 갱신하며, 식별자 충돌과 입력·SQL 오류는 카탈로그 전체 적용을 중단한다.
이후 영화별 트랜잭션의 실패까지 카탈로그를 되돌리지는 않는다. 신규 환경은 승인 CSV 5종을
새 Flyway seed로 초기화하므로 영화 수집이나 TMDb 인증이 필요하지 않다. 기존 식별쌍의 행과
참조를 그대로 보존하고 없는 쌍만 추가하며, 충돌 시 seed 전체 행 변경이 롤백되고 기동이 중단된다.
서비스는 OTT 지원을 종료하지 않으며 기존 목록과 사용자 데이터를 보존한다.
초기화 명령·사전 점검·중단 및 복구 조건은
[OTT 기준정보 운영 안내](scripts/OTT-CATALOG-OPERATIONS.md)를 따른다.

### 1. 전달 데이터 manifest와 입력 검증

MovieLens·TMDb 원천 파일을 인프라에 전달하기 전에 로컬에서 파일 위치, 버전·범위, 파일 수,
전체 크기와 SHA-256 manifest를 만든다. 이 명령은 HDFS에 접속하거나 파일을 업로드하지 않는다.

기본 범위는 MovieLens 32M `links.csv`의 유효한 고유 TMDb ID다. 현재 원본 기준으로
87,585행 중 TMDb ID가 있는 행은 87,461개, 결측은 124개, 중복 제거 후 적재 대상은
87,425개다. MovieLens 원본의 중복 행은 수정하지 않고 HDFS에 그대로 보존하며,
PostgreSQL 입력에서만 TMDb ID를 중복 제거한다. 아래 명령으로 기본 목록과 5편 샘플을
생성한다.

```bash
bash ./backend/scripts/prepare-tmdb-input.sh \
  --movielens-links <ml-32m/links.csv> \
  --scope movielens-ml-32m-linked-tmdb \
  --sample-size 5 \
  --output-dir <영속-입력-준비-디렉터리>
```

생성 결과는 `tmdb-ids.txt`, `tmdb-ids-sample.txt`, `tmdb-id-sources.csv`,
`tmdb-input-manifest.json`, `sample-expected.json`이다. SSAFY 30건 샘플은 소량 실행 계약
검증에만 사용하며 운영용 `tmdb-ids.txt`에 합치지 않는다. 저장소의
`backend/scripts/samples/`에는 실행 계약 확인용 5편 입력과 예상 결과가 있으며 운영 데이터나
완료 증빙으로 사용하지 않는다.

이번 전달 범위는 MovieLens 32M의 87,425개 TMDb ID에 MovieLens 생성 다음 날인
`2023-10-13`부터 수집일 `2026-09-07`까지 TMDb `primary_release_date` 기준 영화를 추가한다.
추가 Discover 조회에만 `include_adult=false`, `include_video=false`를 적용하고 전체 결과를
`tmdb_id`로 중복 제거한다. MovieLens 기본 입력은 원본 연결 범위를 보존하므로 이 필터의
적용 대상이 아니다. 따라서 최종 합집합의 서비스 노출 가능 여부는 적재와 별도로 판단한다.
MovieLens 32M은 1995년 1월 9일부터 2023년 10월 12일까지 발생한 평점·태그를 포함하고
2023년 10월 13일 생성된 배포본이다.
다음 한 명령은 날짜별 Discover 응답을 보존해 최종 ID 목록과 버전·범위 manifest를 만든 뒤,
그 목록의 원본 JSON까지 수집한다. 중단되면 같은 명령을 다시 실행해 이미 받은 Discover
페이지와 영화 원본부터 이어서 진행한다.

```bash
bash ./backend/scripts/prepare-tmdb-raw-handoff.sh \
  --base-input <MovieLens-기본-tmdb-ids.txt> \
  --from-date 2023-10-13 \
  --to-date 2026-09-07 \
  --output-dir <TMDb-전체-전달-디렉터리>
```

출력은 최종 `tmdb-ids.txt`, ID별 출처 `tmdb-id-sources.csv`, API 응답
`discover-pages/`, 범위·건수·체크섬을 기록한 `tmdb-input-manifest.json`과
`raw-tmdb/<tmdb_id>.json`이다. 각 영화는 성공 즉시 원자적으로 저장되고 checkpoint에
기록된다. PostgreSQL 계정은 필요하지 않다. `Ctrl+C`, 네트워크 끊김 또는 PC 재시작 뒤
같은 명령을 실행하면 유효한 기존 파일은 API를 다시 호출하지 않고 건너뛴다.
원본 수집은 TMDb의 `append_to_response`로 상세·크레딧·시청 제공처를 한 요청에 받고,
기본 8개 작업을 병렬 실행한다. API가 `429`를 반환하면 `Retry-After`를 따라 자동 재시도한다.

모든 후보를 조회한 완전 완료 조건은 `collection-manifest.json`의 `status`가 `COMPLETE`, 실패와
누락이 모두 `0`, `raw-files.sha256`의 파일 수가 최종 TMDb ID 수와 같은 것이다. 조회 시점의
HTTP 404 등으로 가져올 수 없는 ID가 남아 팀이 그 범위를 승인한 경우에는 `PARTIAL` 상태도 성공 원본과
실패 목록을 함께 전달할 수 있다. 이때 전체 후보를 성공했다고 표현하지 않고 성공·실패 분모와
HTTP 상태를 manifest와 `failure-report.json`에 그대로 기록한다. 함께 전달할 파일은
`tmdb-input-manifest.json`, `collection-manifest.json`, `collection-summary.txt`,
`raw-files.sha256`, `failure-report.json`, `tmdb-ids.txt`, `tmdb-id-sources.csv`,
`discover-pages/`, `raw-tmdb/`이다. `TMDB_TOKEN`은 어떤 전달 파일에도 포함하지 않는다.

TMDb 키워드는 영화 상세 원본과 별도 endpoint이므로 같은 최종 ID 목록으로 따로 수집한다.
이 작업은 `raw-tmdb/`를 변경하지 않으며 별도의 checkpoint, checksum, manifest와 실패 목록을
생성한다. 중단 후 같은 명령을 실행하면 검증된 파일은 재사용한다.

```bash
bash ./backend/scripts/collect-tmdb-keywords.sh \
  --input <TMDb-전체-전달-디렉터리>/tmdb-ids.txt \
  --output-dir <TMDb-전체-전달-디렉터리> \
  --scope "movielens-ml-32m+tmdb-release-2023-10-13_to_2026-09-07" \
  --workers 16
```

키워드 산출물은 `raw-keywords/<tmdb_id>.json`, `keywords-checkpoint-success.txt`,
`keywords-files.sha256`, `keywords-collection-manifest.json`,
`keywords-collection-summary.txt`, `keywords-failure-report.json`이다. 키워드 배열이 비어 있어도
유효한 TMDb 응답이면 성공 파일로 보존한다.

```bash
bash ./backend/scripts/run-initial-data-load.sh manifest \
  --dataset movielens \
  --version ml-32m \
  --scope full-official-release \
  --output-dir <영속-결과-디렉터리> \
  --file <ml-32m/links.csv> \
  --file <ml-32m/movies.csv> \
  --file <ml-32m/ratings.csv> \
  --file <ml-32m/tags.csv> \
  --file <ml-32m/README.txt> \
  --file <ml-32m/checksums.txt>
```

`movielens`·`ml-32m`·`full-official-release` 조합은 위 6개가 정확히 모두 있어야 manifest를
생성한다. CSV 4개만 지정하거나 메타 파일이 빠지면 전달 범위 오류로 실패한다. 다른 데이터셋은
필요한 원천 파일을 반복 지정한다.

```bash
bash ./backend/scripts/run-initial-data-load.sh manifest \
  --dataset <movielens-or-tmdb> \
  --version <확정-버전> \
  --scope <확정-범위> \
  --output-dir <영속-결과-디렉터리> \
  --file <원천파일-1> \
  --file <원천파일-2>
```

서버 전달 전 로컬 또는 J15E106에서 입력 파일의 ID, 크기와 SHA-256을 확인한다. SSAFY `|`
구분 파일은 `id` 헤더를 사용하고, 단순 목록은 한 줄에 양의 TMDb ID 하나를 사용한다.

```bash
bash ./backend/scripts/run-tmdb-ingestion.sh \
  --input <확정-TMDb-ID-파일> \
  --output-dir <영속-결과-디렉터리> \
  --limit 5 \
  --validate-only
```

### 2. 소량 TMDb·PostgreSQL 공동 검증

터미널 기록에 토큰이 남지 않도록 대화형으로 입력한 뒤 소량 dry-run, 실제 적재, 같은 입력
재실행 순서로 확인한다.

```bash
read -rsp 'TMDb token: ' TMDB_TOKEN && printf '\n'
export TMDB_TOKEN

bash ./backend/scripts/run-tmdb-ingestion.sh \
  --input <확정-TMDb-ID-파일> \
  --output-dir <영속-결과-디렉터리> \
  --limit 5 \
  --dry-run

bash ./backend/scripts/run-tmdb-ingestion.sh \
  --input <확정-TMDb-ID-파일> \
  --output-dir <영속-결과-디렉터리> \
  --limit 5 \
  --ott-mapping <승인된-OTT-매핑-CSV>
```

은채님이 별도 적재 계정을 발급한 경우에는 먼저 다른 터미널에서 SSH 터널을 유지한다. 실제
SSH 사용자·서버 주소·DB 계정은 저장소에 기록하지 않는다.

```bash
ssh -N -L 15432:127.0.0.1:5432 <SSH_USER>@<SERVICE_SERVER>
```

적재 터미널에서는 접속값과 비밀번호를 현재 셸에만 주입하고 `direct` 모드로 실행한다.
`PGPASSWORD`와 `TMDB_TOKEN`은 명령 인자, GitLab, MM, 결과 파일에 기록하지 않는다.

```bash
export PGHOST=127.0.0.1
export PGPORT=15432
export PGDATABASE=<전달받은-DB명>
export PGUSER=<전달받은-적재계정>
read -rsp 'PostgreSQL password: ' PGPASSWORD && printf '\n'
export PGPASSWORD

bash ./backend/scripts/run-tmdb-ingestion.sh \
  --postgres-mode direct \
  --input <확정-TMDb-ID-파일> \
  --raw-dir <TMDb-전체-전달-디렉터리>/raw-tmdb \
  --output-dir <영속-결과-디렉터리> \
  --limit 5

unset PGPASSWORD TMDB_TOKEN
```

직접 접속 모드는 실행 전에 `SELECT 1`과 필수 테이블·`movies.tmdb_id` 유일 제약을 확인하고,
접속 또는 권한이 부족하면 데이터 변경 전에 실패한다. 5건 실제 적재와 결과 검증이 끝난 뒤에만
`--limit`을 제거한다.

`--raw-dir`을 사용하면 수집 완료 원본을 읽으므로 PostgreSQL 적재 때 TMDb API를 다시
호출하거나 Token을 다시 전달할 필요가 없다. 동일 명령을 다시 실행했을 때 성공 checkpoint의
영화는 건너뛰어야 한다. 동일 원본을 DB에 다시 반영할 때만 `--refresh`를 추가한다. 전체
적재는 소량 검증 후 `--limit`을 제거해 실행한다.

PostgreSQL 단계의 주요 산출물은 다음과 같다.

- `raw-tmdb/<tmdb_id>.json`: 토큰을 포함하지 않는 TMDb 원본 응답
- `checkpoint-success.txt`: DB transaction이 성공한 TMDb ID
- `failure-report.json`: 실패 ID와 재처리 가능한 오류 분류
- `service-movies.csv`: 찬형 담당 파이프라인 입력, 정확한 `service_movie_id,tmdb_id` 헤더
- `postgres-sample.csv`: 입력의 앞 5개 TMDb ID에 대한 영화와 관계 건수 샘플 조회 결과
- `input-manifest.json`, `run-manifest.json`, `run-summary.txt`: 입력 및 실행 결과

영화는 `tmdb_id`로 upsert해 기존 `movies.id`를 유지한다. `average_rating`, `rating_count`,
`movielens_id`, `representative_flavor_id`는 수정하지 않는다. 한 영화의 본체와 장르·국가·인물·
승인된 OTT 관계는 하나의 PostgreSQL transaction으로 처리한다.

PostgreSQL 필드 매핑은 현재 Flyway Migration을 기준으로 다음과 같다.

| TMDb 원천 | PostgreSQL 대상 | 처리 |
| --- | --- | --- |
| `details.id` | `movies.tmdb_id` | 유일 키, 이 값으로 upsert |
| `details.title`, `original_title`, `overview` | `movies.title`, `original_title`, `synopsis` | 한국어 제목 우선, 제목이 없으면 원제 사용, 빈 줄거리는 그대로 보존 |
| `details.release_date`, `runtime` | `movies.release_date`, `runtime_minutes` | 빈 날짜·0분은 `NULL` |
| `details.poster_path`, `backdrop_path` | `movies.poster_url`, `backdrop_url` | 포스터는 `w500`, 배경은 `w1280` TMDb CDN URL로 변환 |
| `details.genres[]` | `genres`, `movie_genres` | `genres.tmdb_genre_id`로 기준정보 upsert 후 관계 교체 |
| `details.production_countries[]` | `movie_countries.country_code` | ISO 3166-1 alpha-2만 저장 |
| `credits.crew[job=Director]`, `cast[]` | `people`, `movie_people` | `tmdb_person_id` upsert, 역할은 `DIRECTOR`/`ACTOR` |
| `watch/providers.results.KR.flatrate` | `otts`, `movie_ott_providers` | 현재 승인된 5개 OTT만 동기화하고 다른 offer type은 제외 |
| DB identity | `movies.id` | 직접 입력하지 않으며 재적재 시 기존 ID 유지 |

영화 제목은 TMDb `ko-KR` 상세 제목에 한글이 있으면 그대로 사용한다. 상세 제목이
외국어이면 같은 TMDb ID의 고유한 `ko-KR` 번역 제목, 고유한 KR 대체 제목 순서로
확인된 한글 제목을 선택한다. TMDb에 한국어 제목이 없어도
[`scripts/data/verified-korean-titles.tsv`](scripts/data/verified-korean-titles.tsv)에
원제·제작연도·국내 출처로 확인한 제목이 있으면 이를 사용한다. 적재 시 해당 TMDb ID의
원제와 연도가 보정표와 다르면 그 영화의 적재를 실패 처리해 다른 작품에 제목이 붙지
않도록 한다. TMDb 후보가 모호해도 보정표에 확인된 제목이 있으면 이를 사용한다.
보정표에도 없으면 기존 DB에 저장된 한국어 제목을 유지한다. 기존 한국어 제목도
없으면 TMDb 제목 또는 원제를 사용하며, 원제는 `original_title`에 그대로 둔다.
보정표에는 현재 확인된 5편만 기록한다.

기존 영화 보정은 외국어 제목인 TMDb ID만 별도 입력으로 준비한다. TMDb 번역·대체
제목을 다시 확인할 대상은 원본 수집을 `--refresh`한 뒤 적재도 `--refresh`한다.
보정표의 작품은 기존 원본을 재사용할 수 있다. 전체 서비스 DB에 직접 일괄 적용하지
않고 소량 dry-run과 격리 DB에서 제목·원제·검색 결과를 먼저 확인한다.
접속 환경 변수를 설정한 터미널에서 후보 ID는 다음처럼 추출할 수 있다.

```bash
psql -X -v ON_ERROR_STOP=1 -At -c \
  "SELECT tmdb_id FROM movies WHERE title !~ '[가-힣]' ORDER BY tmdb_id" \
  > title-backfill-ids.txt
bash ./backend/scripts/collect-tmdb-raw.sh \
  --input title-backfill-ids.txt --output-dir title-backfill-source \
  --scope korean-title-backfill --refresh
bash ./backend/scripts/run-tmdb-ingestion.sh \
  --input title-backfill-ids.txt \
  --raw-dir title-backfill-source/raw-tmdb \
  --output-dir title-backfill-load --postgres-mode direct \
  --ott-mapping backend/scripts/samples/ott-provider-mapping.csv \
  --limit 5 --dry-run
```

적재를 진행할 때는 같은 입력으로 `--dry-run`을 빼고 `--refresh`를 넣는다. 5건의
제목·원제와 관계 데이터를 확인한 뒤 `--limit`을 제거한다. 재적재는 제목 외에도
줄거리·장르·국가·인물 정보를 최신 TMDb 값으로 갱신하므로 격리 DB에서 변경 범위를
확인한다. TMDb와 검증된 보정표 모두에 한국어 제목이 없는 영화는 기존 한국어
제목이 있다면 보존하고, 그렇지 않으면 TMDb 제목을 유지한다.

이미지 파일을 적재하거나 원본 JSON을 변형하지 않는다. `raw-tmdb`에는 TMDb의
`poster_path`·`backdrop_path`가 그대로 남으므로, 추후 제공 크기를 바꿔도 TMDb 메타데이터를
다시 수집할 필요가 없다.

한국어 `overview`가 비어 있는 영화도 적재 실패로 처리하지 않고 원본 값 그대로 저장한다.
줄거리 보강은 초기 적재의 완료 조건이 아닌 별도 데이터 품질 작업이다. 보강할 때는 기존
`tmdb_id`와 서비스 영화 ID를 유지하고 대상·언어·수집 시각을 기록한다.

`movielens_id`, `representative_flavor_id`, `average_rating`, `rating_count`는 후속 매핑·추천·
서비스 집계 영역이므로 이 스크립트가 수정하지 않는다. 적재 완료 후 `service-movies.csv`는
반드시 적재한 환경의 DB에서 아래 계약으로 새로 생성된다.

```sql
SELECT id AS service_movie_id, tmdb_id
FROM movies
ORDER BY id;
```

소량 실제 적재의 예상 결과는 명령 종료 코드 `0`, `run-manifest.json`의 PostgreSQL 상태
`SUCCESS`, 실패 `0`, `postgres-sample.csv`에서 성공한 샘플 영화가 조회되는 것이다. 같은
명령을 다시 실행하면 신규·갱신 대신 checkpoint 건너뜀 건수가 증가하고 DB 행은 중복되지
않아야 한다. 일부 영화가 실패하면 종료 코드 `3`과 `PARTIAL_FAILURE`가 기록되며 성공 영화는
checkpoint에 남아 다음 실행에서 건너뛴다.

필수 객체·배열이 누락되거나 응답 ID가 요청과 다르면 불완전 응답으로 실패 처리한다.
`genres: []`, `production_countries: []`, `cast: []`, `crew: []`, `results: {}`는
정상적인 빈 결과다. 구조 검증은 raw 수집, 개별 API 수집, 기존 raw 적재 모두에 적용하며,
검증에 실패하면 기존 raw와 영화 관계를 보존한다.

전체 영화가 실패하거나 CSV 내보내기가 실패해도 `failure-report.json`,
`run-manifest.json`, `run-summary.txt`에 진단 결과를 남기고 종료 코드 `3`을 반환한다.
manifest의 최상위 `status`는 실행 전체 결과이며, `postgresql`의 건수는 영화 처리 결과다.
`service_movie_export`와 `postgres_sample`은 각각 내보내기 상태와 실패 사유를 기록한다.
실패한 내보내기의 행 수·체크섬은 `null`이며, 이전 실행의 CSV가 남아 있어도 이번 실행의
성공 산출물로 간주하지 않는다.

초기 적재 회귀 테스트는 `bash backend/scripts/tests/initial-data-load-test.sh`로 실행한다.
Bash, jq, GNU coreutils와 Docker가 필요하다. 실제 API 대신 fake curl을 사용하고,
백엔드 통합 테스트와 같은 pgvector 이미지로 네트워크·호스트 포트·볼륨이 없는 임시
PostgreSQL 컨테이너를 생성해 SQL을 검증한 뒤 삭제한다. 운영 DB 접속 정보는 사용하지 않는다.

### 3. HDFS 원천 파일 검증과 업로드

J15E106A에서 업로드 없이 HDFS 상태·파일·경로를 검증한다.

```bash
bash ./backend/scripts/run-initial-data-load.sh validate \
  --dataset <movielens-or-tmdb> \
  --version <확정-버전> \
  --scope <확정-범위> \
  --output-dir <영속-결과-디렉터리> \
  --file <원천파일-1> \
  --file <원천파일-2>
```

공동 확인 후 `validate`를 `hdfs`로 바꾸어 업로드한다. 기본 중복 정책 `skip-if-same`은 기존
경로의 파일명·파일 수·크기·SHA-256이 모두 같을 때만 적재를 건너뛰고, 하나라도 다르면
실패하여 기존 원본을 보호한다. `replace`는 명시한 경우에만 staging 검증 후 교체한다.

HDFS 단계는 `hdfs-manifest.json`과 `hdfs-summary.txt`에 경로, 파일 수·전체 크기와 파일별
원본 위치·HDFS 위치·SHA-256 및 원격 검증 여부를 기록한다. 스크립트는 NameNode를 포맷하거나
Docker Named Volume을 삭제하지 않는다.

소량 HDFS 적재의 예상 결과는 `hdfs-manifest.json` 상태 `SUCCESS`, 합의 경로의 파일 수·크기와
로컬 SHA-256 일치, `checksum_verified: true`다. 같은 경로에 재실행했을 때 내용이 완전히 같으면
`SKIPPED_IDENTICAL`로 끝나고, 다르면 기존 경로를 변경하지 않은 채 실패한다. 실패한 staging은 제거되고,
`replace` 도중 실패하면 기존 경로의 backup 복원을 시도한다.

### 완료 보고

- HDFS: `hdfs-manifest.json`의 상태·대상 경로·파일 수·크기·체크섬
- PostgreSQL: `run-manifest.json`의 신규·갱신·건너뜀·성공·실패 건수와 CSV checksum
- 남은 작업: `failure-report.json`의 실패 사유 및 같은 명령을 이용한 재처리 계획

공유 메시지에는 다음 양식을 사용한다. HDFS와 PostgreSQL은 한쪽만 성공해도 다른 쪽을 완료로
표시하지 않는다.

```text
HDFS: SUCCESS | 경로=<확정 경로> | 파일=<개수> | 크기=<bytes> | checksum=<검증 결과>
PostgreSQL: SUCCESS | 신규=<n> | 갱신=<n> | 건너뜀=<n> | 실패=<n>
샘플 조회: postgres-sample.csv <n>행 확인
추천 전달: service-movies.csv <n>행 | sha256=<값>
남은 작업: 없음 또는 <실패 사유와 재처리 명령>
```

첫 적재는 인프라 담당자와 함께 `--limit 5` 소량 검증 후 실행자를 합의한다. 서버 접근이
제한되면 백엔드는 데이터·스크립트·manifest를 전달하고 인프라가 같은 명령을 실행하며,
백엔드는 반환된 manifest와 샘플 조회 결과를 검증한다.

실제 IP, DB 비밀번호와 TMDb Token은 결과 파일·문서·MR에 기록하지 않는다.
