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
| [API-068](#api-068-ott-구독-추천) | OTT 구독 추천 | GET | `/recommendations/otts` | 2차 MVP | 회원 |

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

다국어 임베딩 모델로 생성한 768차원 영화 벡터의 Cosine Similarity가 높은 영화를 추천한다. MVP 저장·검색 계약은 `multilingual-e5-base` 계열의 768차원 벡터이며, 안정적인 모델 식별자와 Revision은 `model_version`으로 관리한다(ADR-0014).

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
      "averageRating": 4.2,
      "similarity": 0.87
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
      "flavorCode": "FLAVOR_A",
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
- `TASTE`는 이벤트마다 PostgreSQL의 현재 평가·온보딩 선택을 한 snapshot으로 다시 읽어 결정론적으로
  재계산한다. 같은 영화는 실제 별점이 온보딩 선택보다 우선하고, 온보딩 LIKE/DISLIKE는 DB 별점으로
  저장하지 않고 ALS Fold-in adapter의 확정값으로만 사용한다. 서로 다른 유효 입력 영화 수와 관계없이
  가용한 ALS·콘텐츠 성분으로 후보를 계산한다. 콘텐츠 산출물은 선택 사항이며, 한 성분 이상이 점수를
  낼 수 있을 때 각 지원 모집단의 백분위를 결합해 `taste` 최대 500편을 게시한다. 입력이 없거나 두
  성분 모두 사용자 프로필을 만들 수 없으면 서비스 가능한 영화에 `0.0` 동점 점수를 부여하고
  `movieId` 오름차순으로 `taste`를 게시한다.
- `DISCOVERY`는 내부
  8×128 영화 그룹에서 별도로 고른 `discovery` 최대 100편이며, Spring이 두 목록을 합쳐
  `TASTE 2 + DISCOVERY 1`을 구성한다. 그룹 결과가 없거나 부족하면 TASTE 상위 500편 밖의 개인 후보를
  우선 사용하고, 그래도 부족하면 TASTE 중복을 허용해 100편을 채운다. 서비스 가능한 고유 후보가
  100편 미만일 때만 그 수만 게시한다(FR-10·FR-12).
- DISCOVERY는 저이력·고궁합 그룹을 개인별로 고른 뒤, 그룹 안에서는 모든 사용자에게 같은
  `max(TMDB 투표 수 백분위, KOBIS 누적 관객 백분위)` 순서로 우선 10편씩 모집한다. 상위 10개
  그룹만으로 100편을 채우지 못하면 다음 적격 그룹을 같은 방식으로 탐색하고, 그래도 부족하면
  적합도 순으로 그룹별 11번째 이후 후보를 모집한다. 모집된 최대 100편은 사용자 개인 예측
  점수로 최종 정렬한다.
- 서비스 DB에 없는 영화, 평가한 영화와 관심없음 영화는 제외한다. 감상·OTT 클릭 이력은 제외나 재가중에 사용하지 않는다
- 같은 `generationId`의 버퍼가 있으면 같은 `recommendationSetId`를 반환한다. 새 generation을 받으면 새 세트를 생성하며 관심없음은 세트 내 같은 타입 후보로 교체한다(API-047)
- 온보딩을 건너뛰고 평가가 없는 사용자의 개인 후보 보충 순서 개선과 후보 모집단 밖 POPULAR 확장은
  S15P21E106-483에서 다룬다.
  `discovery-popularity`는 선택된 그룹 안의 공통 후보 진입 순서이며 fallback 목록이 아니다
- Python Consumer가 Redis에 갱신한 TASTE·DISCOVERY 후보를 Spring이 종류별 점수로 정렬하고 동점을 결정적으로 섞은 뒤 타입별 최대 20편을 독립 버퍼로 저장한다. 조회 시 TASTE 2편과 DISCOVERY 1편을 꺼낸다
- 버퍼는 PostgreSQL `recommendation_items`에 저장하고 `exposed_at`으로 노출 여부를 구분한다. Consumer가 전달한 구조화된 `reasons[]` 원본도 함께 저장하며, 사용자 문구 생성은 별도 단계에서 처리한다.
- Consumer는 `vector`·`candidates`·version 키를 원자 게시한다. Spring은 `candidates.taste`와
  `candidates.discovery`를 읽어 제외·재정렬하고 최종 세트를 생성한다. 별도 Redis `topn`은
  사용하지 않는다. 후보가 없거나 유효하지 않으면 짧은 재시도 후 `503 RECOMMENDATION_UNAVAILABLE`을 반환한다.
- 새 세트 생성 시 추천 이력(FR-33)에 시각·유형·이유·후보를 저장한다
- `reason.factors[].type`: `GENRE`, `DIRECTOR`, `ACTOR`, `COUNTRY`, `ERA`, `SIMILAR_MOVIE`, `NEW_FLAVOR`(가정)

### 구현·연동 상태

- S15P21E106-415에서 DISCOVERY 후보 생성 정책과 Consumer 구현을 완료했다.
- S15P21E106-471에서 Redis Candidate v3 `taste`/`discovery` 그룹화와 `generationId` writer를
  반영했고, S15P21E106-481에서 TASTE 후보별 구조화 `reasons[]`를 추가해 Candidate v4가 됐다.
  S15P21E106-488은 전체 평가 또는 온보딩 이력으로 계산한 저이력 그룹 적합도와 상위 기여 비율을
  DISCOVERY 전용 `reasons[]` 원소로 추가해 Candidate v5로 올렸다. S15P21E106-505는 입력 수 제한을 제거하고
  DISCOVERY 보충 후보용 `DISCOVERY_PERSONAL_SCORE`를 추가해 Candidate v6로 올린다. 현재 `develop`에는
  Candidate v6 Spring reader와 TASTE 2 + DISCOVERY 1 조립 구현이 없으므로, 위 처리 규칙은 Backend가
  구현·통합 검증해야 하는 소비 계약이다. S15P21E106-164는 기존 v4 배열 계약을 유지하고,
  S15P21E106-498에서 v6 배열 계약 대응을 진행한다. 전환은 v5/v6 reader 선반영, Consumer v6 전환과 활성 후보
  재게시, v6·`generationId` 검증, v5 호환 제거 순서로 진행한다.
- S15P21E106-482에서 그룹·그룹 내부 대중성 번들의 HDFS 게시, 두 URI 동시 활성화와 rollback을 검증한다.
- S15P21E106-483은 개인 후보 보충 순서를 개선하고 후보 모집단 밖까지 확장하는 별도 POPULAR 목록의
  정책·산출물·서비스 연동을 담당한다.

### 상태값

- RecommendationType `TASTE`, `DISCOVERY`

### 미정 사항

- [ ] Candidate v6 Spring reader, 두 목록 조립과 감상 변경 재계산을 구현·통합 검증해야 함
- [ ] TMDB/KOBIS 공개 신호 후보 자격 하한을 합의하고 S15P21E106-482에서 운영 활성화해야 함
- [x] 콜드 스타트 임시 전략 — 입력이 없으면 `0.0` 동점 TASTE 게시
- [ ] 콜드 스타트 POPULAR 순서·후보 모집단 확장 — S15P21E106-483 이후
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
- 현재 세트의 버퍼를 모두 소진한 뒤 다시 요청하면 Redis의 TASTE 최대 500편·DISCOVERY 최대 100편과
  최신 행동 Feature를 읽어 재정렬하고, 기존 노출분을 제외한 후보로 새 추천 세트를 생성한다
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
    "flavorCode": "FLAVOR_B",
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
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-40 |
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

Redis의 사용자별 TASTE 추천 후보 최대 500편에서 OTT별로 시청 가능한 영화 수를 집계해 순위를 제공한다. OTT마다 후보 점수 상위 대표 영화 3편을 함께 내려준다. 취향 입력이 없는 사용자의 후보도 그대로 사용하며 이때의 순위는 취향 일치도나 인기순을 뜻하지 않는다.

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
          "releaseYear": 1995
        }
      ]
    }
  ]
}
```

- `matchCount`: TASTE 후보 최대 500편 중 해당 OTT에서 볼 수 있는 미감상·미관심없음 영화 수. 전체 OTT 카탈로그의 취향 영화 수가 아니다
- `subscribed`: 내 구독 OTT(API-026) 여부
- 현재 Candidate가 있으면 취향 입력이 0건이어도 `insufficientData: false`다. 이 필드는 후속 콜드 스타트 정책 확장을 위해 유지한다

### 오류 응답

- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 집계 기준은 Redis `candidates.taste` 최대 500편 전체다. Spring은 점수 임계치나 별도의 상위 200편 컷을 적용하지 않는다. 취향 입력이 없어 Consumer가 `0.0` 동점 후보를 게시한 경우에도 같은 범위로 집계한다
- 평가한 영화, 관심없음 영화, 활성 감상 기록(`LINK_CLICKED`, `WATCHED_UNRATED`, `RATED`)이 있는 영화는 제외한다. `EXPIRED` 기록만 있는 영화는 포함한다. `LINK_CLICKED`의 `expiresAt`이 지났지만 아직 상태가 갱신되지 않은 경우도 만료로 보고 포함한다
- `items`는 `matchCount` 내림차순·`ottId` 오름차순으로 정렬하고, 후보 영화가 0편인 OTT는 생략한다
- 순위는 절대 편수 기준이며 OTT 카탈로그 크기로 정규화하지 않는다(FR-40)
- `topMovies`는 후보 점수 내림차순·동점 `movieId` 오름차순 상위 3편이며 같은 제외 필터를 적용한다. Redis 점수는 상대 순위만 보장하므로 `similarity`나 취향 일치 확률로 응답하지 않는다
- `aggregatedAt`은 요청 시점의 UTC 시각이다. 후보가 없거나 Redis를 읽지 못하면 짧게 한 번 재시도한 뒤 `503 RECOMMENDATION_UNAVAILABLE`을 반환한다
- 영화별 OTT 제공 정보는 API-025의 TMDb watch provider 매핑을 사용한다

### 상태값

없음

### 미정 사항

- [ ] S15P21E106-483의 검증된 POPULAR 목록이 준비되면 취향 입력이 없는 사용자의 OTT 집계와 화면 문구에 반영
- [ ] 화면 배치(어느 화면에 노출할지)
