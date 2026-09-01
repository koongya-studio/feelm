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

## 로컬 실행

Java 21이 설치된 환경에서 `backend` 디렉터리를 기준으로 실행합니다.

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
