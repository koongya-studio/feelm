# API 명세 — 영화·검색

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-028](#api-028-영화-검색) | 영화 검색 | GET | `/movies/search` | 1차 MVP | 회원 |
| [API-029](#api-029-검색-필터-옵션-조회) | 검색 필터 옵션 조회 | GET | `/movies/filter-options` | 1차 MVP | 공개 |
| [API-030](#api-030-영화-상세-조회) | 영화 상세 조회 | GET | `/movies/{movieId}` | 1차 MVP | 선택 |

## API-028 영화 검색

`GET` `/api/v1/movies/search`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-14, FR-15 |
| 화면 | ⑩ 검색 홈, ⑩-1 검색 결과 |
| 미정 사항 | 있음 |
| 비고 | 검색 화면 구성 미정 |

### 설명

키워드(영화명·배우·감독)와 필터(장르·국가·개봉연도·OTT)로 영화를 검색한다. 키워드 없이 필터만으로도 조회할 수 있다.

### 인증 및 권한

- 회원 전용. 유효한 Access Token이 있어야 검색 결과를 조회할 수 있다.
- 토큰이 없거나 유효하지 않으면 `401 UNAUTHORIZED`, 만료되면 `401 TOKEN_EXPIRED`를 반환한다.
- `watched`는 인증된 사용자 본인의 감상 기록으로 계산한다.

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

배열은 `genre=ACTION&genre=DRAMA`처럼 같은 파라미터 이름을 반복해 전달한다.
인덱스·중첩 표기(`genre[0]`, `genre[].name` 등)는 지원하지 않는다.

### Request Body

없음

### Response

`200 OK`

```json
{
  "totalCount": 24,
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
- `400 VALIDATION_FAILED` 손상된 cursor, 또는 발급 시점과 검색조건·정렬 방식이 다른 cursor
- `400 VALIDATION_FAILED` cursor 정렬값·날짜가 DB 표현 범위를 벗어남
- `400 VALIDATION_FAILED` 검색어·장르·국가에 NUL(U+0000) 포함, 인덱스·중첩 파라미터 표기
- `401 UNAUTHORIZED` Access Token 없음, 형식·서명 오류 또는 Access Token이 아닌 토큰
- `401 TOKEN_EXPIRED` Access Token 만료

### 처리 규칙

- 서로 다른 필터는 AND, 같은 필터의 복수 값은 OR로 결합한다
- `totalCount`는 cursor와 size를 적용하기 전, 현재 검색어·필터에 일치하는 전체 영화 수다.
  필터 시트의 `결과 보기 · N편`과 검색 결과의 `N건` 표시에 사용한다.
- 결과가 없으면 `totalCount: 0`, 빈 `items`와 `200`
- cursor의 연속성(중복·누락 없는 다음 페이지)은 **동일한 검색조건과 변경되지 않은
  데이터**에서만 보장한다. 요청 사이에 평점·제목·크레딧·개봉일·필터 대상 데이터가
  갱신되면 결과가 중복되거나 누락될 수 있으며, 검색 결과의 스냅샷은 유지하지 않는다.
- cursor는 정규화한 `q`, 장르·국가·연도·OTT 필터, 요청 정렬 및 실제 정렬 방식에
  연결된다. 다른 조건으로 재사용하면 `400 VALIDATION_FAILED`다. 검색조건을 바꾸거나
  데이터 갱신 후 결과를 새로 확인하려면 cursor를 버리고 첫 페이지부터 조회한다.
  검색어의 앞뒤 공백·대소문자, 필터의 앞뒤 공백·빈 값·중복·나열 순서는 식별에 영향을
  주지 않는다. 장르·국가 코드의 대소문자는 구분한다. `size` 변경은 허용한다.
  공백 제거·소문자 정규화를 마친 같은 검색어를 실제 검색과 cursor 조건 식별에 함께 사용한다.
  정규화 의미가 변경되기 전에 발급된 cursor는 거부하므로 첫 페이지부터 다시 조회한다.
- cursor 내부 JSON에서 정렬은 문자열 enum, 영화 ID와 값이 있는 정렬 점수는 숫자 타입이어야 한다.
  숫자 enum 및 문자열 숫자의 자동 변환은 허용하지 않는다. 검색조건 식별값이 없는
  이전 형식의 cursor도 거부하므로, 이 경우 첫 페이지부터 다시 조회한다.
- 공백만 있는 `q`는 미입력으로 처리한다. `q` 없이 필터만 사용하고 `sort=RELEVANCE`인
  경우(정렬 생략 포함), `averageRating` 내림차순 → `movieId` 내림차순으로 정렬한다.
  다음 cursor 페이지에도 동일한 순서를 적용한다.
- `watched`는 요청한 로그인 사용자의 해당 영화 감상 기록을 기준으로 계산한다.
  `WATCHED_UNRATED` 또는 `RATED` 상태의 기록이 있으면 `true`이며,
  `LINK_CLICKED`·`EXPIRED` 기록만 있거나 기록이 없으면 `false`이다.
  다른 사용자의 기록은 반영하지 않는다. 인증되지 않은 요청에는 검색 결과를 반환하지 않는다.

### 상태값

- WatchStatus 참조. `watched` 판정은 위 처리 규칙을 따른다.

### 미정 사항

- [ ] 검색 화면 구성(FR-14 비고)
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

- 성공 응답에 `Cache-Control: public, max-age=86400`을 설정한다.
- 장르와 OTT는 실제 영화와 연결된 기준정보만 중복 없이 반환한다. 국가도 영화에 저장된 코드를 중복 없이 반환한다.
- 장르·국가는 `code` 오름차순으로 정렬한다(명세에 기존 정렬 기준이 없어 적용한 결정적 순서).
- OTT는 확정한 5종 `NETFLIX`, `DISNEY_PLUS`, `WATCHA`, `WAVVE`, `TVING`만 위 순서로 반환한다.
  해당 OTT가 DB에 없거나 연결된 영화가 없으면 응답에 넣지 않는다. `ottId`, `code`, `name`은 DB 값을 사용하며 `logoUrl`은 포함하지 않는다.
- 국가명은 JDK 21의 한국어 ISO 국가명을 사용하고 `KR`은 `한국`으로 표기한다. 수집 데이터의 역사·지역 코드는 기존 국가명 표기를 유지한다.
- 한국어 표시명 매핑이 없는 국가 코드가 조회되면 공통 `500 INTERNAL_ERROR`를 반환한다. 코드 자체를 국가명으로 반환하거나 해당 국가를 조용히 누락하지 않으며, 오류 응답에는 1일 공개 캐시를 적용하지 않는다.
- 연도 범위는 `release_date`가 있는 영화의 최소·최대 연도다. 해당 영화가 없으면 `yearRange`는 `{ "min": null, "max": null }`이며, 목록에 값이 없으면 빈 배열이다.
- 현행 관련 스키마에는 활성화 필드가 없으므로 별도 활성 상태 조건을 만들지 않는다.

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

- 선택 인증. 비회원도 `200`으로 조회하며 `myRating`, `myWatchStatus`, `recommendation`은
  `null`, 모든 OTT의 `subscribed`는 `false`다. 비회원 조회는 평가·감상·구독·추천 개인화 테이블을 조회하지 않는다.
- 유효한 Access Token이 있으면 본인의 평가·현재 감상 상태·OTT 구독 정보를 포함한다.
  유효하지 않은 토큰, Refresh Token, 다른 인증 스킴은 기존 선택 인증 정책대로 비회원으로 처리한다.
- 추천 이유는 요청 영화와 일치하는 추천 항목에 한해, 개인 추천은 본인 소유일 때,
  파티 추천은 조회 시점에 해당 `party_members`에 속할 때만 반환한다.
- 영화 상세 `GET`만 선택 인증이며 검색 `GET`과 인접 평가 생성 등 회원 API는 인증이 필요하다.
  OpenAPI에도 인증 없음 또는 `bearerAuth`로 표현한다(`security: [{}, {"bearerAuth": []}]`).

### Path Parameters

- `movieId` (integer/int64, 필수): 서비스 영화 ID. `1~9223372036854775807` 범위의 양의 정수

### Query Parameters

- `recommendationItemId` (integer/int64, 선택): 추천 카드의 항목 ID.
  제공하면 `1~9223372036854775807` 범위의 양의 정수여야 하며 빈 값은 허용하지 않는다.
  영화 일치 및 열람 권한을 확인한 추천 이유만 `recommendation`에 포함한다.

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

- 비회원: `myRating`, `myWatchStatus`, `recommendation`은 `null`, `subscribed`는 `false`
- 응답의 모든 필드를 유지한다. 원제·줄거리·개봉일·상영 시간·이미지 URL·대표 맛처럼
  원천 값이 없는 선택 필드는 `null`이다. 장르·국가·감독·배우·OTT 연결이 없으면 빈 배열이다.
  배우의 배역명, OTT 시청 URL, 내 한줄평·공개 범위도 값이 없으면 명시적인 `null`이다.
- 평가가 없으면 `averageRating=0`, `ratingCount=0`이다. 본인 평가가 없으면 `myRating=null`,
  활성 감상 기록이 없거나 만료(`EXPIRED`) 기록만 있으면 `myWatchStatus=null`이다.
- `recommendation`은 `recommendationItemId`가 조회 영화와 일치하고 열람 권한이 있을 때만 포함한다.
  개인 추천은 본인 소유, 파티 추천은 해당 파티의 현재 `party_members`에 속한 회원에게 허용한다.
  탈퇴한 멤버나 초대만 받은 사용자는 파티 추천 열람 권한이 없다.

### 오류 응답

- `400 VALIDATION_FAILED`: 영화 ID 또는 제공한 추천 ID의 형식 오류, 빈 값, 0·음수·int64 범위 초과
- `404 RESOURCE_NOT_FOUND`: 해당 영화가 존재하지 않음
- `500 INTERNAL_ERROR`: 표시명 매핑이 없는 국가 코드, 잘못된 추천 이유 형식 등 내부 데이터 계약 위반
- 위 오류는 공통 `ErrorResponse`를 `application/json`으로 반환한다.
  `500` 응답은 `{"code":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}`이며,
  내부 예외 메시지·SQL·클래스명·stack trace를 포함하지 않는다.

### 처리 규칙

- 영화와 실제 연결된 장르·국가·인물·OTT만 반환하고 같은 관계를 중복 반환하지 않는다.
- `ottProviders`는 사용자가 구독 중인 OTT를 우선 정렬한다(FR-16).
  같은 구독 그룹 안에서는 API-025와 동일하게 `ottId` 오름차순이다. 비회원은 전체를 `ottId` 순으로 받는다.
  API-029의 검색 필터 목록은 별도의 고정 코드 순서를 사용하며, 이 상세 화면의 구독 그룹 정렬 기준은 `ottId`다.
  지원 OTT는 API-025에서 확정한 Netflix, Disney Plus, Watcha, wavve, TVING 5종이며,
  DB code `NETFLIX`, `DISNEY_PLUS`, `WATCHA`, `WAVVE`, `TVING`으로 제한한다.
  해당 영화에 연결된 승인 OTT만 표시하며, 미승인 OTT는 구독 중이어도 제외한다.
  승인 OTT가 하나도 연결되지 않으면 `ottProviders`는 빈 배열이다.
- `cast`는 TMDb 순서(`movie_people.display_order` 오름차순)의 상위 10명만 반환한다.
  순서가 없는 인물은 뒤에 두고, 같은 순서는 `person_id` 오름차순으로 정렬한다.
- `directors`는 인원 제한 없이 모두 반환하며, `display_order` 오름차순(NULL은 마지막),
  `person_id` 오름차순으로 정렬한다.
- `averageRating`, `ratingCount`는 전체 사용자 집계값이며 차단 관계 필터를 적용하지 않는다
  (D-10 결정). 차단 필터는 한줄평 목록(API-032)에만 적용한다.
- AI 영화 요약은 범위에서 제외한다(FR-34)
- 다른 사용자 소유, 현재 멤버가 아닌 파티, 다른 영화 또는 존재하지 않는
  `recommendationItemId`를 전달하면 `recommendation=null`과 `200`을 반환한다.
- 추천 ID를 생략하면 저장된 추천이 있어도 `recommendation=null`이다.
- 영화 상세는 읽기 전용 조회다. 추천 결과를 재계산하거나 평가·감상·추천 데이터를 변경하지 않는다.

### 상태값

- WatchStatus, RecommendationType 참조

### 미정 사항

- [ ] TMDb 데이터 동기화 주기와 캐시 정책
