# API 명세 — 영화·검색

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-028](#api-028-영화-검색) | 영화 검색 | GET | `/movies/search` | 1차 MVP | 선택 |
| [API-029](#api-029-검색-필터-옵션-조회) | 검색 필터 옵션 조회 | GET | `/movies/filter-options` | 1차 MVP | 공개 |
| [API-030](#api-030-영화-상세-조회) | 영화 상세 조회 | GET | `/movies/{movieId}` | 1차 MVP | 선택 |

## API-028 영화 검색

`GET` `/api/v1/movies/search`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 선택 |
| 관련 요구사항 | FR-14, FR-15 |
| 화면 | ⑩ 검색 홈, ⑩-1 검색 결과 |
| 미정 사항 | 있음 |
| 비고 | 검색 화면 구성 미정 |

### 설명

키워드(영화명·배우·감독)와 필터(장르·국가·개봉연도·OTT)로 영화를 검색한다. 키워드 없이 필터만으로도 조회할 수 있다.

### 인증 및 권한

- 선택. 비회원 조회 가능. 로그인 시 `watched` 플래그가 채워진다

### Path Parameters

없음

### Query Parameters

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `q` | string | X | 키워드. 영화명·배우·감독 대상. 필터가 하나도 없으면 필수 |
| `genre` | string[] | X | 장르 코드, 복수 가능(OR) |
| `country` | string[] | X | ISO 3166-1 alpha-2, 복수 가능(OR) |
| `yearFrom`, `yearTo` | integer | X | 개봉연도 범위 |
| `ott` | integer[] | X | ottId, 복수 가능(OR) |
| `sort` | string | X | `RELEVANCE`(기본), `RATING`, `RELEASE_DATE` |
| `cursor`, `size` |  | X | 공통 페이지네이션 |

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
      "originalTitle": "Fight Club",
      "posterUrl": "https://...",
      "releaseYear": 1999,
      "genres": ["드라마", "스릴러"],
      "averageRating": 4.3,
      "ottProviders": ["NETFLIX"],
      "watched": false
    }
  ],
  "nextCursor": "...",
  "hasNext": true
}
```

### 오류 응답

- `400 VALIDATION_FAILED` `q`와 필터가 모두 없음, 연도 범위 역전

### 처리 규칙

- 서로 다른 필터는 AND, 같은 필터의 복수 값은 OR로 결합한다
- 결과가 없으면 빈 `items`와 `200`

### 상태값

없음

### 미정 사항

- [ ] 검색 화면 구성(FR-14 비고)
- [ ] 검색 엔진: PostgreSQL full-text(가정) vs Elasticsearch
- [ ] 배우·감독 검색 시 인물 결과를 별도로 노출할지

## API-029 검색 필터 옵션 조회

`GET` `/api/v1/movies/filter-options`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-15 |
| 화면 | ⑩-1 검색 결과 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

필터 UI에 필요한 장르·국가·연도 범위·OTT 목록을 한 번에 내려준다.

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
  "genres": [{ "code": "THRILLER", "name": "스릴러" }],
  "countries": [{ "code": "KR", "name": "한국" }],
  "yearRange": { "min": 1920, "max": 2026 },
  "otts": [{ "ottId": 1, "code": "NETFLIX", "name": "넷플릭스" }]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 기준정보이므로 캐시 가능(가정 1일)

### 상태값

없음

### 미정 사항

없음

## API-030 영화 상세 조회

`GET` `/api/v1/movies/{movieId}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 선택 |
| 관련 요구사항 | FR-34, FR-16, FR-13 |
| 화면 | ⑥-2 영화 상세 |
| 미정 사항 | 있음 |
| 비고 | 추천 경로 진입 시 recommendationItemId 전달 |

### 설명

시놉시스, OTT 제공처·바로가기, 평균 별점 등 영화 상세를 조회한다. 추천 경로로 진입하면 추천 이유를 함께 내려준다. 공개 한줄평 목록은 API-032로 별도 조회한다.

### 인증 및 권한

- 선택. 비회원은 비개인화 정보만. 로그인 시 `myRating`, `myWatchStatus`, OTT `subscribed` 채움

### Path Parameters

- `movieId`

### Query Parameters

- `recommendationItemId` (integer, 선택): 추천 카드에서 진입한 경우 전달. 해당 추천 이유를 `recommendation`에 포함

### Request Body

없음

### Response

`200 OK`

```json
{
  "movieId": 550,
  "title": "파이트 클럽",
  "originalTitle": "Fight Club",
  "synopsis": "...",
  "releaseDate": "1999-10-15",
  "runtimeMinutes": 139,
  "genres": [{ "code": "DRAMA", "name": "드라마" }],
  "countries": [{ "code": "US", "name": "미국" }],
  "directors": [{ "personId": 7467, "name": "데이비드 핀처" }],
  "cast": [{ "personId": 287, "name": "브래드 피트", "character": "Tyler Durden" }],
  "posterUrl": "https://...",
  "backdropUrl": "https://...",
  "flavorCode": "FLAVOR_X",
  "averageRating": 4.3,
  "ratingCount": 1280,
  "ottProviders": [
    { "ottId": 1, "code": "NETFLIX", "name": "넷플릭스", "watchUrl": "https://...", "subscribed": true }
  ],
  "myRating": { "ratingId": 91, "score": 5, "comment": "...", "visibility": "PUBLIC" },
  "myWatchStatus": "RATED",
  "recommendation": {
    "recommendationItemId": 3301,
    "type": "TASTE",
    "reason": { "summary": "스릴러 장르를 높게 평가하셨어요", "factors": [{ "type": "GENRE", "label": "스릴러" }] }
  }
}
```

- 비회원: `myRating`, `myWatchStatus`는 `null`, `subscribed`는 `false`
- `recommendation`은 `recommendationItemId`가 있고 본인의 추천일 때만 포함

### 오류 응답

- `404 RESOURCE_NOT_FOUND`

### 처리 규칙

- `ottProviders`는 사용자가 구독 중인 OTT를 우선 정렬한다(FR-16)
- `averageRating`은 전체 사용자 기준이며 차단 관계 필터를 적용하지 않는다(가정). 차단 필터는 한줄평 목록(API-032)에만 적용
- AI 영화 요약은 범위에서 제외한다(FR-34)
- 다른 사용자의 `recommendationItemId`를 전달하면 `recommendation`을 생략하고 `200`

### 상태값

- WatchStatus, RecommendationType 참조

### 미정 사항

- [ ] 평균 별점에 차단 사용자 평가 제외 여부(가정: 미제외)
- [ ] `cast` 노출 인원 수
- [ ] TMDb 데이터 동기화 주기와 캐시 정책
