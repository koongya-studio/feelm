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
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

평가 데이터를 장르·국가·시대·감독으로 분석한 결과와 사전 정의 취향 키워드 2~5개를 조회한다.

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
    { "code": "DIRECTOR_BINGE", "label": "데이비드 핀처 몰아보기" },
    { "code": "COUNTRY_FOCUS", "label": "한국 영화 집중" },
    { "code": "ERA_LOVER", "label": "2010년대 애호가" }
  ],
  "analyzedAt": "2026-08-30T05:21:00Z"
}
```

아직 생성된 취향 분석 캐시가 없으면 다음과 같이 반환한다.

```json
{
  "ratingCount": 0,
  "insufficientData": true,
  "analysis": {
    "genres": [],
    "countries": [],
    "eras": [],
    "directors": []
  },
  "keywords": [],
  "analyzedAt": null
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 분석 요소는 최소 장르·국가·시대·감독을 포함한다
- Spring은 평가 추가·수정·삭제 트랜잭션에서 `taste_analyses` 캐시를 갱신하고 이 API는 캐시된 결과를 반환한다
- 평가가 하나도 없으면 `taste_analyses` 행을 만들지 않는다
- 캐시가 없거나 캐시의 `keywords`가 비어 있으면 `insufficientData: true`로 반환한다
- `analysis` JSON은 `genres`, `countries`, `eras`, `directors` 배열로 구성하며 누락된 배열은 빈 배열로 반환한다
- `genres`·`countries`·`eras`는 그 사용자에게 나타난 **모든** 종류를 담고 상위 몇 개로 자르지 않는다. 연대 버킷도 `그 이전` 같은 묶음 라벨로 합치지 않는다. `GENRE_OMNIVORE`·`WORLD_EXPLORER`가 종류 수로, `CLASSIC_DIGGER`가 2000년 이전 버킷의 비중 합으로 판정하기 때문이다. 자르거나 합치면 이 세 규칙이 예외 없이 조용히 사라진다. 상위 N명으로 자르는 것은 `directors`뿐이다(1위만 보므로 무해하다)
- 캐시 JSON에 조회 API가 모르는 필드가 추가되어도 알려진 필드만 반환한다
- 키워드는 아래 사전에서만 고르며 사용자당 2~5개다. `code`는 고정이고 `label`만 감독명·장르명·국가명·연대가 채워지는 템플릿이다
- 규칙마다 임계값을 두고 **넘긴 것만** 아래 순서대로 최대 5개를 고른다. 조건을 넘긴 규칙이 2개 미만이면 부분 결과를 내지 않고 `keywords`를 비운다(그러면 위 규칙에 따라 `insufficientData: true`다. 이때도 `ratingCount`와 `analysis` 수치는 그대로 반환하므로, 평가가 40편인데 `insufficientData: true`인 응답이 정상으로 나갈 수 있다). 평가가 15편 미만인 사용자도 만들지 않는다
- 비중(`share`)은 그 사용자가 평가한 편수를 분모로 한다. 영화 하나가 장르를 여러 개 가지므로 장르 비중의 합은 1을 넘을 수 있다
- 시대 라벨은 10년 버킷을 `2010s` 형식으로 쓴다. 키워드 라벨에는 `2010년대`로 바꿔 넣는다
- 같은 축에서 몰림형과 넓이형이 둘 다 조건을 넘기면 **앞 순서 하나만** 내보낸다. `GENRE_DEVOTEE`가 나가면 `GENRE_OMNIVORE`를, `COUNTRY_FOCUS`가 나가면 `WORLD_EXPLORER`를 건너뛴다. `GENRE_FAVORITE`도 `GENRE_DEVOTEE`가 이미 같은 장르를 말했으면 건너뛴다. 편식과 잡식을 나란히 보이지 않으려는 것이고, 그만큼 뒤 순위 규칙이 자리를 채운다
- 각 축의 1위는 편수가 가장 많은 값이며, 편수가 같으면 장르·국가는 `code`, 시대는 `label`, 감독은 `personId`가 작은 쪽으로 정한다. 배열에 담긴 순서에 기대지 않는다
- 이름이나 라벨이 비어 있는 축은 그 규칙을 건너뛴다. 라벨에 `null`이 섞여 나가지 않게 하려는 것이다

| 순서 | `code` | 조건 | `label` 예 |
|---|---|---|---|
| 1 | `DIRECTOR_BINGE` | 1위 감독 4편 이상 | `봉준호 몰아보기` |
| 2 | `GENRE_DEVOTEE` | 1위 장르 비중 45% 이상 | `스릴러 편식` |
| 3 | `COUNTRY_FOCUS` | 1위 국가 비중 55% 이상 | `한국 영화 집중` |
| 4 | `GENRE_OMNIVORE` | 장르 10종 이상 | `장르 잡식` |
| 5 | `WORLD_EXPLORER` | 국가 6개 이상 | `세계 영화 탐험` |
| 6 | `CLASSIC_DIGGER` | 2000년 이전 연대 비중 합 30% 이상 | `고전 발굴가` |
| 7 | `ERA_LOVER` | 1위 연대 비중 40% 이상 | `2010년대 애호가` |
| 8 | `GENRE_FAVORITE` | 1위 장르가 3편 이상이고 평균 ★4.0 이상 | `스릴러 편애` |
| 9 | `GENEROUS_RATER` / `STRICT_RATER` | 전체 평균 ★4.3 이상 / ★3.0 이하 | `후한 평가자` / `깐깐한 평가자` |

앞쪽일수록 그 사용자를 다른 사용자와 가르는 힘이 세다고 보아 순서를 정했다. 장르 편애와 평가 습관은 앞 규칙을 못 넘긴 사용자에게도 줄 수 있어 뒤에 둔다.

취향 리포트(API-061)의 키워드와 `code` 이름이 여러 개 겹치지만 **별개 규칙**이다. 리포트는 반기 집계이고 이쪽은 전체 이력이라 기간과 임계값이 다르며, 리포트에만 있는 `rationale`도 여기에는 없다.

**임계값은 잠정값이다.** 2026-09-24 실측에서 평가 10편 이상인 사용자가 9명뿐이었고(최소 평가 수를 15편으로 올린 것은 이 측정 뒤다) 그중 `COUNTRY_FOCUS`·`GENRE_OMNIVORE`는 전원이, `ERA_LOVER`는 7명이 넘겨 변별력이 낮았다. `CLASSIC_DIGGER`는 1명이고 `GENRE_FAVORITE`은 측정하지 못했다. 새 값을 고를 표본도 없어 초안값을 두고, 데이터가 쌓이면 조정한다(S15P21E106-834).

**최소 평가 수가 15편인 이유.** 온보딩(API-024)은 전 사용자 공통의 고정 10편을 일반 평가와 같은 경로로 `ratings`에 남긴다. 좋아요는 4.0, 싫어요는 2.0으로 바뀌고 둘 다 평가 행이 되므로, 카드를 다 고른 사용자의 분석 입력은 서로 완전히 같다. 2026-09-24 실측에서 그 고정 10편은 `COUNTRY_FOCUS`(0.60)·`GENRE_OMNIVORE`(13종)·`ERA_LOVER`(0.70) 세 개를 넘겨 최소 키워드 수를 정확히 채웠다. 최소 평가 수가 10이면 온보딩만 마친 사용자 전원이 이 세 개를 같은 라벨로 받는다. 그래서 온보딩 카드 수보다 크게 잡았다. **이 값을 온보딩 카드 수 이하로 내리거나 카드 수를 15장 이상으로 늘리면 그 경로가 되살아난다.**

임계값을 다시 볼 때 함께 볼 것이 하나 더 있다. 장르 비중은 한 영화가 장르를 여럿 가져 합이 1을 넘고 국가 비중은 1에 가까우므로, 두 축의 45%와 55%는 같은 의미의 수치가 아니다. 통과율만 보고 임계값을 미세 조정하면 근거 없는 값이 된다.

### 상태값

없음

### 미정 사항

- [x] 키워드 사전 목록과 산출 규칙 — 위 처리 규칙의 표로 확정(S15P21E106-834). 임계값은 데이터가 쌓이면 조정한다
- [x] 분석 최소 평가 수 — 15편. 그 미만은 비중이 표본을 대표하지 못해 키워드를 만들지 않는다. 온보딩 카드 수(10)보다 크게 잡아 온보딩만 마친 사용자가 공통 키워드를 받지 않게 한다(S15P21E106-834)

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
      "flavorCode": "FLAVOR_A",
      "displayName": "오리지널 솔트",
      "color": "#F3DFB2",
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
      "flavorCode": "FLAVOR_A",
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
  "flavorCode": "FLAVOR_A",
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
| 미정 사항 | 없음 |
| 비고 | ADR-0020·S15P21E106-439의 고정 상위 맛 8종 |

### 설명

S15P21E106-439의 고정 `taste_id` 0~7에 대응하는 8개 팝콘 맛의 코드·표시명·색상과
맛이 뜻하는 설명 문장을 내려준다. 내부 `cluster_id=taste_id`는 공개하지 않으며
클라이언트는 표시 기준정보를 하드코딩하지 않고 이 API를 사용한다.

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
      "code": "FLAVOR_A",
      "displayName": "오리지널 솔트",
      "color": "#F3DFB2",
      "description": "있었던 일을 그대로 담은 이야기에 끌려요",
      "sortOrder": 1
    }
  ]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- `code`는 불변, `displayName`·`color`는 변경 가능(FR-06)
- `description`은 각 맛에 실제 배정된 장르 분포를 바탕으로 사람이 확정한 기준정보 문장이며 항상 값이 있다
- 고정된 `clusterId`와 불변 `code`를 `popcorn_flavors`에서 1:1로 관리하며 내부 Cluster ID는 응답에 노출하지 않는다(ADR-0020)
- 기준정보이므로 캐시 가능

| 내부 taste_id | code | displayName | color | description |
|---:|---|---|---|---|
| 0 | `FLAVOR_A` | 오리지널 솔트 | `#F3DFB2` | 있었던 일을 그대로 담은 이야기에 끌려요 |
| 1 | `FLAVOR_B` | 다크초코 | `#5A3623` | 서늘하고 낯선 세계를 즐겨요 |
| 2 | `FLAVOR_C` | 솔티카라멜 | `#CF8F4A` | 웃기면서 씁쓸한 관계 이야기를 봐요 |
| 3 | `FLAVOR_D` | 피스타치오 | `#D6CF9C` | 사람과 시대를 깊게 들여다봐요 |
| 4 | `FLAVOR_E` | 쿠키앤크림 | `#F0E7D3` | 쫓고 쫓기는 긴장감을 즐겨요 |
| 5 | `FLAVOR_F` | 체다치즈 | `#E6AA52` | 가볍게 웃고 기분 좋아지는 영화를 봐요 |
| 6 | `FLAVOR_G` | 스트로베리 | `#E7A79E` | 상상으로 그린 세계를 여행해요 |
| 7 | `FLAVOR_H` | 블루베리 | `#B78A96` | 크고 시원한 모험에 빠져요 |

표시명·색상·설명은 기준정보 수정으로 바꿀 수 있지만 `code`와 내부 `taste_id` 순서의 대응은 바꾸지 않는다.
`taste_id=7`은 서비스에 반영된 영화 기준 장르 믹스이며 장르 근거 부족을 뜻하지 않는다.

### 상태값

없음

### 미정 사항

없음
