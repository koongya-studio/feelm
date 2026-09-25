# API 명세 — 소셜

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-032](#api-032-공개-한줄평-목록) | 공개 한줄평 목록 | GET | `/movies/{movieId}/reviews` | 1차 MVP | 회원 |
| [API-062](#api-062-공개-프로필-조회) | 공개 프로필 조회 | GET | `/users/{publicId}/profile` | 2차 MVP | 선택 |
| [API-063](#api-063-취향-비교) | 취향 비교 | GET | `/users/{publicId}/taste-comparison` | 2차 MVP | 회원 |
| [API-065](#api-065-사용자-랭킹) | 사용자 랭킹 | GET | `/rankings/users` | 확장 | 선택 |
| [API-067](#api-067-커뮤니티) | 커뮤니티 | - | `/community (미정)` | 확장 | 회원 |

## API-032 공개 한줄평 목록

`GET` `/api/v1/movies/{movieId}/reviews`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-26, FR-34, FR-39 |
| 화면 | ⑥-2 영화 상세 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

영화의 한줄평을 `createdAt DESC`, `ratingId DESC` 순으로 조회한다. 타인의 `PUBLIC`(작성자의 `reviewVisibility`도 `PUBLIC`인 것)과 본인의 `PUBLIC`·`PRIVATE`를 포함한다.

### 인증 및 권한

- Access Token이 필요한 회원 API다. 토큰이 없거나 잘못됐거나 만료됐으면 `401`이며 비회원으로 강등하지 않는다
- 조회자는 `ACTIVE` 회원이어야 한다. 작성자도 `ACTIVE`인 한줄평만 노출한다
- 본인 한줄평은 `PRIVATE`이어도 `mine: true`로 포함하고, 양방향 차단 상대의 한줄평은 제외한다
- 작성자 프로필이 `PRIVATE`여도 평가가 `PUBLIC`이면 영화별 목록에 포함한다. 작성자 프로필 접근은 별도 공개 범위 정책을 따른다(API-062)
- 작성자의 한줄평 공개 범위(`reviewVisibility`, API-014·015)가 `PRIVATE`이면 평가가 `PUBLIC`이어도 타인에게 노출하지 않는다. 본인 한줄평에는 적용하지 않는다(S15P21E106-856)

### Path Parameters

- `movieId`: 양의 64비트 정수

### Query Parameters

- `cursor`: 서버가 직전 응답에서 발급한 불투명 cursor. 영화와 로그인 사용자 범위에 묶이며 빈 문자열은 허용하지 않는다
- `size`: 기본 20, 1~50

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
      "spoiler": true,
      "createdAt": "2026-08-30T05:21:00Z"
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

### 오류 응답

- `400 VALIDATION_FAILED` 잘못된 `movieId`·`size`·`cursor`
- `401 UNAUTHORIZED` 인증이 없거나 유효하지 않거나 조회자가 `ACTIVE`가 아님
- `401 TOKEN_EXPIRED` Access Token이 만료됨
- `404 RESOURCE_NOT_FOUND` 영화가 없음
- `500 INTERNAL_ERROR`

### 처리 규칙

- 한줄평은 평가(FR-19)의 선택 항목이므로 별도 생성 API가 없다. 작성·수정·삭제는 API-036/037/038
- `comment IS NULL`이거나 빈 문자열·탭·개행·Unicode 공백뿐인 평가는 제외한다
- 타인의 `PRIVATE`, `reviewVisibility=PRIVATE`인 작성자, 양방향 차단 상대, `ACTIVE`가 아닌 작성자의 한줄평은 서버에서 제외한다
- `size + 1`행으로 다음 페이지 여부를 판단하며 마지막 페이지의 `nextCursor`는 `null`이다. 페이지 사이에는 현재 커밋된 차단·평가 상태를 다시 적용하며 전체 페이지의 snapshot은 보장하지 않는다
- `spoiler`는 항상 반환하는 boolean이다. 백엔드는 값과 무관하게 원문 `comment`를 반환한다
- FE는 작성자가 본인인지와 관계없이 `spoiler=true`인 공개 목록 항목을 기본 블러하고
  `평가 보기`로 로컬 해제한다. 별도 내 평가 영역은 본인 원문을 그대로 표시하며,
  별도 reveal API나 서버 저장 상태는 없다
- 공감·좋아요·싫어요와 한줄평에 대한 댓글 기능은 범위 밖이다

### 상태값

- Visibility 참조

### 미정 사항

없음

## API-062 공개 프로필 조회

`GET` `/api/v1/users/{publicId}/profile`

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

- `publicId`: `users.public_id` (D-1 결정, ADR-0008) — 내부 숫자 ID는 공개 URL에 노출하지 않는다

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "user": {
    "userId": 2048,
    "nickname": "영화광",
    "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg",
    "visibility": "PUBLIC",
    "frameVisibility": "PUBLIC",
    "popcornVisibility": "PUBLIC",
    "reviewVisibility": "PUBLIC"
  },
  "keywords": [{ "code": "DARK_THRILLER_LOVER", "label": "어둠 속 스릴러 애호가" }],
  "stats": { "frameCount": 88, "popcornCount": 88 },
  "popcorn": { "flavors": [{ "flavorCode": "FLAVOR_A", "popcornCount": 30, "averageScore": 4.5 }] },
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
- 등록된 프로필 사진이 없으면 `user.avatarUrl`은 `null`
- 항목별 공개 범위(API-014 [항목별 공개 범위](./users.md#항목별-공개-범위), S15P21E106-856)가 `PRIVATE`인 항목은 본인 외 조회에서 **필드 모양을 유지한 채 값을 비운다.** 기존 필드의 타입과 필수 여부는 바뀌지 않는다

  | `user.` 필드 | `PRIVATE`일 때 |
  |---|---|
  | `frameVisibility` | `recentFrames: []`, `stats.frameCount: 0` |
  | `popcornVisibility` | `popcorn.flavors: []`, `stats.popcornCount: 0` |
  | `reviewVisibility` | `publicReviewCount: 0` |

- 빈 값과 가린 값은 `user.frameVisibility`·`popcornVisibility`·`reviewVisibility`로 구분한다. 세 필드는 본인 조회에서도 실제 설정값을 돌려준다. 가려도 `404`로 바꾸지 않는다 — 프로필은 보이고 그 안의 구역만 가린다
- 본인 조회는 세 값과 무관하게 모든 항목을 채운다
- `keywords`(취향 분석 결과)는 세 항목에 속하지 않아 가리지 않는다

### 상태값

- Visibility

### 미정 사항

없음

## API-063 취향 비교

`GET` `/api/v1/users/{publicId}/taste-comparison`

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

- `publicId`: 비교 대상의 `users.public_id` (ADR-0008)

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
  "sharedFlavors": ["FLAVOR_A", "FLAVOR_B"],
  "flavorComparison": [
    { "flavorCode": "FLAVOR_A", "mine": { "popcornCount": 12, "averageScore": 4.4 }, "theirs": { "popcornCount": 30, "averageScore": 4.5 } }
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
