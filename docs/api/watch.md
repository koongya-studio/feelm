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
| 미정 사항 | 있음 |
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

- 링크 클릭은 감상 확정이 아니며 프레임·팝콘을 생성하지 않는다
- 같은 영화에 `LINK_CLICKED` 기록이 이미 있으면 새로 만들지 않고 `clickedAt`·`confirmDueAt`·`expiresAt`을 갱신한다(가정). 응답은 `200`
- 이미 `WATCHED_UNRATED` 또는 `RATED`인 영화는 상태를 바꾸지 않고 클릭 로그만 남긴 뒤 기존 기록을 `200`으로 반환한다
- `EXPIRED` 기록이 있으면 새 `LINK_CLICKED` 기록을 만든다

### 상태값

- WatchStatus `LINK_CLICKED`

### 미정 사항

- [ ] 재클릭 시 기존 기록 갱신(가정) vs 별건 기록
- [ ] 클릭 로그의 분석 활용 범위(OTT별 전환율 등)

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

- `status` (string[], 선택): `LINK_CLICKED`, `WATCHED_UNRATED`, `RATED`, `EXPIRED`
- `confirmDue` (boolean, 선택): `true`면 `LINK_CLICKED` 중 클릭 2일 경과·7일 미경과 건만
- `cursor`, `size`

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

- `400 VALIDATION_FAILED` 잘못된 `status`

### 처리 규칙

- 기본 정렬은 `clickedAt` 내림차순이며, `clickedAt`이 `null`인 기록은 뒤에 배치하고 동일 시각은 `watchRecordId` 내림차순으로 정렬한다 (`ORDER BY clicked_at DESC NULLS LAST, id DESC`)
- 7일 만료 처리는 서버 배치(매시, 가정)가 수행하며 조회 시점에도 만료 조건을 만족하면 `EXPIRED`로 계산해 응답한다

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
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

'감상하셨나요?' 질문에 대한 응답을 기록하고 상태를 전이한다.

### 인증 및 권한

- 회원. 본인 기록만

### Path Parameters

- `watchRecordId`

### Query Parameters

없음

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

- `404 RESOURCE_NOT_FOUND` 타인 기록 포함
- `409 INVALID_WATCH_STATUS` `LINK_CLICKED` 상태가 아님

### 처리 규칙

- `watched: true` → `WATCHED_UNRATED`, 평가 요청 UI로 이어진다. 평가는 지금 하지 않고 나중에 남겨도 된다
- `watched: false` → `EXPIRED`. 만료는 추천 신호로 사용하지 않는다
- 클릭 후 2일이 지나지 않아도 사용자가 응답하면 받아들인다(가정)

### 상태값

- WatchStatus `LINK_CLICKED` → `WATCHED_UNRATED` 또는 `EXPIRED`

### 미정 사항

- [ ] 2일 이전 응답 허용 여부(가정: 허용)

## API-036 평가 생성

`POST` `/api/v1/movies/{movieId}/ratings`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-19, FR-26, FR-07, FR-09 |
| 화면 | ⑥-4 평가, ⑪-3 평가 |
| 미정 사항 | 있음 |
| 비고 | 프레임·팝콘 생성 트리거 |

### 설명

영화에 1~5점 평가와 선택적 한줄평을 남긴다. 최초 저장 시 프레임을 생성해 필름에 추가하고 대표 맛에 팝콘 1알을 추가한다.

### 인증 및 권한

- 회원

### Path Parameters

- `movieId`

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `score` | integer | O | 1~5 |
| `comment` | string | X | 한줄평, 200자 이하 |
| `visibility` | string | comment 있으면 O | `PUBLIC` 또는 `PRIVATE` |

```json
{ "score": 5, "comment": "밤에 보면 안 됨", "visibility": "PUBLIC" }
```

### Response

`201 Created`

```json
{
  "ratingId": 91,
  "movieId": 550,
  "score": 5,
  "comment": "밤에 보면 안 됨",
  "visibility": "PUBLIC",
  "frame": { "frameId": 77, "createdAt": "2026-08-30T05:21:00Z" },
  "popcorn": { "flavorCode": "FLAVOR_X", "isNewFlavor": true, "flavorCount": 1 },
  "watchStatus": "RATED",
  "createdAt": "2026-08-30T05:21:00Z"
}
```

### 오류 응답

- `400 VALIDATION_FAILED` 점수 범위, 200자 초과, `comment` 있는데 `visibility` 없음
- `404 RESOURCE_NOT_FOUND`
- `409 RATING_ALREADY_EXISTS` 사용자·영화 조합에 평가가 이미 있음. 수정은 API-037

### 처리 규칙

- 사용자·영화 조합마다 평가는 하나다
- 평가 저장·프레임 생성·팝콘 추가·감상 기록 `RATED` 전이는 하나의 트랜잭션으로 처리한다(NFR-06)
- 영화는 K-Means 취향 군집으로 배정된 대표 맛 하나에만 팝콘을 추가한다(ADR-0015)
- 감상 기록이 없거나 `LINK_CLICKED`인 영화도 평가할 수 있으며, 이때 감상 기록을 `RATED`로 생성·전이한다 (D-6 결정)
- 저장 후 취향 분석(FR-04)과 추천 입력 버전을 갱신하고 추천 캐시를 무효화한다
- 한줄평은 평가에 종속되므로 별도 리소스가 아니다

### 상태값

- WatchStatus → `RATED`
- Visibility

### 미정 사항

- [ ] `isNewFlavor` 판정 임계치(FR-06)
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

점수·한줄평·공개 범위를 수정한다. 한줄평을 지우려면 `comment: null`을 보낸다.

### 인증 및 권한

- 회원. 본인 평가만

### Path Parameters

- `ratingId`

### Query Parameters

없음

### Request Body

```json
{ "score": 4, "comment": null, "visibility": "PRIVATE" }
```

### Response

`200 OK`, API-036과 동일 본문(`frame`·`popcorn`은 기존 값)

### 오류 응답

- `400 VALIDATION_FAILED`
- `404 RESOURCE_NOT_FOUND` 타인 평가 포함

### 처리 규칙

- 프레임·팝콘은 최초 저장 시 한 번만 생성하므로 수정으로 늘거나 줄지 않는다
- 점수 변경은 맛 선호도(평균 평점)와 취향 분석에 반영된다
- `PUBLIC` → `PRIVATE`로 바꾸면 한줄평 목록(API-032)에서 즉시 제외된다

### 상태값

없음

### 미정 사항

없음

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

평가를 삭제한다. 연결된 프레임과 팝콘도 함께 삭제된다.

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

- `404 RESOURCE_NOT_FOUND`

### 처리 규칙

- 평가·프레임·팝콘 삭제와 취향 집계 반영을 하나의 트랜잭션으로 처리한다
- 감상 기록은 삭제하지 않고 `WATCHED_UNRATED`로 되돌린다(FR-18). 추천에서는 계속 제외된다
- 삭제 후 같은 영화에 다시 평가하면 새 프레임·팝콘이 생성된다

### 상태값

- WatchStatus `RATED` → `WATCHED_UNRATED`

### 미정 사항

- [ ] 삭제로 어떤 맛의 팝콘이 0이 되면 `새로운 맛 발견` 표시(FR-32)도 취소할지

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

내가 남긴 평가를 최신순으로 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `cursor`, `size`

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "ratingId": 91,
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
      "score": 5,
      "comment": "밤에 보면 안 됨",
      "visibility": "PUBLIC",
      "frameId": 77,
      "createdAt": "2026-08-30T05:21:00Z"
    }
  ],
  "nextCursor": "...",
  "hasNext": true
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 필름(API-042)과 1:1이지만, 평가 목록은 점수·한줄평 관리 관점, 필름은 시각화 관점으로 분리한다

### 상태값

없음

### 미정 사항

없음
