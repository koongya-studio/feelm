# API 명세 — 소셜

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-032](#api-032-공개-한줄평-목록) | 공개 한줄평 목록 | GET | `/movies/{movieId}/reviews` | 1차 MVP | 선택 |
| [API-062](#api-062-공개-프로필-조회) | 공개 프로필 조회 | GET | `/users/{userId}/profile` | 2차 MVP | 선택 |
| [API-063](#api-063-취향-비교) | 취향 비교 | GET | `/users/{userId}/taste-comparison` | 2차 MVP | 회원 |
| [API-065](#api-065-사용자-랭킹) | 사용자 랭킹 | GET | `/rankings/users` | 확장 | 선택 |
| [API-067](#api-067-커뮤니티) | 커뮤니티 | - | `/community (미정)` | 확장 | 회원 |

## API-032 공개 한줄평 목록

`GET` `/api/v1/movies/{movieId}/reviews`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 선택 |
| 관련 요구사항 | FR-26, FR-34, FR-39 |
| 화면 | ⑥-2 영화 상세 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

영화의 `PUBLIC` 한줄평을 최신순으로 조회한다.

### 인증 및 권한

- 선택. 비회원 조회 가능. 로그인 시 차단 필터 적용, 본인 한줄평은 `PRIVATE`이어도 `mine: true`로 포함

### Path Parameters

- `movieId`

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
      "user": { "userId": 1024, "nickname": "팝콘러버" },
      "score": 5,
      "comment": "밤에 보면 안 됨",
      "mine": false,
      "createdAt": "2026-08-30T05:21:00Z"
    }
  ],
  "nextCursor": "...",
  "hasNext": false
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`

### 처리 규칙

- 한줄평은 평가(FR-19)의 선택 항목이므로 별도 생성 API가 없다. 작성·수정·삭제는 API-036/037/038
- 차단 관계(양방향)의 한줄평은 서버에서 제외한다
- 한줄평 댓글은 범위 밖

### 상태값

- Visibility 참조

### 미정 사항

없음

## API-062 공개 프로필 조회

`GET` `/api/v1/users/{userId}/profile`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 선택 |
| 관련 요구사항 | FR-25, FR-39, NFR-07 |
| 화면 | ⑪ 프로필 대시보드 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

`PUBLIC` 사용자의 닉네임·프로필 요약·필름·팝콘·공개 평가를 조회한다. 비회원도 조회할 수 있다.

### 인증 및 권한

- 선택. `PRIVATE` 사용자는 본인 외 `404`. 로그인 사용자와 차단 관계면 `404`

### Path Parameters

- `userId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "user": { "userId": 2048, "nickname": "영화광", "visibility": "PUBLIC" },
  "keywords": [{ "code": "DARK_THRILLER_LOVER", "label": "어둠 속 스릴러 애호가" }],
  "stats": { "frameCount": 88, "popcornCount": 88 },
  "popcorn": { "flavors": [{ "flavorCode": "FLAVOR_X", "popcornCount": 30, "averageScore": 4.5 }] },
  "recentFrames": [
    { "frameId": 190, "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." }, "score": 5, "createdAt": "2026-08-30T05:21:00Z" }
  ],
  "publicReviewCount": 21,
  "isBlockedByMe": false
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND` 없음, `PRIVATE`, 차단 관계

### 처리 규칙

- 공개 프로필의 평가 중 `PRIVATE` 한줄평은 제외한다
- 본인이 자신의 `userId`로 조회하면 `visibility`와 무관하게 `200`

### 상태값

- Visibility

### 미정 사항

없음

## API-063 취향 비교

`GET` `/api/v1/users/{userId}/taste-comparison`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-25 |
| 화면 | ⑪ 프로필 대시보드 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

로그인 사용자와 `PUBLIC` 사용자의 취향을 비교한다.

### 인증 및 권한

- 회원. 대상은 `PUBLIC`이어야 하며 차단 관계면 `404`

### Path Parameters

- `userId`: 비교 대상

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "targetUser": { "userId": 2048, "nickname": "영화광" },
  "similarity": 0.72,
  "sharedFlavors": ["FLAVOR_X", "FLAVOR_Y"],
  "flavorComparison": [
    { "flavorCode": "FLAVOR_X", "mine": { "popcornCount": 12, "averageScore": 4.4 }, "theirs": { "popcornCount": 30, "averageScore": 4.5 } }
  ],
  "commonMovieCount": 9,
  "commonMovies": [{ "movieId": 550, "title": "파이트 클럽", "myScore": 5, "theirScore": 4 }]
}
```

### 오류 응답

- `400 CANNOT_COMPARE_SELF`
- `404 RESOURCE_NOT_FOUND`

### 처리 규칙

- `commonMovies`는 최대 10편(가정)

### 상태값

없음

### 미정 사항

- [ ] `similarity` 산출식(팝콘 분포 코사인 유사도 vs 공통 영화 평점 상관)

## API-065 사용자 랭킹

`GET` `/api/v1/rankings/users`

| 항목 | 값 |
|---|---|
| 단계 | 확장 |
| 인증 | 선택 |
| 관련 요구사항 | FR-28 |
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | 랭킹 기준·집계 주기 미정 |

### 설명

팝콘 수와 새로운 맛 발견 횟수 등을 기준으로 사용자 랭킹을 제공한다.

### 인증 및 권한

- 선택. 로그인 시 `myRank` 포함

### Path Parameters

없음

### Query Parameters

- `metric` (string): `POPCORN_COUNT`, `DISCOVERY_COUNT` (가정)
- `cursor`, `size`

### Request Body

없음

### Response

`200 OK`

```json
{
  "metric": "POPCORN_COUNT",
  "updatedAt": "2026-08-30T00:00:00Z",
  "myRank": { "rank": 152, "value": 42 },
  "items": [
    { "rank": 1, "user": { "userId": 2048, "nickname": "영화광" }, "value": 880 }
  ],
  "nextCursor": "...",
  "hasNext": true
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- `PRIVATE` 사용자는 랭킹 노출 대상에서 제외하고 `myRank`에만 표시한다(가정)
- 차단 관계 사용자는 목록에서 제외한다

### 상태값

없음

### 미정 사항

- [ ] 랭킹 기준·집계 주기(FR-28 비고) 확정 후 상세 작성
- [ ] `PRIVATE` 사용자 노출 정책

## API-067 커뮤니티

`/community (미정)`

| 항목 | 값 |
|---|---|
| 단계 | 확장 |
| 인증 | 회원 |
| 관련 요구사항 | FR-31 |
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | 범위 미정 — 자리표시자 |

### 설명

사용자가 감상 의견과 추천을 공유하는 커뮤니티 기능. FR-31의 범위가 미정이어서 API를 정의하지 않았다. 범위 확정 후 이 항목을 게시글·댓글 등 실제 API로 분할한다.

### 인증 및 권한

- 회원(가정)

### Path Parameters

미정

### Query Parameters

미정

### Request Body

미정

### Response

미정

### 오류 응답

미정

### 처리 규칙

- 차단 관계 필터(FR-39)와 공개 범위 정책을 따른다

### 상태값

미정

### 미정 사항

- [ ] 커뮤니티 범위(게시판형 vs 영화별 토론 vs 추천 리스트 공유)
- [ ] 한줄평(FR-26)과의 관계
