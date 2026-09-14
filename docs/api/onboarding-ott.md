# API 명세 — 온보딩·OTT

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-023](#api-023-초기-취향-카드-목록) | 초기 취향 카드 목록 | GET | `/onboarding/cards` | 1차 MVP | 회원 |
| [API-024](#api-024-초기-취향-제출) | 초기 취향 제출 | POST | `/onboarding/preferences` | 1차 MVP | 회원 |
| [API-025](#api-025-ott-목록-조회) | OTT 목록 조회 | GET | `/otts` | 1차 MVP | 공개 |
| [API-026](#api-026-내-구독-ott-조회) | 내 구독 OTT 조회 | GET | `/users/me/otts` | 1차 MVP | 회원 |
| [API-027](#api-027-내-구독-ott-설정) | 내 구독 OTT 설정 | PUT | `/users/me/otts` | 1차 MVP | 회원 |

## API-023 초기 취향 카드 목록

`GET` `/api/v1/onboarding/cards`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-02 |
| 화면 | ④ 취향 초기 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

온보딩에서 좋아요·싫어요를 선택할 영화 카드 목록을 내려준다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `size` (integer, 기본 20, 최대 40)

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "movieId": 550,
      "title": "파이트 클럽",
      "posterUrl": "https://image.tmdb.org/t/p/w500/...",
      "releaseYear": 1999,
      "genres": ["드라마", "스릴러"],
      "flavorCode": "FLAVOR_X"
    }
  ]
}
```

### 오류 응답

- `400 VALIDATION_FAILED` `size`가 1~40을 벗어남
- `409 ONBOARDING_ALREADY_COMPLETED` 이미 온보딩을 마친 사용자. 온보딩은 재실행하지 않는다

### 처리 규칙

- 카드 수는 `size`이며 기본 20, 최대 40이다
- 8개 팝콘 맛이 고루 포함되도록 카드를 구성한다. 맛마다 인기 순위를 매겨 1순위를 한 장씩 깔고 다음 순위로
  넘어가는 방식이며, 어떤 맛의 후보가 모자라면 남은 자리를 다른 맛의 다음 순위가 채운다
- 인기도는 서비스 평가 수(`movies.rating_count`) 내림차순, 평균 별점 내림차순, 식별자 오름차순으로 판단한다.
  같은 요청은 항상 같은 순서를 반환한다
- 대표 맛이 배정되지 않은 영화(`movies.representative_flavor_id`가 비어 있음)는 카드가 되지 않는다.
  대표 맛 배정은 [ADR-0015](../adr/0015-kmeans-ctfidf-taste-clusters.md)의 파이프라인이 수행한다
- 온보딩을 마친 사용자는 카드를 받지 못한다. 제출만 막으면 카드를 고른 뒤에야 거절당한다

### 상태값

없음

### 미정 사항

없음

## API-024 초기 취향 제출

`POST` `/api/v1/onboarding/preferences`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-02 |
| 화면 | ④ 취향 초기 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

카드에 대한 LIKE/DISLIKE 선택 결과를 저장하고 온보딩을 완료 처리한다. `차차 찾아갈래요`로 건너뛸 수도 있다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{
  "preferences": [
    { "movieId": 550, "preference": "LIKE" },
    { "movieId": 680, "preference": "DISLIKE" }
  ],
  "skipped": false
}
```

- 제외한 카드는 배열에 포함하지 않는다
- `skipped: true`이면 `preferences`는 빈 배열
- `movieId`는 JSON 정수, `preference`는 문자열, `skipped`는 boolean이다. 다른 JSON 타입은 값이 바뀔 수 있어
  변환하지 않고 거절한다

### Response

`200 OK`

```json
{ "onboardingCompleted": true, "savedCount": 2, "skipped": false }
```

### 오류 응답

- `400 VALIDATION_FAILED` `preference` 값 오류, 중복 `movieId`, 존재하지 않는 `movieId`,
  `preferences`·`skipped` 누락, `skipped: true`인데 `preferences`가 비어 있지 않음, 필드의 JSON 타입 불일치
- `409 ONBOARDING_ALREADY_COMPLETED`

### 처리 규칙

- 명시적으로 선택한 값만 저장한다. 제외 카드는 저장하지 않는다
- `preferences`와 `skipped`는 모두 필수다. 빠진 필드를 기본값으로 채우면 건너뛰기 의사를 밝히지 않은 빈 제출이
  온보딩을 완료해 버린다
- 필드의 JSON 타입이 다르면 변환하지 않고 거절한다. `preference: 0`을 선언 순서로 읽거나 `movieId: 101.9`의
  소수점 아래를 버리면 보낸 값과 저장한 값이 다른데도 `200`이 나간다
- 온보딩 LIKE/DISLIKE는 감상 후 평가(0.5~5.0점, 0.5점 단위)와 별도 테이블에 저장하며 프레임을 생성하거나 팝콘 버킷 집계에 포함하지 않는다
- 저장과 온보딩 완료 전이는 한 트랜잭션이며, 같은 사용자의 동시 제출은 계정 행 잠금으로 직렬화해 하나만 성공한다.
  실패한 요청은 아무것도 저장하지 않고 `409`를 받는다
- 저장한 선택이 한 건 이상이면 [이벤트·Redis 계약](./streaming-contract.md)의 `ONBOARDING` 이벤트를 같은
  트랜잭션의 아웃박스에 기록한다. 건너뛴 제출은 Consumer가 읽을 행이 없으므로 발행하지 않는다
- 저장 후 초기 추천(API-045)이 가능하도록 추천 입력 버전을 갱신한다. 추천 세트 저장이 들어오기 전까지는
  `ONBOARDING` 이벤트 발행이 이 역할을 대신한다

### 상태값

- OnboardingPreference: `LIKE`, `DISLIKE`

### 미정 사항

없음

## API-025 OTT 목록 조회

`GET` `/api/v1/otts`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-03, FR-16 |
| 화면 | ④-1 OTT 설정, ⑪-5 OTT 관리 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

서비스가 지원하는 OTT 기준정보를 내려준다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    { "ottId": 1, "code": "NETFLIX", "name": "넷플릭스", "logoUrl": "https://..." }
  ]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 지원 OTT는 다섯 개이며 TMDb watch provider와 다음과 같이 매핑한다.

  | `code` | 이름 | TMDb watch provider id |
  |---|---|---|
  | `NETFLIX` | 넷플릭스 | 8 |
  | `DISNEY_PLUS` | 디즈니+ | 337 |
  | `WATCHA` | 왓챠 | 97 |
  | `WAVVE` | 웨이브 | 356 |
  | `TVING` | 티빙 | 1883 |

- 행의 적재와 갱신은 콘텐츠 적재 경로가 승인된 매핑 CSV(`backend/scripts/samples/ott-provider-mapping.csv`)를
  기준으로 수행한다. 목록에 추가하거나 이름·로고를 바꾸려면 이 표와 CSV를 함께 고친다
- 적재 경로는 추가와 갱신(UPSERT)만 자동화한다. 지원 목록에서 OTT를 빼는 절차는 정해져 있지 않다.
  CSV에서 줄을 지워도 `otts` 행은 남고, 구독 중인 사용자가 있으면 `user_otts`의 외래키가 삭제를 막는다
- `ottId` 오름차순으로 정렬한다. 식별자가 적재 CSV의 순서를 따르므로 재조회해도 화면의 OTT 순서가 바뀌지 않는다
- 기준정보이므로 캐시 가능. 응답에 사용자별 값이 없어 공유 캐시에도 담을 수 있다(`Cache-Control`에
  `max-age=86400`과 `public`. 지시자 순서는 의미가 없다)

### 상태값

없음

### 미정 사항

없음

## API-026 내 구독 OTT 조회

`GET` `/api/v1/users/me/otts`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-03 |
| 화면 | ⑪-5 OTT 관리 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

사용자가 구독 중으로 설정한 OTT 목록을 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{ "ottIds": [1, 3] }
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 설정하지 않았으면 빈 배열
- 응답의 `ottIds`는 오름차순이다

### 상태값

없음

### 미정 사항

없음

## API-027 내 구독 OTT 설정

`PUT` `/api/v1/users/me/otts`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-03 |
| 화면 | ④-1 OTT 설정, ⑪-5 OTT 관리 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

구독 OTT 목록을 전체 교체한다. 온보딩과 설정 화면에서 공통으로 사용한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "ottIds": [1, 3] }
```

### Response

`200 OK`, API-026과 동일 본문

### 오류 응답

- `400 VALIDATION_FAILED` 존재하지 않는 `ottId`

### 처리 규칙

- 멱등. 빈 배열이면 전체 해제
- 요청의 중복 `ottId`는 제거하고 저장하므로 응답 길이가 요청보다 짧을 수 있다
- 응답의 `ottIds`는 API-026과 같은 오름차순이다
- 한 번에 보낼 수 있는 `ottId`는 50개까지이며 초과하면 `VALIDATION_FAILED`다. 지원 OTT 수보다 큰
  방어값이며 중복 제거 전의 요청 원소 수로 판단한다
- 변경 시 영화 상세의 OTT 정렬(FR-16)과 검색 필터 기본값에 반영된다

### 상태값

없음

### 미정 사항

없음
