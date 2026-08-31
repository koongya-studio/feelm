# API 명세 — 추천

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-031](#api-031-유사-영화-추천) | 유사 영화 추천 | GET | `/movies/{movieId}/similar` | 1차 MVP | 선택 |
| [API-045](#api-045-기본-추천-조회) | 기본 추천 조회 | GET | `/recommendations` | 1차 MVP | 회원 |
| [API-046](#api-046-추가-영화-추천) | 추가 영화 추천 | POST | `/recommendations/more` | 1차 MVP | 회원 |
| [API-047](#api-047-관심없음-표시) | 관심없음 표시 | POST | `/recommendations/items/{recommendationItemId}/dismiss` | 1차 MVP | 회원 |
| [API-048](#api-048-추천-이력-조회) | 추천 이력 조회 | GET | `/recommendations/history` | 1차 MVP | 회원 |
| [API-066](#api-066-오늘의-영화) | 오늘의 영화 | GET | `/recommendations/today` | 확장 | 회원 |

## API-031 유사 영화 추천

`GET` `/api/v1/movies/{movieId}/similar`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 선택 |
| 관련 요구사항 | FR-11 |
| 화면 | ⑥-2 영화 상세 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

장르·태그·줄거리·감독·배우 등 콘텐츠 특성이 비슷한 영화를 추천한다.

### 인증 및 권한

- 선택. 로그인 시 감상한 영화를 제외한다(가정)

### Path Parameters

- `movieId`: 기준 영화

### Query Parameters

- `size` (integer, 기본 10, 최대 20)

### Request Body

없음

### Response

`200 OK`

```json
{
  "baseMovieId": 550,
  "items": [
    {
      "movieId": 807,
      "title": "세븐",
      "posterUrl": "https://...",
      "releaseYear": 1995,
      "averageRating": 4.2,
      "similarity": 0.87,
      "reason": { "summary": "같은 감독, 스릴러", "factors": [{ "type": "DIRECTOR", "label": "데이비드 핀처" }] }
    }
  ]
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 기준 영화 자체는 결과에 포함하지 않는다
- 콘텐츠 기반이므로 비회원에게도 동일 결과를 제공한다

### 상태값

- RecommendationType `SIMILAR`

### 미정 사항

- [ ] 로그인 사용자의 감상 영화 제외 여부
- [ ] 유사 추천을 추천 이력(FR-33)에 기록할지

## API-045 기본 추천 조회

`GET` `/api/v1/recommendations`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-10, FR-12, FR-13 |
| 화면 | ⑤ 초기 결과 추천, ⑥ 추천 피드 |
| 미정 사항 | 있음 |
| 비고 | 3편 = 취향 기반 2 + 취향 발견 1 |

### 설명

현재 추천 세트(3편)를 조회한다. 추천 입력이 바뀌지 않았으면 같은 결과를 반환한다.

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
  "recommendationSetId": 7001,
  "generatedAt": "2026-08-30T05:21:00Z",
  "items": [
    {
      "recommendationItemId": 3301,
      "type": "TASTE",
      "movie": {
        "movieId": 550,
        "title": "파이트 클럽",
        "posterUrl": "https://...",
        "releaseYear": 1999,
        "genres": ["드라마", "스릴러"],
        "averageRating": 4.3,
        "ottProviders": ["NETFLIX"]
      },
      "flavorCode": "FLAVOR_X",
      "reason": {
        "summary": "스릴러를 높게 평가하셨어요",
        "factors": [
          { "type": "GENRE", "label": "스릴러" },
          { "type": "SIMILAR_MOVIE", "label": "세븐", "movieId": 807 }
        ]
      }
    }
  ]
}
```

### 오류 응답

- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 기본 구성은 `TASTE` 2편 + `DISCOVERY` 1편. `DISCOVERY` 후보가 없으면 `TASTE` 3편
- 감상 기록이 있는 영화(`LINK_CLICKED` 포함, 평가 여부 무관)와 관심없음 영화는 제외한다
- 추천 입력(평가·온보딩 선택)이 바뀌지 않으면 같은 `recommendationSetId`와 결과를 반환한다. 입력이 바뀌면 새 세트를 생성한다. 관심없음은 입력 변경으로 취급하지 않고 세트 내 교체로 처리한다(API-047)
- 온보딩을 건너뛰고 평가가 없는 사용자는 인기 기반 후보로 `TASTE`를 채운다(가정)
- 세트 생성 시 추천 서비스가 후보 20편(설정값)을 인터리브 정렬로 반환하고, 서버는 이를 세트 버퍼로 저장해 3편씩 노출한다
- 버퍼는 PostgreSQL `recommendation_items`에 저장하고 `exposed_at`으로 노출 여부를 구분한다. 무효화 기준은 [내부 API 명세](./internal.md)의 추천 캐시 무효화 정책을 따른다
- 새 세트 생성 시 추천 이력(FR-33)에 시각·유형·이유·후보를 저장한다
- `reason.factors[].type`: `GENRE`, `DIRECTOR`, `ACTOR`, `COUNTRY`, `ERA`, `SIMILAR_MOVIE`, `NEW_FLAVOR`(가정)

### 상태값

- RecommendationType `TASTE`, `DISCOVERY`

### 미정 사항

- [ ] 콜드 스타트 전략(온보딩 건너뛴 사용자)
- [ ] 추천 이유 factor 타입 목록과 문장 생성 방식(템플릿 vs LLM)
- [ ] 내부 API 계약은 [내부 API 명세](./internal.md)에서 협의 중 (데이터 접근 경계 등)

## API-046 추가 영화 추천

`POST` `/api/v1/recommendations/more`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-10 |
| 화면 | ⑥ 추천 피드 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

현재 세트를 유지한 채 같은 구성 규칙(TASTE 2 + DISCOVERY 1)의 3편을 추가한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "recommendationSetId": 7001 }
```

### Response

`200 OK`

```json
{
  "recommendationSetId": 7001,
  "addedItems": [],
  "totalItemCount": 6,
  "hasMore": true
}
```

- `addedItems`는 API-045의 `items`와 같은 구조

### 오류 응답

- `404 RESOURCE_NOT_FOUND` 세트 없음 또는 타인 세트
- `409 RECOMMENDATION_SET_STALE` 입력이 바뀌어 세트가 갱신됨. API-045를 다시 호출
- `409 NO_MORE_CANDIDATES` 후보 소진
- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 같은 세트 버퍼에서 다음 3편을 노출한다. 버퍼가 소진된 경우에만 추천 서비스를 재호출한다(기존 노출분 제외)
- 추가된 항목도 같은 세트의 추천 이력에 누적된다

### 상태값

없음

### 미정 사항

- [ ] 세트 버퍼 크기(기본 20)와 버퍼 재생성 상한 — 설정값

## API-047 관심없음 표시

`POST` `/api/v1/recommendations/items/{recommendationItemId}/dismiss`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-36 |
| 화면 | ⑥-1 카드 상호작용 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

추천 카드를 관심없음으로 표시하고 같은 유형의 새 후보 1편으로 즉시 교체한다.

### 인증 및 권한

- 회원. 본인 추천 항목만

### Path Parameters

- `recommendationItemId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "dismissedItemId": 3301,
  "movieId": 550,
  "replacement": {
    "recommendationItemId": 3305,
    "type": "TASTE",
    "movie": {},
    "flavorCode": "FLAVOR_Y",
    "reason": {}
  }
}
```

- 교체 후보가 없으면 `replacement: null`

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 ALREADY_DISMISSED`

### 처리 규칙

- 관심없음은 평가가 아니며 평가 테이블과 분리해 저장한다. 팝콘·프레임에 영향 없음
- 관심없음 영화는 이후 모든 개인 추천에서 제외한다. 유사 영화 추천(API-031) 제외 여부는 미정
- 관심없음은 세트가 갱신되지 않도록 현재 세트 내 교체로 처리한다(버퍼의 다음 같은 타입 후보 승격, 추천 서비스 호출 없음). 같은 타입 후보가 소진되면 `replacement: null`을 `200`으로 반환하고 세트와 `inputVersion`은 유지한다
- 관심없음 영화는 다음 버퍼 생성 시 `excludeMovieIds`에 포함되어 제외된다

### 상태값

없음

### 미정 사항

- [ ] 관심없음 해제 기능 제공 여부

## API-048 추천 이력 조회

`GET` `/api/v1/recommendations/history`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-33, FR-13 |
| 화면 | ⑪-2 추천 기록 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

추천받은 세트를 최신순으로 조회한다. 세트마다 추천 시각·유형·이유·당시 후보를 포함한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `cursor`, `size` (세트 단위)

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "recommendationSetId": 7001,
      "generatedAt": "2026-08-30T05:21:00Z",
      "source": "HOME",
      "items": [
        {
          "recommendationItemId": 3301,
          "type": "TASTE",
          "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
          "reason": { "summary": "스릴러를 높게 평가하셨어요" },
          "dismissed": false,
          "watchStatus": "RATED"
        }
      ]
    }
  ],
  "nextCursor": "...",
  "hasNext": true
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 이력은 생성 시점 스냅샷이며 이후 영화 정보 변경과 무관하게 추천 이유를 보존한다
- `source`: `HOME`(기본 추천), `PARTY`(파티 추천, 가정)
- 관심없음 처리한 항목은 `dismissed: true`로 남긴다

### 상태값

- RecommendationType, WatchStatus

### 미정 사항

없음

## API-066 오늘의 영화

`GET` `/api/v1/recommendations/today`

| 항목 | 값 |
|---|---|
| 단계 | 확장 |
| 인증 | 회원 |
| 관련 요구사항 | FR-30 |
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | 외부 데이터 소스 미정 |

### 설명

날씨·계절·기념일·최근 트렌드 등 외부 요소를 반영해 하루 한 편을 추천한다.

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
  "date": "2026-08-30",
  "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://..." },
  "reason": {
    "summary": "비 오는 토요일 밤에 어울리는 스릴러",
    "factors": [
      { "type": "WEATHER", "label": "비" },
      { "type": "GENRE", "label": "스릴러" }
    ]
  }
}
```

### 오류 응답

- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 하루에 한 편, 자정(KST) 기준 갱신(가정)
- 감상 기록·관심없음 영화 제외

### 상태값

- factor type: `WEATHER`, `SEASON`, `HOLIDAY`, `TREND` + 개인 추천 factor

### 미정 사항

- [ ] 외부 데이터 소스(날씨 API, 공휴일 API) 확정 후 상세 작성(FR-30 비고)
- [ ] 위치 정보 수집 여부(날씨 반영 시)
