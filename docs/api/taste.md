# API 명세 — 취향

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-040](#api-040-취향-분석키워드-조회) | 취향 분석·키워드 조회 | GET | `/users/me/taste` | 1차 MVP | 회원 |
| [API-041](#api-041-팝콘-버킷-조회) | 팝콘 버킷 조회 | GET | `/users/me/popcorn` | 1차 MVP | 회원 |
| [API-042](#api-042-내-필름프레임-목록-조회) | 내 필름(프레임 목록) 조회 | GET | `/users/me/frames` | 1차 MVP | 회원 |
| [API-043](#api-043-프레임-상세-조회) | 프레임 상세 조회 | GET | `/frames/{frameId}` | 1차 MVP | 회원 |
| [API-044](#api-044-팝콘-맛-기준정보-조회) | 팝콘 맛 기준정보 조회 | GET | `/popcorn-flavors` | 1차 MVP | 공개 |

## API-040 취향 분석·키워드 조회

`GET` `/api/v1/users/me/taste`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-04, FR-05 |
| 화면 | ⑪ 프로필 대시보드 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

평가 데이터를 장르·국가·시대·감독으로 분석한 결과와 사전 정의 취향 키워드 3~5개를 조회한다.

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
{
  "ratingCount": 42,
  "insufficientData": false,
  "analysis": {
    "genres": [{ "code": "THRILLER", "name": "스릴러", "count": 12, "averageScore": 4.4, "share": 0.29 }],
    "countries": [{ "code": "KR", "name": "한국", "count": 15, "averageScore": 4.1, "share": 0.36 }],
    "eras": [{ "label": "2010s", "count": 20, "averageScore": 4.0, "share": 0.48 }],
    "directors": [{ "personId": 7467, "name": "데이비드 핀처", "count": 3, "averageScore": 4.7 }]
  },
  "keywords": [
    { "code": "DARK_THRILLER_LOVER", "label": "어둠 속 스릴러 애호가" }
  ],
  "analyzedAt": "2026-08-30T05:21:00Z"
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 분석 요소는 최소 장르·국가·시대·감독을 포함한다
- Spring은 평가 추가·수정·삭제 트랜잭션에서 `taste_analyses` 캐시를 갱신하고 이 API는 캐시된 결과를 반환한다
- 평가가 하나도 없으면 `taste_analyses` 행을 만들지 않는다
- 평가가 기준 미만(5건, 가정)이면 `insufficientData: true`와 빈 `keywords`
- 키워드는 사전 정의 목록에서만 선택하며 사용자당 3~5개

### 상태값

없음

### 미정 사항

- [ ] 키워드 사전 목록과 산출 규칙
- [ ] 분석 최소 평가 수

## API-041 팝콘 버킷 조회

`GET` `/api/v1/users/me/popcorn`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-06, FR-09, FR-32 |
| 화면 | ⑨ 팝콘 버킷 |
| 미정 사항 | 없음 |
| 비고 | 8개 맛을 항상 반환하며 평가 영화가 없는 맛은 0개로 표시 |

### 설명

사용자의 평가와 각 영화의 대표 맛을 집계해 8개 팝콘 맛별 감상량과 평균 평점을 조회한다. 별도의 사용자 팝콘 행은 저장하지 않는다.

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
{
  "totalCount": 42,
  "flavors": [
    {
      "flavorCode": "FLAVOR_X",
      "displayName": "짭짤한 스릴",
      "color": "#E63946",
      "popcornCount": 12,
      "averageScore": 4.4,
      "discoveredAt": "2026-07-01T00:00:00Z"
    }
  ]
}
```

- 8개 맛을 항상 모두 포함하며 팝콘이 없으면 `popcornCount: 0`, `averageScore: null`

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- `ratings JOIN movies`를 `movies.representative_flavor_id`로 그룹화해 수량·평균 평점·최초 평가 시각을 계산한다
- 팝콘 맛 8개를 항상 반환하고 집계 결과가 없는 맛은 `popcornCount: 0`, `averageScore: null`로 반환한다
- 팝콘 수와 선호도는 독립 지표로 구분해 표시한다
- 감상량 임계값이나 임계값 기반 `isNewFlavor`를 사용하지 않는다. 취향 발견 추천(FR-12)의 판정은 별도 기준으로 다룬다
- `discoveredAt`은 확장 기능 FR-32용으로 예약. 1차에서는 `null` 가능

### 상태값

- PopcornFlavor 기준정보 참조(API-044)

### 미정 사항

없음

## API-042 내 필름(프레임 목록) 조회

`GET` `/api/v1/users/me/frames`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-07 |
| 화면 | ⑪ 프로필 대시보드, ⑥-5 필름 기입 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

필름에 기입된 프레임을 최신순으로 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `flavorCode` (string, 선택): 맛으로 필터
- `cursor`, `size`

### Request Body

없음

### Response

`200 OK`

```json
{
  "totalCount": 42,
  "items": [
    {
      "frameId": 77,
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
      "score": 5,
      "flavorCode": "FLAVOR_X",
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

- 프레임은 평가와 1:1. 평가 삭제 시 함께 사라진다

### 상태값

없음

### 미정 사항

없음

## API-043 프레임 상세 조회

`GET` `/api/v1/frames/{frameId}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-08 |
| 화면 | ⑪ 프로필 대시보드 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

프레임을 선택했을 때 영화 정보, 내 평가, 감상 기록을 함께 조회한다.

### 인증 및 권한

- 회원. 본인 프레임만. 타인 프레임은 `404` (공개 프로필의 프레임은 2차 API-062 경로)

### Path Parameters

- `frameId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "frameId": 77,
  "movie": {
    "movieId": 550,
    "title": "파이트 클럽",
    "posterUrl": "https://...",
    "releaseYear": 1999,
    "genres": ["드라마", "스릴러"],
    "directors": ["데이비드 핀처"]
  },
  "flavorCode": "FLAVOR_X",
  "rating": { "ratingId": 91, "score": 5, "comment": "밤에 보면 안 됨", "visibility": "PUBLIC" },
  "watchRecord": {
    "ott": { "code": "NETFLIX", "name": "넷플릭스" },
    "clickedAt": "2026-08-28T05:21:00Z",
    "watchedAt": "2026-08-30T05:00:00Z",
    "ratedAt": "2026-08-30T05:21:00Z"
  }
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`

### 처리 규칙

- 감상 기록이 없이 평가한 경우 `watchRecord`의 `ott`·`clickedAt`은 `null`

### 상태값

없음

### 미정 사항

없음

## API-044 팝콘 맛 기준정보 조회

`GET` `/api/v1/popcorn-flavors`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-06 |
| 화면 | ⑨ 팝콘 버킷 |
| 미정 사항 | 있음 |
| 비고 | ADR-0015의 K-Means 8개 취향 유형 |

### 설명

K-Means로 생성한 8개 팝콘 맛의 안정적인 코드·표시명·색상·대표 키워드를 내려준다. 원시 Cluster ID는 공개하지 않으며 클라이언트는 색상·표시명을 하드코딩하지 않고 이 API를 사용한다.

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
    {
      "code": "FLAVOR_X",
      "displayName": "짭짤한 스릴",
      "color": "#E63946",
      "representativeKeywords": ["긴장", "추적", "반전"],
      "sortOrder": 1
    }
  ]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- `code`는 불변, `displayName`·`color`는 변경 가능(FR-06)
- 고정된 `clusterId`와 불변 `code`를 `popcorn_flavors`에서 1:1로 관리하며 원시 Cluster ID는 응답에 노출하지 않는다(ADR-0015)
- `representativeKeywords`는 군집별 텍스트의 c-TF-IDF 결과를 사용한다
- 기준정보이므로 캐시 가능

### 상태값

없음

### 미정 사항

- [ ] 8종 표시명·색상과 군집 코드 매핑 검수
