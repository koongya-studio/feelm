# API 명세 — 감상

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-033](#api-033-ott-링크-클릭-기록) | OTT 링크 클릭 기록 | POST | `/movies/{movieId}/ott-clicks` | 1차 MVP | 회원 |
| [API-034](#api-034-감상-기록-목록) | 감상 기록 목록 | GET | `/watch-records` | 1차 MVP | 회원 |
| [API-035](#api-035-감상-확인-응답) | 감상 확인 응답 | POST | `/watch-records/{watchRecordId}/confirm` | 1차 MVP | 회원 |
| [API-036](#api-036-평가-생성) | 평가 생성 | POST | `/movies/{movieId}/ratings` | 1차 MVP | 회원 |
| [API-037](#api-037-평가-수정) | 평가 수정 | PUT | `/ratings/{ratingId}` | 1차 MVP | 회원 |
| [API-038](#api-038-평가-삭제) | 평가 삭제 | DELETE | `/ratings/{ratingId}` | 1차 MVP | 회원 |
| [API-039](#api-039-내-평가-목록) | 내 평가 목록 | GET | `/users/me/ratings` | 1차 MVP | 회원 |

## API-033 OTT 링크 클릭 기록

`POST` `/api/v1/movies/{movieId}/ott-clicks`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-17 |
| 화면 | ⑥-2 영화 상세 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

OTT 보러가기 클릭을 `LINK_CLICKED` 감상 기록으로 남긴다. 클라이언트는 응답과 무관하게 외부 시청 페이지를 연다.

### 인증 및 권한

- 회원. 비회원 클릭은 기록하지 않는다

### Path Parameters

- `movieId`

### Query Parameters

없음

### Request Body

```json
{ "ottId": 1 }
```

### Response

`201 Created`

활성 감상 기록을 새로 만들지 않는 재클릭과 기존 감상 완료 상태 유지에는 `200 OK`를 반환한다.

```json
{
  "watchRecordId": 5001,
  "movieId": 550,
  "status": "LINK_CLICKED",
  "clickedAt": "2026-08-30T05:21:00Z",
  "confirmDueAt": "2026-09-01T05:21:00Z",
  "expiresAt": "2026-09-06T05:21:00Z"
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND` 영화 또는 OTT 없음

### 처리 규칙

- 링크 클릭은 감상 확정이 아니며 프레임을 생성하거나 팝콘 버킷 집계에 포함하지 않는다
- 같은 영화에 활성 `LINK_CLICKED` 기록이 이미 있으면 새로 만들지 않고 클릭한 `ottId`와 `clickedAt`·`confirmDueAt`·`expiresAt`을 갱신한다. 응답은 `200`
- 이미 `WATCHED_UNRATED` 또는 `RATED`인 영화는 상태·감상 완료 시각·평가 연결을 바꾸지 않고 클릭 로그만 남긴 뒤 기존 기록을 `200`으로 반환한다
- `EXPIRED` 기록이 있으면 새 `LINK_CLICKED` 기록을 만든다
- 성공한 실제 API 호출마다 `OTT_CLICK` 이벤트를 `feelm.ott-click.v1` 아웃박스에 저장한다. payload는 `movieId`, 요청 `ottId`, 처리된 `watchRecordId`만 포함한다
- `OTT_CLICK`은 행동 Feature 집계에 사용하는 약한 신호다. 클릭만으로 추천 `inputVersion`을 증가시키거나 현재 추천 세트 버퍼를 무효화하지 않고, Spark 집계 후 다음 추천 세트 생성 때 반영한다

### 상태값

- WatchStatus `LINK_CLICKED`

### 미정 사항

없음. 재클릭 저장과 클릭 분석 활용 범위는 위 처리 규칙 및 [`streaming-contract.md`](./streaming-contract.md)의 `OTT_CLICK` 계약으로 확정한다.

## API-034 감상 기록 목록

`GET` `/api/v1/watch-records`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-18 |
| 화면 | ⑥-3 평가 알림, ⑪-4 평가 안 남긴 영화 |
| 미정 사항 | 없음 |
| 비고 | confirmDue=true로 감상 확인 대상 조회 |

### 설명

내 감상 기록을 상태별로 조회한다. 접속 시 감상 확인 대상(`confirmDue=true`)과 평가 안 남긴 영화(`status=WATCHED_UNRATED`)를 이 API로 가져온다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `status` (string[], 선택): 반복 지정 가능. `LINK_CLICKED`, `WATCHED_UNRATED`, `RATED`, `EXPIRED`만 허용하며 중복은 제거한다
- `confirmDue` (boolean, 선택): 정확한 소문자 `true` 또는 `false`. `true`면 `LINK_CLICKED` 중 확인 시각 도달·만료 시각 미도달 건만 조회한다
- `cursor` (string, 선택): 서버가 직전 응답에서 발급한 불투명 커서
- `size` (integer, 선택): 기본 20, 최소 1, 최대 50

배열은 `status=LINK_CLICKED&status=RATED`처럼 같은 파라미터 이름을 반복해 전달한다.
인덱스·중첩 표기(`status[0]`, `status[].name` 등)는 지원하지 않는다.

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "watchRecordId": 5001,
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
      "status": "LINK_CLICKED",
      "ott": { "ottId": 1, "code": "NETFLIX" },
      "clickedAt": "2026-08-28T05:21:00Z",
      "confirmDueAt": "2026-08-30T05:21:00Z",
      "expiresAt": "2026-09-04T05:21:00Z",
      "watchedAt": null,
      "ratingId": null
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

### 오류 응답

- `400 VALIDATION_FAILED` 잘못된 `status`, `confirmDue`, `size`, 손상되거나 조회 조건과 맞지 않는 `cursor`
- `400 VALIDATION_FAILED` 인덱스·중첩 파라미터 표기, 또는 허용되지 않는 문자가 든 파라미터 이름. 조건을 무시하고 전체 목록을 반환하지 않는다
- `401 UNAUTHORIZED` 인증이 없거나 활성 사용자가 아님
- `500 INTERNAL_ERROR` 만료 반영 또는 목록 조회 실패

### 처리 규칙

- 기본 정렬은 `clickedAt` 내림차순이며, `clickedAt`이 `null`인 기록은 뒤에 배치하고 동일 시각은 `watchRecordId` 내림차순으로 정렬한다 (`ORDER BY clicked_at DESC NULLS LAST, id DESC`)
- 조회 트랜잭션 안에서 본인의 `LINK_CLICKED AND expires_at <= now` 기록을 먼저 `EXPIRED`로 저장하고 `expiredAt`을 기록한 뒤 목록을 조회한다. `expires_at == now`도 만료다
- `confirmDue=true`의 정확한 조건은 `status=LINK_CLICKED AND confirm_due_at <= now AND expires_at > now`다. 따라서 확인 시각과 정확히 같으면 대상이고, 만료 시각과 정확히 같으면 대상이 아니다
- 커서는 사용자와 정규화된 `status`·`confirmDue` 조건에 묶여 다른 사용자나 다른 필터에서 재사용할 수 없다. 다음 페이지에서 `size`를 바꾸는 것은 허용한다
- 평가는 `ratingId` 조회에만 결합하며 이 API가 평가·한줄평·프레임·팝콘 데이터를 변경하지 않는다

### 상태값

- WatchStatus 참조

### 미정 사항

없음

## API-035 감상 확인 응답

`POST` `/api/v1/watch-records/{watchRecordId}/confirm`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-18 |
| 화면 | ⑥-3 평가 알림 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

'감상하셨나요?' 질문에 대한 응답을 기록하고 상태를 전이한다.

### 인증 및 권한

- 회원. 본인 기록만

### Path Parameters

- `watchRecordId`

### Query Parameters

없음. 인덱스·중첩 표기(`x[0]` 등)를 붙이면 `400 VALIDATION_FAILED`다. 본문이나 `Content-Type`도 함께 잘못된 경우에는 그쪽 오류가 먼저 나간다.

### Request Body

```json
{ "watched": true }
```

### Response

`200 OK`

```json
{ "watchRecordId": 5001, "status": "WATCHED_UNRATED", "watchedAt": "2026-08-30T05:21:00Z" }
```

- `watched: false`이면 `status: "EXPIRED"`

### 오류 응답

- `400 VALIDATION_FAILED` `watchRecordId`가 양의 64비트 정수가 아니거나 `watched`가 JSON boolean이 아님. 문자열·숫자·배열·객체·`null`·누락·깨진 JSON은 허용하지 않는다
- `400 VALIDATION_FAILED` 인덱스·중첩 파라미터 표기, 또는 허용되지 않는 문자가 든 파라미터 이름
- `401 UNAUTHORIZED` 인증이 없거나 활성 사용자가 아님
- `404 RESOURCE_NOT_FOUND` 기록이 없거나 다른 사용자의 기록
- `409 INVALID_WATCH_STATUS` `LINK_CLICKED`가 아니거나 `expires_at <= now`인 기록, 중복 또는 경쟁 상태 전이
- `415 UNSUPPORTED_MEDIA_TYPE` `Content-Type` 누락 또는 비지원
- `500 INTERNAL_ERROR` 상태 전이 저장 또는 commit 실패

### 처리 규칙

- `watched: true` → `WATCHED_UNRATED`, 평가 요청 UI로 이어진다. 평가는 지금 하지 않고 나중에 남겨도 된다
- `watched: false` → `EXPIRED`. 만료는 추천 신호로 사용하지 않는다
- 클릭 후 2일이 지나지 않아도 사용자가 직접 응답하면 받아들인다. 2일은 앱에서 확인 질문을 노출하는 기준이지 상태 전이 제한이 아니다
- `expires_at == now`는 만료로 보아 확인을 거절한다
- 잠금 순서는 OTT 클릭 API와 동일하게 `active user → owned watch record`이며 조건부 update가 정확히 1행이 아니면 경쟁 상태로 거절한다
- 성공하더라도 Kafka·outbox 이벤트를 새로 만들지 않고 평가·한줄평·프레임·팝콘 데이터를 변경하지 않는다
- 알 수 없는 JSON 필드는 향후 호환성을 위해 허용한다

### 상태값

- WatchStatus `LINK_CLICKED` → `WATCHED_UNRATED` 또는 `EXPIRED`

### 미정 사항

없음

## API-036 평가 생성

`POST` `/api/v1/movies/{movieId}/ratings`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-19, FR-26, FR-07, FR-09 |
| 화면 | ⑥-4 평가, ⑪-3 평가 |
| 미정 사항 | 있음 |
| 비고 | 프레임 생성 및 팝콘 버킷 재집계 트리거 |

### 설명

영화에 0.5~5.0점 평가(0.5점 단위)와 선택적 한줄평을 남긴다. 최초 저장 시 프레임을 생성해 필름에 추가하며, 이 평가는 영화의 대표 맛을 기준으로 팝콘 버킷 집계에 반영된다.

### 인증 및 권한

- 회원

### Path Parameters

- `movieId`

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `score` | number | O | 0.5~5.0, 0.5점 단위 (`minimum: 0.5`, `maximum: 5`, `multipleOf: 0.5`) |
| `comment` | string | X | 한줄평, 200자 이하 |
| `visibility` | string | comment 있으면 O | `PUBLIC` 또는 `PRIVATE` |
| `spoiler` | boolean | X | 한줄평 스포일러 여부. 생략 시 `false`; `true`는 내용 있는 `comment`에서만 허용 |

```json
{ "score": 4.5, "comment": "밤에 보면 안 됨", "visibility": "PUBLIC", "spoiler": true }
```

### Response

`201 Created`

```json
{
  "ratingId": 91,
  "movieId": 550,
  "score": 4.5,
  "comment": "밤에 보면 안 됨",
  "visibility": "PUBLIC",
  "spoiler": true,
  "frame": { "frameId": 77, "createdAt": "2026-08-30T05:21:00Z" },
  "popcorn": { "flavorCode": "FLAVOR_A", "isNewFlavor": true, "flavorCount": 1 },
  "watchStatus": "RATED",
  "createdAt": "2026-08-30T05:21:00Z"
}
```

### 오류 응답

- `400 VALIDATION_FAILED` 점수 범위·0.5점 단위 위반, 200자 초과, `comment` 있는데 `visibility` 없음, 내용 없는 `comment`에 `spoiler: true`, `spoiler`의 `null`·boolean 외 값
- `404 RESOURCE_NOT_FOUND`
- `409 RATING_ALREADY_EXISTS` 사용자·영화 조합에 평가가 이미 있음. 수정은 API-037

### 처리 규칙

- 사용자·영화 조합마다 평가는 하나다
- `score`는 JSON 숫자로 받고 0.5~5.0 범위와 0.5의 배수 조건을 모두 검증한다. 4.49 같은 값은 반올림·절삭하지 않고 거절한다. Java에서는 `BigDecimal`로 원값을 검증·전달한다
- 평가 조회(API-030·032·039), 프레임(API-042·043), 공개 프로필(API-062)의 개인 평가 `score`도 같은 숫자 규칙을 따른다. 평균 평점과 추천 예측 점수에는 0.5 배수 조건을 적용하지 않는다
- 평가 저장·프레임 생성·감상 기록 `RATED` 전이는 하나의 트랜잭션으로 처리한다(NFR-06)
- 응답의 `popcorn`은 별도 행이 아니라 `ratings JOIN movies`로 계산한 현재 집계 결과다
- 감상 기록이 없거나 `LINK_CLICKED`인 영화도 평가할 수 있으며, 이때 감상 기록을 `RATED`로 생성·전이한다 (D-6 결정)
- `spoiler`를 생략하면 `false`로 저장하고, 명시적 `true`·`false`는 그대로 저장해 응답에도 항상 boolean으로 반환한다. `null`·문자열·숫자·배열·객체는 허용하지 않는다
- `spoiler: true`는 `comment`가 null·빈 문자열·공백 또는 화면에 보이지 않는 제어·서식 문자만 있는 경우 거절한다. 스포일러 검증을 위해 한줄평 원문을 변경하지 않는다. `spoiler: false`의 기존 한줄평·공개 범위 규칙은 그대로다
- 스포일러 표시는 한줄평의 공개 범위나 정렬을 바꾸지 않는다. API-032는 원문과 표시값을 반환하며, 타인의 스포일러 한줄평 블러·해제는 FE 로컬 상태다
- 저장 후 취향 분석(FR-04)과 추천 입력 버전을 갱신하고 추천 캐시를 무효화한다. 현재 노출 중인 추천 세트 전체는 무효화하지 않으며 갱신된 입력은 다음 세트 생성에 반영한다
- 평가 저장은 추천 보충과 별도 트랜잭션으로 유지한다. 다음 기본 추천 조회(API-045)가 현재 노출 카드의 평가·관심없음 상태를 다시 확인해 빈 슬롯만 보충하며, 추천 장애가 평가 저장을 롤백하지 않는다
- 한줄평은 평가에 종속되므로 별도 리소스가 아니다

### 상태값

- WatchStatus → `RATED`
- Visibility

### 미정 사항

- `isNewFlavor`는 해당 평가 생성 후 사용자의 같은 대표 맛 평가가 정확히 1건이면 `true`다. 감상량 임계값이나 발견 추천(FR-12) 판정과는 무관하다.
- [ ] 발견 표시(FR-32, 확장) 응답 필드 예약 여부

## API-037 평가 수정

`PUT` `/api/v1/ratings/{ratingId}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-19, FR-26 |
| 화면 | ⑥-4 평가, ⑪-3 평가 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

점수·한줄평·공개 범위·스포일러 표시를 전체 교체한다. 한줄평을 지우려면 `comment: null`을 보낸다.

### 인증 및 권한

- 회원. 본인 평가만

### Path Parameters

- `ratingId`

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `score` | number | O | 0.5~5.0, 0.5점 단위 (`minimum: 0.5`, `maximum: 5`, `multipleOf: 0.5`) |
| `comment` | string | X | 한줄평, 200자 이하. 생략 또는 `null`은 기존 한줄평 삭제 |
| `visibility` | string | comment 있으면 O | `PUBLIC` 또는 `PRIVATE` |
| `spoiler` | boolean | X | 생략 시 `false`. 내용 있는 comment에서만 `true` 가능 |

```json
{ "score": 0.5, "comment": null, "visibility": "PRIVATE", "spoiler": false }
```

### Response

`200 OK`, API-036과 동일 본문(`frame`은 기존 값, `popcorn`은 변경 후 집계값)

### 오류 응답

- `400 VALIDATION_FAILED` 경로 ID, 점수 범위·0.5점 단위, 200자 초과, comment 있는데 visibility 없음, 내용 없는 comment에 spoiler true, spoiler의 null·boolean 외 값, 잘못된 JSON
- `401 UNAUTHORIZED` 인증 정보가 없거나 활성 사용자가 아님
- `404 RESOURCE_NOT_FOUND` 타인 평가 포함
- `415 UNSUPPORTED_MEDIA_TYPE` Content-Type 누락 또는 비지원
- `500 INTERNAL_ERROR` 평가 또는 연관 데이터 저장 실패

### 처리 규칙

- 프레임은 최초 저장 시 한 번만 생성한다. 점수 수정은 팝콘 수를 바꾸지 않지만 평균 평점 집계에는 반영된다
- 요청 필드와 점수 범위·0.5점 단위 검증은 API-036과 동일하다. 잘못된 값을 자동으로 보정하지 않는다
- `spoiler` 생략은 false로 저장하고 응답에도 항상 boolean을 반환한다. 명시적 null·boolean 외 값은 거절한다. true는 화면에 보이는 내용의 comment가 있을 때만 허용하며, 원문은 검증을 위해 바꾸지 않는다
- 수정 시 평가·프레임 ID와 생성 시각을 유지하고, 영화 평균 별점·평가 수를 실제 평가에서 재계산한다. `RATING UPDATE`(변경 후 점수)를 같은 PostgreSQL 트랜잭션의 아웃박스에 기록한다
- 이미 저장된 점수·한줄평·공개 범위·스포일러 표시와 완전히 같은 PUT은 수정 시각을 바꾸거나 이벤트를 추가하지 않는다
- 본인 평가만 수정할 수 있다. 타인 평가와 없는 평가는 모두 404로 처리한다
- 점수 변경은 평가·영화 평점 집계와 `RATING UPDATE` 아웃박스에 반영된다. 맛 선호도·취향 분석 캐시의 동기 갱신은 400 writer 연동 후 통합 검증한다
- `PUBLIC` → `PRIVATE`로 바꾸면 한줄평 목록(API-032)에서 즉시 제외된다

### 상태값

없음

### 미정 사항

- [ ] 400 취향 캐시 writer와 연동 후 동기 갱신·원자성을 통합 검증한다

## API-038 평가 삭제

`DELETE` `/api/v1/ratings/{ratingId}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-19, FR-07, FR-09, FR-18 |
| 화면 | ⑪-3 평가 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

평가를 삭제한다. 연결된 프레임도 함께 삭제되고 팝콘 버킷 집계에서 해당 평가가 제외된다.

### 인증 및 권한

- 회원. 본인 평가만

### Path Parameters

- `ratingId`

### Query Parameters

없음

### Request Body

없음

### Response

`204 No Content`

### 오류 응답

- `400 VALIDATION_FAILED` 잘못된 경로 ID
- `401 UNAUTHORIZED` 인증 정보가 없거나 활성 사용자가 아님
- `404 RESOURCE_NOT_FOUND` 타인 평가 포함
- `500 INTERNAL_ERROR` 평가 또는 연관 데이터 삭제 실패

### 처리 규칙

- 취향 분석 캐시의 동기 갱신·원자성은 400 writer가 연결된 뒤 통합 인수 조건으로 검증한다. 213 범위에서는 평가·프레임·감상·영화 집계·아웃박스의 PostgreSQL 트랜잭션을 처리한다
- 평가 삭제와 프레임 FK CASCADE, 영화 평균 별점·평가 수 재계산, `RATING DELETE`(점수 없음) 아웃박스 기록은 같은 PostgreSQL 트랜잭션에서 처리한다. 마지막 평가가 삭제되면 평균 별점은 `0.00`, 평가 수는 `0`이다
- 감상 기록은 삭제하지 않고 `WATCHED_UNRATED`로 되돌리며 `rated_at`만 비운다(FR-18). 기존 감상 시각은 유지하고 추천에서는 계속 제외된다. 평가에 감상 기록 연결이 없었다면 새 감상 기록을 만들지 않는다
- 본인 평가만 삭제할 수 있다. 타인 평가와 없는 평가는 모두 404로 처리한다
- 삭제를 재요청하면 404를 반환하되 삭제 상태를 유지하고 이벤트를 추가하지 않는다
- 삭제 후 같은 영화에 다시 평가하면 새 프레임이 생성되고 팝콘 버킷 집계에 다시 포함된다

### 상태값

- WatchStatus `RATED` → `WATCHED_UNRATED`

### 미정 사항

- [ ] 삭제로 어떤 맛의 팝콘이 0이 되면 `새로운 맛 발견` 표시(FR-32)도 취소할지
- [ ] 400 취향 캐시 writer와 연동 후 위 캐시 갱신·원자성을 통합 검증한다

## API-039 내 평가 목록

`GET` `/api/v1/users/me/ratings`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-19 |
| 화면 | ⑪-3 평가 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

내가 남긴 평가와 감상 확인 후 아직 평가하지 않은 영화를 조회한다. 필터를 생략한 기존 호출은 내가 남긴 평가만 최신순으로 반환한다. 명시적 `ALL`은 두 종류를 한 페이지에서 전역 정렬한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `filter`: 생략 시 평가만(`RATED`), `ALL`(평가+미평가), `COMMENTED`(내용 있는 본인 한줄평), `UNRATED`(감상 확인 후 평가하지 않은 영화)
- `sort`: 생략 시 `LATEST`; `LATEST`(최신순), `SCORE_DESC`(별점 높은 순), `SCORE_ASC`(별점 낮은 순), `OLDEST`(오래된 순)
- `cursor`: 서버가 직전 응답에서 발급한 불투명 문자열. 사용자·filter·sort에 귀속되며 size는 바꿀 수 있다.
- `size`: 기본 20, 1~50
- 알 수 없는 값, 중복·빈 query 값, 훼손되거나 범위가 다른 cursor는 `400 VALIDATION_FAILED`

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "itemType": "RATED",
      "ratingId": 91,
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
      "score": 5,
      "comment": "밤에 보면 안 됨",
      "visibility": "PUBLIC",
      "spoiler": false,
      "frameId": 77,
      "createdAt": "2026-08-30T05:21:00Z",
      "activityAt": "2026-08-30T05:21:00Z"
    }
  ],
  "nextCursor": "...",
  "hasNext": true
}
```

`filter=ALL` 또는 `filter=UNRATED`의 미평가 항목 예시:

```json
{
  "itemType": "UNRATED",
  "ratingId": null,
  "movie": { "movieId": 551, "title": "인터스텔라", "posterUrl": null },
  "score": null,
  "comment": null,
  "visibility": null,
  "spoiler": null,
  "frameId": null,
  "createdAt": null,
  "activityAt": "2026-09-17T01:00:00Z"
}
```

평가 항목은 `ratingId`, `score`, `spoiler`, `createdAt`이 필수이며 `comment`, `visibility`, `frameId`는 null일 수 있다. 과거 데이터의 프레임이 없어도 평가는 반환하고 `frameId=null`로 표시한다. 미평가 항목에는 평가 전용 값을 꾸며내지 않는다. `activityAt`은 정렬에 사용한 시각으로, 불완전한 과거 감상 기록에서는 null일 수 있다. 평가 항목의 `activityAt`은 `createdAt`과 같다.

### 오류 응답

- `400 VALIDATION_FAILED`: 잘못된 filter·sort·size·cursor
- `401 UNAUTHORIZED`: 인증이 없거나 유효하지 않거나 활성 회원이 아님
- `401 TOKEN_EXPIRED`: Access Token 만료
- `500 INTERNAL_ERROR`: 조회 실패

### 처리 규칙

- 필름(API-042)과 1:1이지만, 평가 목록은 점수·한줄평 관리 관점, 필름은 시각화 관점으로 분리한다
- 본인의 PUBLIC·PRIVATE 평가를 모두 조회하고 본인 스포일러 한줄평 원문을 반환한다. 타인의 평가·감상 기록은 반환하지 않는다.
- `COMMENTED`는 null·Unicode 공백만 있는 한줄평을 제외한다. 공개 범위와 스포일러 여부는 이 필터에 영향을 주지 않는다.
- `UNRATED`는 현재 `WATCHED_UNRATED` 감상 기록만 포함한다. `LINK_CLICKED`·`EXPIRED`는 포함하지 않는다. 같은 영화에 평가가 존재하면 미평가로 중복 노출하지 않는다.
- `LATEST`·`OLDEST`는 평가의 `created_at`과 미평가 감상의 `watched_at`을 비교한다. `watched_at`이 없으면 남아 있는 `rated_at`, 그마저 없으면 `clicked_at` 순으로 fallback한다. 평가 삭제 시 `rated_at`이 지워지는 직접 평가 이력처럼 시각이 모두 없으면 마지막에 둔다. 동률은 평가 우선·항목 ID 내림차순이다.
- `SCORE_DESC`·`SCORE_ASC` 모두 평가를 점수순으로 먼저 정렬하고 점수 없는 미평가는 뒤에 둔다. 점수가 같거나 미평가끼리는 `activityAt DESC NULLS LAST`, 평가 우선, 항목 ID 내림차순으로 고정한다.
- 서버에서 `size+1`개를 조회해 `hasNext`를 정하고, 마지막 페이지의 `nextCursor`는 null이다. 페이지 간 데이터 변경에 대한 고정 snapshot은 보장하지 않는다.

### 상태값

없음

### 미정 사항

없음
