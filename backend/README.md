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
| Build | Gradle 9.7.1 Wrapper | 빌드, 테스트 및 의존성 관리 |
| Test | JUnit 5, Spring Boot Test | 단위·통합 테스트 |
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
│   ├── user, onboarding, ott
│   ├── search, movie, watch, social, report, trend
│   └── taste, recommendation, party
├── global            # 여러 도메인이 공유하는 공통 코드
│   ├── config
│   ├── entity
│   ├── error
│   └── response
└── infrastructure    # Kafka, Redis, 외부 API 등 외부 시스템 연결(필요 시 생성)
```

담당자별 도메인 매핑은 [백엔드 역할 분배](../docs/backend-role-assignment.md)를
기준으로 합니다.

| 담당 | 도메인 패키지 |
| --- | --- |
| A (계정·온보딩) | `user`, `onboarding`, `ott` |
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

dev 실행 예시: `./gradlew bootRun --args='--spring.profiles.active=dev'`

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
docker run --rm -p 8080:8080 --name feelm-backend feelm-backend
```

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
