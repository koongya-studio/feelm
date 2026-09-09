# 백엔드 개발 컨벤션

> 상태: 팀 검토용 1차 초안. 14장의 결정 대기 항목은 합의 전 구현 기준으로 사용하지 않는다.

백엔드(Spring Boot, Java) 코드의 구조와 품질 기준을 정의한다. 자동화 가능한 규칙은
도구로 강제하고, 이 문서에는 채택 근거와 도구가 잡지 못하는 구현 규칙을 남긴다.

- API 요청·응답·오류 계약은 [`docs/api/README.md`](./api/README.md)를 기준으로 한다.
- 엔티티·관계·제약은 [`docs/erd.md`](./erd.md)를 기준으로 한다.
- 담당 도메인과 공통 계약 소유권은
  [`docs/backend-role-assignment.md`](./backend-role-assignment.md)를 기준으로 한다.
- AI를 이용한 작업도 이 문서와
  [`docs/ai-collaboration.md`](./ai-collaboration.md)를 동일하게 적용한다.
- 문서 사이에 충돌이 있거나 명세가 미정이면 구현자가 임의로 확정하지 않고 관련 담당자와
  합의한다.

## 1. 코드 스타일

- 기준: [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)의
  **AOSP 변형**을 따른다.
  - 들여쓰기 4칸(공백), 탭 미사용
  - 한 줄 최대 100자
  - 나머지 규칙(중괄호 위치, import 순서, 공백 등)은 Google Java Style과 동일
- 스타일은 리뷰에서 사람이 지적하지 않는다. 아래 Spotless가 자동으로 강제한다.

### 1.1 네이밍

기본 식별자 규칙은 Google Java Style을 따른다.

- 클래스·인터페이스·Enum·record는 `UpperCamelCase`, 메서드·변수는 `lowerCamelCase`,
  상수는 `UPPER_SNAKE_CASE`, 패키지는 소문자를 사용한다.
- 약어도 하나의 단어처럼 취급해 Java 식별자에서는 `userId`, `movieUrl`, `apiClient`처럼
  작성한다.
- 역할이 있는 클래스는 책임이 드러나는 접미사를 사용한다.
  - HTTP 진입점: `{기능명}Controller`
  - 유스케이스: `{기능명}Service`
  - 영속성 접근: `{엔티티명}Repository`
  - 요청·응답: `{기능명}Request`, `{기능명}Response`
  - 변환: `{기능명}Mapper`
  - 비즈니스 예외: `{원인명}Exception`
- 클래스와 변수는 명사 또는 명사구, 메서드는 동사 또는 동사구로 작성한다.
- Boolean 반환 메서드는 `is`, `has`, `can`으로 시작하고, Boolean 변수와 필드는 `active`,
  `enabled`, `deleted`처럼 상태가 드러나는 이름을 사용한다.
- 컬렉션은 요소가 여러 개임을 알 수 있도록 복수형으로 작성한다.
- `data`, `info`, `manager`, `util`, `common`처럼 책임이나 대상이 불분명한 이름을 피한다.
- 테스트 클래스와 메서드의 네이밍은 테스트 작성 기준과 함께 별도 후속 업무에서 확정한다.

## 2. Spotless 자동 포맷팅

`backend/build.gradle`에 [Spotless](https://github.com/diffplug/spotless)와
google-java-format(AOSP 모드)이 설정되어 있다.

| 명령 | 용도 |
| --- | --- |
| `./gradlew spotlessApply` | 전체 Java 소스를 규칙에 맞게 자동 수정 |
| `./gradlew spotlessCheck` | 위반 여부만 검사. CI 검증용 |

- 커밋 전에 `spotlessApply`를 실행한다. IDE 포맷터 설정은 자유이며 최종 기준은 Spotless다.
- `spotlessCheck`는 `check` 태스크에 포함하고, `./gradlew build` 시 위반이 있으면 빌드를
  실패시킨다.
- 포맷터 버전 변경은 공통 빌드 파일 변경이므로 사전 공유 후 별도 업무로 처리한다.

## 3. 패키지 구조

최상위는 계층이 아니라 **도메인 기준**으로 나눈다. 담당자 이름이나 화면 이름으로 패키지를
만들지 않는다.

```text
com.feelm.backend
├── domain
│   ├── auth
│   ├── user
│   ├── onboarding
│   ├── ott
│   ├── search
│   ├── movie
│   ├── watch
│   ├── taste
│   ├── recommendation
│   ├── party
│   ├── report
│   ├── social
│   └── trend
├── global
│   ├── config
│   ├── entity
│   ├── error
│   ├── response
│   └── security
└── infrastructure
    ├── kafka
    ├── redis
    ├── tmdb
    └── mail
```

도메인 패키지와 그 내부 계층은 실제 코드가 생길 때 만든다. 사용하지 않는 빈 패키지를 미리
만들지 않는다. 담당자별 도메인은 [`backend-role-assignment.md`](./backend-role-assignment.md)를
따른다.

```text
domain/movie
├── controller
├── service
├── repository
├── entity
└── dto
    ├── request
    └── response
```

- `global`: 여러 도메인이 함께 사용하는 설정·공통 엔티티·오류·응답·보안 등 기술 독립적인
  공통 코드. `global/security`는 토큰 발급·검증, 인증 필터, 인가·CORS 정책을 담당한다.
- `infrastructure`: Kafka, Redis, 외부 API, 메일처럼 외부 시스템과 연결하는 코드
- `auth`: 로그인, 토큰 재발급과 폐기처럼 인증 유스케이스를 담당한다. 토큰과 필터 자체는
  `global/security`에 둔다.
- `search`: 영화 검색과 검색 필터를 담당한다. 영화 상세와 기준정보는 `movie`에 둔다.
- `watch`: 감상 기록과 평가를 담당하며 한줄평의 저장·수정·삭제도 평가에 포함한다.
- `social`: 공개 한줄평 목록, 공개 프로필, 취향 비교, 사용자 랭킹과 커뮤니티 유스케이스를
  담당한다.
- `trend`: 급상승 영화처럼 전체 사용자 데이터를 집계한 트렌드 유스케이스를 담당한다.
- 사용자 차단과 차단 목록은 `user`에 두고, 다른 도메인이 사용할 차단 필터를 `user`의 공개
  컴포넌트로 제공한다.
- 공통화 가능성만으로 `global`에 코드를 두지 않는다. 두 개 이상의 도메인에서 실제로
  사용하고 의미가 같은 경우에만 이동한다.
- 공통 코드 및 최상위 패키지 변경은 구현 전에 백엔드 담당자에게 공유한다.

## 4. 계층별 책임

### 4.1 Controller

Controller는 HTTP 경계만 담당한다.

- 요청 DTO와 경로·쿼리 파라미터를 받는다.
- Bean Validation과 인증 사용자 정보를 처리한다.
- Service를 호출하고 Response DTO를 반환한다.
- Repository, EntityManager, Kafka, Redis, 외부 API Client를 직접 호출하지 않는다.
- Entity를 생성·수정하거나 비즈니스 조건을 판단하지 않는다.
- Entity를 API 응답으로 직접 반환하지 않는다.

### 4.2 Service

Service는 하나의 유스케이스와 트랜잭션 경계를 담당한다.

- 비즈니스 규칙과 권한을 검증한다.
- Entity를 생성하고 의미 있는 메서드로 상태를 변경한다.
- 필요한 Repository 및 다른 도메인의 공개 컴포넌트를 조합한다.
- Servlet API, `HttpServletRequest`, `ResponseEntity` 등 HTTP 표현에 의존하지 않는다.
- 구현체 교체 가능성이 없는 Service에는 형식적인 인터페이스를 만들지 않는다.
- Service 간 순환 의존을 허용하지 않는다.

### 4.3 Repository

Repository는 영속성 조회·저장만 담당한다.

- 기본 DB 접근은 Spring Data JPA를 사용한다.
- 비즈니스 판단, 권한 검사, 외부 API 호출, Kafka 발행을 수행하지 않는다.
- 단순 조회는 파생 쿼리 메서드를 사용하되, 메서드 이름이 지나치게 길어지면 명시적 쿼리로
  전환한다.
- 조회 결과가 없을 수 있는 단건 조회는 `Optional<T>`를 반환한다.
- `Optional`을 Entity 필드, DTO 필드 또는 메서드 파라미터로 사용하지 않는다.

### 4.4 Infrastructure

- 외부 시스템별 설정, Client, 직렬화, 재시도 등의 세부 구현을 둔다.
- 도메인 Service는 가능한 한 구체 기술 대신 프로젝트가 정의한 컴포넌트나 인터페이스에
  의존한다.
- Kafka 토픽·이벤트 DTO·Producer 공통 설정은 역할 분배 문서의 소유권을 따른다.
- 추천 저장소와 Redis 키 등 아직 확정되지 않은 계약은 ADR과 데이터 계약이 확정된 뒤
  반영한다.

### 4.5 Spring 컴포넌트와 Lombok

- Spring 컴포넌트의 의존성은 생성자로 주입하고 필드를 `final`로 선언한다.
- Controller, Service, Handler와 Infrastructure Adapter의 생성자 주입에는
  `@RequiredArgsConstructor`를 사용할 수 있다.
- 로깅이 필요한 클래스에는 `@Slf4j`를 사용할 수 있다. 민감정보와 중복 예외 로깅 제한은
  12장을 따른다.
- DTO는 `record`를 기본으로 하므로 Lombok을 사용하지 않는다.
- `@Data`와 공개 `@Setter`는 사용하지 않는다.

## 5. DTO와 변환

### 5.1 DTO 구조와 이름

- 요청과 응답 DTO를 `dto/request`, `dto/response`로 분리한다.
- Java 21의 `record`를 기본으로 사용한다. 프레임워크 제약이나 가변 상태가 필요한 경우에만
  일반 클래스를 사용한다.
- 목적이 드러나는 이름을 사용한다.
  - 요청: `RatingCreateRequest`, `ProfileUpdateRequest`
  - 응답: `MovieDetailResponse`, `PartyInvitationResponse`
- `MovieDto`, `CommonDto`, `ResultDto`처럼 범위가 불분명한 이름을 사용하지 않는다.
- API 목적이 다르면 필드가 일부 같더라도 DTO를 무리하게 공용화하지 않는다.
- 요청 검증 애너테이션은 Request DTO에 선언한다.

### 5.2 변환 방식

- Entity 하나를 단순 변환하는 경우 Response DTO의 `from` 정적 팩토리를 사용한다.
- 여러 Entity나 조회 결과를 조립하는 복잡한 응답에만 도메인 내부 `mapper` 패키지와
  `{기능명}Mapper`를 추가한다.
- Mapper는 변환만 수행한다. DB 조회, 권한 검사, 상태 변경, 외부 호출을 수행하지 않는다.
- MapStruct는 기본 도구로 도입하지 않는다. 반복되는 기계적 매핑이 충분히 확인된 경우
  별도 합의와 ADR을 거쳐 도입한다.
- MyBatis `Mapper.xml`은 사용하지 않는다. DB 접근 방식 예외가 필요하면 9장의 기준을 따른다.

```java
public record RatingResponse(Long ratingId, BigDecimal score, String comment) {

    public static RatingResponse from(Rating rating) {
        return new RatingResponse(rating.getId(), rating.getScore(), rating.getComment());
    }
}
```

## 6. Entity 작성 규칙

- Entity는 `entity` 패키지에 두고 API 계층에 노출하지 않는다.
- Entity의 Lombok은 `@Getter`와
  `@NoArgsConstructor(access = AccessLevel.PROTECTED)`에 제한한다.
- Entity에 `@Data`, 공개 `@Setter`, `@Value`, `@EqualsAndHashCode`를 사용하지 않는다.
- JPA용 기본 생성자는 `protected`로 제한한다.
- 새 Entity 생성은 검증을 포함한 정적 팩토리 메서드를 우선한다.
- 상태 변경은 `setStatus`가 아니라 `accept`, `close`, `updateScore`처럼 의도가 드러나는
  메서드로 수행한다.
- Entity에 `@Builder`를 기본 적용하지 않는다. 필수값과 생성 규칙을 우회할 수 있기 때문이다.
- Enum은 `@Enumerated(EnumType.STRING)`으로 저장한다.
- 컬렉션 필드는 선언 시 빈 컬렉션으로 초기화한다.
- 시간은 시각에 `Instant`, 날짜 자체에 `LocalDate`를 사용한다. API에서는 UTC ISO 8601
  형식을 따른다.
- 모든 Entity에 공통 `id`, `status`, `deletedAt`을 강제하지 않는다. 복합키와 도메인별
  생명주기를 존중한다.
- 감사 컬럼이 필요한 Entity는 `global/entity`의 `BaseTimeEntity`(createdAt) 또는
  `BaseUpdatableEntity`(createdAt, updatedAt)를 선택해 상속한다. 모든 Entity에 강제하지
  않는다.

## 7. JPA 연관관계 규칙

- 모든 연관관계의 Fetch 전략을 명시하고 기본값은 `LAZY`로 한다.
- ERD에 관계가 있다는 이유만으로 양방향 연관관계를 만들지 않는다. 단방향을 우선한다.
- 양방향 관계가 필요하면 연관관계 편의 메서드에서 양쪽 상태를 함께 맞춘다.
- `CascadeType.ALL`과 `orphanRemoval = true`는 생명주기가 완전히 종속된 관계에만 사용하고
  MR에 이유를 설명한다.
- 외부 파이프라인이 함께 사용하는 테이블은 필요하면 JPA 연관관계 대신 식별자만 보관해
  결합도를 낮춘다. 구체 기준은 해당 데이터 계약을 따른다.
- 지연 로딩에 의존해 Controller나 JSON 직렬화 단계에서 쿼리를 실행하지 않는다.
- Entity를 가져온 뒤 반복문에서 연관 데이터를 조회하는 N+1 패턴을 피한다. 필요한 경우
  fetch join, EntityGraph, Projection 중 조회 목적에 맞는 방식을 선택한다.
- OSIV는 비활성화를 기본안으로 하며, 필요한 조회와 DTO 변환은 Service 트랜잭션 안에서
  완료한다. 최종 설정은 공통 기반 MR에서 검증한다.

## 8. 트랜잭션 규칙

- 트랜잭션 경계는 Controller나 Repository가 아니라 유스케이스 Service에 둔다.
- 조회 Service 또는 조회 메서드는 `@Transactional(readOnly = true)`를 사용한다.
- 생성·수정·삭제 메서드는 `@Transactional`을 사용한다.
- 하나의 유스케이스가 여러 Repository를 변경하면 최상위 Service 메서드가 전체
  트랜잭션을 소유한다.
- 평가·감상·프레임·팝콘처럼 여러 도메인에 걸친 단일 트랜잭션은 역할 분배 문서에서
  조정 Service와 변경 책임을 먼저 합의한다.
- 외부 HTTP 호출과 장시간 처리를 DB 트랜잭션 안에 포함하지 않는다.
- DB 저장과 Kafka 발행의 일관성·재시도 방식은 이벤트 계약에서 별도로 확정한다. 확정 전
  개별 도메인이 서로 다른 방식을 임의로 적용하지 않는다.
- checked exception을 포함한 롤백 정책을 기본값과 다르게 설정하면 이유를 코드와 MR에
  기록한다.

## 9. 조회 기술 선택 기준

기본 DB 접근은 JPA로 통일하되, JPA만 사용하기 위해 PostgreSQL 기능이나 대량 처리 성능을
포기하지 않는다.

| 상황 | 기본 선택 |
| --- | --- |
| 일반 CRUD·단순 조건 조회 | Spring Data JPA 파생 쿼리 |
| 고정된 조인·조회 최적화 | JPQL, fetch join, EntityGraph 또는 DTO Projection |
| 선택 조건이 조합되는 동적 조회 | Spring Data JPA Specification 우선 검토 |
| `pg_trgm`, `pgvector`, JSONB 등 PostgreSQL 기능 | Native Query |
| TMDb·MovieLens 대량 적재·일괄 갱신 | `JdbcTemplate.batchUpdate` 검토 |

- QueryDSL은 초기 기본 도구로 도입하지 않는다. Specification으로 유지하기 어려운 동적
  쿼리가 확인된 경우 팀 합의 후 도입한다.
- Native Query는 PostgreSQL 고유 기능 또는 명확한 성능 근거가 있을 때만 사용한다.
- JdbcTemplate은 대량 적재·일괄 처리처럼 JPA가 부적합한 작업에 제한한다.
- Native Query나 JdbcTemplate을 도입한 MR에는 JPA 방식이 부적합한 이유와 검증 방법을
  기록한다.
- 문자열 결합으로 SQL을 만들지 않고 파라미터 바인딩을 사용한다.
- 같은 기능에서 JPA와 직접 SQL로 같은 Entity를 변경할 때 영속성 컨텍스트 불일치를
  고려한다.

## 10. API 응답과 예외 처리

이 장은 Notion API 명세의 `API-000` 공통 규약을 저장소로 옮긴
`docs/api/README.md`의 응답·오류 규칙을 백엔드 구현 기준으로 채택한다. 이번 MR의 합의
범위는 성공 응답, HTTP 상태와 공통 오류 구조이며, `API-000`의 나머지 미정 사항은 별도
합의를 따른다.

- 공개 API 계약은 `docs/api/README.md`를 기준으로 하며 Controller가 임의의 응답 구조를
  만들지 않는다.
- 성공 응답은 공통 envelope로 감싸지 않고 Response DTO를 그대로 반환한다.
- 생성은 `201 Created`, 삭제 성공은 `204 No Content` 등 명세의 HTTP 상태를 사용한다.
- 오류는 공통 `ErrorResponse`와 의미 있는 `ErrorCode`로 통일한다.
- `ErrorCode`에는 HTTP 상태와 사용자용 한국어 메시지를 함께 정의하고 이름은
  `UPPER_SNAKE_CASE`로 작성한다.
- API 계약에 없는 공통 오류 코드를 추가할 때는 `docs/api/README.md`의 API-000 오류 코드
  표를 먼저 함께 갱신한다.
- Service는 비즈니스 예외를 발생시키고, 전역 예외 처리기가 HTTP 상태와 오류 응답으로
  변환한다.
- 예외 메시지 문자열을 Controller마다 직접 작성하지 않는다.
- 존재하지 않는 리소스와 권한상 노출하면 안 되는 리소스의 처리 차이는 API 명세를 따른다.

## 11. 도메인 간 참조와 공통 코드

- 각 도메인의 Entity와 Repository 소유권은 `backend-role-assignment.md`를 따른다.
- 다른 도메인의 Entity 필드를 직접 변경하지 않는다. 변경은 소유 도메인의 공개 메서드나
  합의한 유스케이스 경계를 사용한다.
- 다른 도메인의 Repository를 직접 사용해야 하는 교차 도메인 트랜잭션은 담당자끼리 먼저
  조정 Service와 의존 방향을 합의한다.
- 도메인 간 Service 호출로 순환 의존이 생기면 공통 Service를 추가해 숨기지 않고 유스케이스
  경계 또는 이벤트 방식으로 재설계한다.
- 공통 DTO, 오류 코드, DB Schema, Kafka 이벤트 스키마를 변경할 때는 역할 분배 및 AI 협업
  문서의 공통 계약 변경 절차를 따른다.
- `SecurityConfig`는 기본 정책을 "인증 필요"로 두고, 비회원도 호출할 수 있는 **공개**·**선택**
  구분의 API만 허용 목록에 등록한다. 규칙을 빠뜨린 새 API가 조용히 열리지 않도록 한 것이므로,
  도메인의 공개 API를 추가할 때는 `SecurityConfig`의 허용 경로에 함께 등록한다. 이 파일은
  전원이 공유하는 공통 파일이라 변경 전에 루트 `AGENTS.md` 5장에 따라 영향 범위를 알린다.

## 12. 로깅과 민감정보

- 비밀번호, 인증·재설정 토큰, OAuth 코드, JWT, 개인정보를 로그에 남기지 않는다.
- `System.out.println`을 사용하지 않고 표준 로깅 도구를 사용한다.
- 운영 흐름 추적에 필요한 식별자는 민감정보 대신 내부 요청 ID, 이벤트 ID, 리소스 ID를
  사용한다.
- 예외를 로그로 남길 때 같은 예외를 여러 계층에서 중복 기록하지 않는다.

## 13. AI 생성 코드 적용 규칙

AI가 생성한 코드도 이 문서의 규칙을 그대로 적용한다. AI가 확인할 기준 문서와 작업
절차는 `backend/AGENTS.md`가, 작업 시작·검증·완료 보고는 루트 `AGENTS.md`가, 충돌 방지
절차는 `ai-collaboration.md`가 정의한다.

## 14. 결정 대기 및 후속 확정 사항

아래 항목은 개발 시작 전 또는 해당 기능 착수 전에 팀 합의와 관련 문서 갱신이 필요하다.
확정 전 AI나 구현자가 임의로 선택하지 않는다.

테스트 요구 수준(플랜의 인수 테스트 목록)과 AI 에이전트 작업 절차는 `backend/AGENTS.md`에서 정의한다.

- Spring Boot 최종 버전과 라이브러리 호환성
- Flyway 도입과 마이그레이션 파일 소유·번호 관리 방식
- 평가 저장과 프레임·팝콘 적립의 조정 Service
- DB 저장과 Kafka 이벤트 발행 보장 방식
- Kafka 이벤트 스키마와 발행 시점
- Spark 추천 결과 저장소·스키마 및 Spring 조회 계약
- Redis 키·TTL·직렬화 계약
- 동시성 제어가 필요한 유스케이스와 잠금 방식
- 테스트 작성 기준(커버리지, 슬라이스 테스트 범위 등)

결정이 확정되면 단순 구현 선택은 이 문서에, 핵심 기술·아키텍처 결정은 ADR에, API 계약은
API 명세에, 데이터 구조는 ERD 또는 별도 데이터 계약 문서에 반영한다.
