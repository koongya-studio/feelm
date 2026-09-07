# API 명세 — 추천

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-031](#api-031-유사-영화-추천) | 유사 영화 추천 | GET | `/movies/{movieId}/similar` | 1차 MVP | 선택 |
| [API-045](#api-045-기본-추천-조회) | 기본 추천 조회 | GET | `/recommendations` | 1차 MVP | 회원 |
| [API-046](#api-046-추가-영화-추천) | 추가 영화 추천 | POST | `/recommendations/more` | 1차 MVP | 회원 |
| [API-047](#api-047-관심없음-표시) | 관심없음 표시 | POST | `/movies/{movieId}/dismiss` | 1차 MVP | 회원 |
| [API-048](#api-048-추천-이력-조회) | 추천 이력 조회 | GET | `/recommendations/history` | 1차 MVP | 회원 |
| [API-066](#api-066-오늘의-영화) | 오늘의 영화 | GET | `/recommendations/today` | 확장 | 회원 |
| [API-068](#api-068-ott-구독-추천) | OTT 구독 추천 | GET | `/recommendations/otts` | 확장 | 회원 |

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

다국어 임베딩 모델로 생성한 영화 벡터의 Cosine Similarity가 높은 영화를 추천한다. `multilingual-e5-base`는 MVP 베이스라인이며 최종 체크포인트는 한국어 영화 데이터 비교 평가 후 확정한다(ADR-0014).

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
- Spring이 PostgreSQL/pgvector HNSW 인덱스에서 Top-K를 조회하고 영화 메타데이터를 결합한다
- 콘텐츠 기반이므로 비회원에게도 동일 결과를 제공한다
- 사용자·파티 추천 세트와 무관한 영화 벡터 기반 결과이므로 `recommendation_items`와 추천 이력에 저장하지 않는다

### 상태값

없음

### 미정 사항

- [ ] 로그인 사용자의 감상 영화 제외 여부

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
- 두 유형 모두 K-Means 취향 군집을 기반으로 한다. 감상량·선호도·거리 등의 구체 구분 방식은 미정이며 `새로운 맛` 표시만으로 추천 유형을 확정하지 않는다(FR-10·FR-12)
- 감상 기록이 있는 영화(`LINK_CLICKED` 포함, 평가 여부 무관)와 관심없음 영화는 제외한다
- 활성 버퍼가 남아 있고 추천 입력(평가·온보딩 선택)이 바뀌지 않으면 같은 `recommendationSetId`와 결과를 반환한다. 입력이 바뀌거나 버퍼가 소진된 상태에서 API-046을 다시 요청하면 새 세트를 생성한다. 관심없음은 입력 변경으로 취급하지 않고 세트 내 교체로 처리한다(API-047)
- 온보딩을 건너뛰고 평가가 없는 사용자는 인기 기반 후보로 `TASTE`를 채운다(가정)
- Python Consumer가 Redis에 갱신한 기본 Top-N을 Spring이 최신 행동 Feature로 경량 재정렬하고 후보 20편(설정값)을 세트 버퍼로 저장해 3편씩 노출한다
- 버퍼는 PostgreSQL `recommendation_items`에 저장하고 `exposed_at`으로 노출 여부를 구분한다. 강한 신호는 Python Consumer가 Candidate와 기본 Top-N·XAI를 같은 사용자 단위 처리에서 갱신하고, 약한 신호는 Spark Streaming이 행동 Feature를 갱신해 Spring의 다음 세트 생성 때 반영한다. 약한 신호만으로 현재 세트는 무효화하지 않는다(ADR-0017)
- 강한 신호로 세트 버퍼가 무효화된 뒤 `topn.candidateVersion`이 최신 `candidates.candidateVersion`보다 낮으면 Spring은 부분 갱신 상태로 보고 이전 `topn`으로 새 세트를 만들지 않는다. 짧은 재시도 후에도 버전이 맞지 않으면 `503 RECOMMENDATION_UNAVAILABLE`을 반환한다
- 새 세트 생성 시 추천 이력(FR-33)에 시각·유형·이유·후보를 저장한다
- `reason.factors[].type`: `GENRE`, `DIRECTOR`, `ACTOR`, `COUNTRY`, `ERA`, `SIMILAR_MOVIE`, `NEW_FLAVOR`(가정)

### 상태값

- RecommendationType `TASTE`, `DISCOVERY`

### 미정 사항

- [ ] K-Means 군집 기반 맞춤·발견 구분 방식·기준값과 저이력·온보딩 사용자 처리. 군집 기반은 확정이며 경험량·선호도·거리 활용 방식은 미정
- [ ] 콜드 스타트 전략(온보딩 건너뛴 사용자)
- [ ] 추천 이유 factor 타입 목록과 문장 생성 방식(템플릿 vs LLM)
- [ ] Re-ranking 가중치와 추천 이유 생성 방식

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

현재 세트에 미노출 후보가 남아 있으면 같은 구성 규칙(TASTE 2 + DISCOVERY 1)으로 최대 3편을 추가한다. 버퍼를 모두 소진한 뒤 다시 요청하면 최신 행동 Feature를 반영한 새 추천 세트를 생성해 최대 3편을 제공한다.

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
- `409 NO_MORE_CANDIDATES` 새 추천 세트를 구성할 후보도 없음
- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 현재 세트에 미노출 후보가 남아 있으면 같은 `recommendationSetId`로 최대 3편을 노출하고, 추가된 항목을 해당 세트의 추천 이력에 누적한다
- 현재 세트의 버퍼를 모두 소진한 뒤 다시 요청하면 Redis의 기본 Top-N과 최신 행동 Feature를 읽어 경량 재정렬하고, 기존 노출분을 제외한 후보로 새 추천 세트를 생성한다
- 새 세트를 생성한 경우 응답의 `recommendationSetId`는 새 세트 ID이며, 제공한 항목과 최종 추천 이유는 새 세트의 추천 이력에 저장한다

### 상태값

없음

### 미정 사항

- [ ] 세트 버퍼 크기(기본 20)와 버퍼 재생성 상한 — 설정값

## API-047 관심없음 표시

`POST` `/api/v1/movies/{movieId}/dismiss`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-36 |
| 화면 | ⑥-1 카드 상호작용 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

영화를 관심없음으로 표시한다. 추천 카드에서 호출한 경우 선택적으로 추천 항목 출처를 기록하고 같은 유형의 새 후보 1편으로 즉시 교체한다. 영화 상세 등 다른 화면에서도 모든 영화에 사용할 수 있다.

### 인증 및 권한

- 회원

### Path Parameters

- `movieId`

### Query Parameters

없음

### Request Body

```json
{ "recommendationItemId": 3301 }
```

- `recommendationItemId`는 추천 카드에서 호출할 때만 보내는 선택값이다

### Response

`200 OK`

```json
{
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

- 관심없음은 `user_movie_dismissals`에 사용자·영화별 1건으로 저장하며 평가·팝콘 버킷·프레임에 영향이 없다
- 추천 외 화면에서 호출하면 `recommendation_item_id`는 NULL이고 교체 후보도 반환하지 않는다
- `recommendationItemId`를 보냈다면 본인 추천 항목이며 경로의 `movieId`와 일치하는지 검증한다
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
- 개인·파티 추천 구분은 세트의 `user_id`·`party_id` 중 어느 값이 존재하는지로 판단하며 별도 `source`는 저장하지 않는다
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

## API-068 OTT 구독 추천

`GET` `/api/v1/recommendations/otts`

| 항목 | 값 |
|---|---|
| 단계 | 확장 |
| 인증 | 회원 |
| 관련 요구사항 | FR-40 |
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

OTT별로 사용자 취향과 유사한 미감상 영화 수를 집계해 구독 효용이 높은 순으로 OTT를 추천한다. OTT마다 유사도 상위 대표 영화 3편을 함께 내려준다.

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
  "insufficientData": false,
  "aggregatedAt": "2026-08-31T00:00:00Z",
  "items": [
    {
      "ott": { "ottId": 1, "code": "NETFLIX", "name": "넷플릭스", "logoUrl": "https://..." },
      "matchCount": 128,
      "subscribed": true,
      "topMovies": [
        {
          "movieId": 807,
          "title": "세븐",
          "posterUrl": "https://...",
          "releaseYear": 1995,
          "similarity": 0.91
        }
      ]
    }
  ]
}
```

- `matchCount`: 취향 매칭 미감상 영화 수
- `subscribed`: 내 구독 OTT(API-026) 여부
- `insufficientData: true`이면 `items`는 빈 배열

### 오류 응답

- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 집계 기준은 Python Consumer가 사용자 취향 벡터와 사전 계산된 영화 Factor로 산출해 Redis에 적재한 **사용자별 영화 점수** 하나다. Spring은 이 점수가 임계치 이상이고 감상 기록·관심없음이 없는 영화를 OTT 제공처별로 집계한다(ADR-0017)
- `items`는 `matchCount` 내림차순으로 정렬한다
- 순위는 절대 편수 기준이며 OTT 카탈로그 크기로 정규화하지 않는다(FR-40)
- `topMovies`는 같은 점수 기준 상위 3편이며 미감상 필터를 동일하게 적용한다
- 영화별 OTT 제공 정보는 TMDb watch provider 매핑(API-025 미정 사항) 확정이 선행 조건이다
- 평가·온보딩 데이터가 기준 미만이면 `insufficientData: true`(가정)

### 상태값

없음

### 미정 사항

- [ ] 추천 점수 임계치 — 설정값, 실데이터로 캘리브레이션
- [ ] 배치 집계 갱신 주기
- [ ] 콜드 스타트(평가 부족 사용자) 처리
- [ ] TMDb watch provider 매핑 확정 선행(API-025)
- [ ] 화면 배치(어느 화면에 노출할지)
