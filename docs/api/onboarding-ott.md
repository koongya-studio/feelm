# API 명세 — 온보딩·OTT

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-023](#api-023-초기-취향-카드-목록) | 초기 취향 카드 목록 | GET | `/onboarding/cards` | 1차 MVP | 회원 |
| [API-024](#api-024-초기-취향-제출) | 초기 취향 제출 | POST | `/onboarding/preferences` | 1차 MVP | 회원 |
| [API-025](#api-025-ott-목록-조회) | OTT 목록 조회 | GET | `/otts` | 1차 MVP | 공개 |
| [API-026](#api-026-내-구독-ott-조회) | 내 구독 OTT 조회 | GET | `/users/me/otts` | 1차 MVP | 회원 |
| [API-027](#api-027-내-구독-ott-설정) | 내 구독 OTT 설정 | PUT | `/users/me/otts` | 1차 MVP | 회원 |

## API-023 초기 취향 카드 목록

`GET` `/api/v1/onboarding/cards`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-02 |
| 화면 | ④ 취향 초기 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

온보딩에서 좋아요·싫어요를 선택할 영화 카드 목록을 내려준다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `size` (integer, 기본 10, 최소 1, 최대 10)

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "movieId": 59738,
      "title": "님아, 그 강을 건너지 마오",
      "posterUrl": "https://image.tmdb.org/t/p/w500/...",
      "releaseYear": 2014,
      "genres": ["다큐멘터리", "로맨스"],
      "flavorCode": "FLAVOR_A"
    }
  ]
}
```

- 응답의 모든 필드를 유지한다. 원천 값이 없는 `releaseYear`와 `posterUrl`은 `null`이다.
  `movies.release_date`가 비어 있으면 `releaseYear`가, `movies.poster_url`이 비어 있으면
  `posterUrl`이 `null`이며, 예시는 두 값이 모두 있는 경우다
- 장르가 없는 영화는 `genres`가 빈 배열이다. `null`을 내려주지 않는다
- `movieId`, `title`, `flavorCode`는 항상 값이 있다. 대표 맛이 없는 영화는 카드가 되지 않는다

### 오류 응답

- `400 VALIDATION_FAILED` `size`가 1~10을 벗어남
- `409 ONBOARDING_ALREADY_COMPLETED` 이미 온보딩을 마친 사용자. 온보딩은 재실행하지 않는다

### 처리 규칙

- 카드 수는 `size`이며 기본 10, 최대 10이다. `size`가 10보다 작으면 아래 확정 목록의 앞에서부터 요청한 수만큼 반환한다
- 온보딩 취향 판별 범위와 8개 팝콘 맛 커버리지를 유지하기 위해 업무 `S15P21E106-542`에서 다음 목록과 순서를 확정했다

  | 순서 | 서비스 영화 ID | 영화 | 팝콘 맛 | 선정 기준·취향 판별 신호 | 출처 |
  |---:|---:|---|---|---|---|
  | 1 | 59738 | 님아, 그 강을 건너지 마오 (2014) | `FLAVOR_A` · 오리지널 솔트 | 다큐멘터리, 현실 관찰, 노년·가족 | 카탈로그·439 배정¹ |
  | 2 | 69823 | 콰이어트 플레이스 (2018) | `FLAVOR_B` · 다크초코 | 공포, 괴물, 폐쇄적 긴장감 | 카탈로그·439 배정¹ |
  | 3 | 72564 | 기생충 (2019) | `FLAVOR_C` · 솔티카라멜 | 사회풍자, 블랙코미디, 계층 드라마 | 카탈로그·439 배정¹ |
  | 4 | 59664 | 라라랜드 (2016) | `FLAVOR_C` · 솔티카라멜 | 로맨스, 음악, 감성 드라마 | 카탈로그·439 배정¹ |
  | 5 | 45504 | 인터스텔라 (2014) | `FLAVOR_D` · 피스타치오 | 진지한 SF, 가족애, 긴 러닝타임 | 카탈로그·439 배정¹ |
  | 6 | 123 | 다크 나이트 (2008) | `FLAVOR_E` · 쿠키앤크림 | 범죄, 스릴러, 안티히어로 | 카탈로그·439 배정¹ |
  | 7 | 76119 | 극한직업 (2019) | `FLAVOR_F` · 체다치즈 | 대중 코미디, 액션 코미디 | 카탈로그·439 배정¹ |
  | 8 | 88608 | 인사이드 아웃 2 (2024) | `FLAVOR_G` · 스트로베리 | 가족 애니메이션, 공감과 성장 | 카탈로그·439 배정¹ |
  | 9 | 100 | 센과 치히로의 행방불명 (2001) | `FLAVOR_G` · 스트로베리 | 일본 애니메이션, 판타지, 세계관 | 카탈로그·439 배정¹ |
  | 10 | 58868 | 어벤져스: 엔드게임 (2019) | `FLAVOR_H` · 블루베리 | 블록버스터, 액션·모험·SF, 프랜차이즈 | 카탈로그·439 배정¹ |

  ¹ 영화 ID·제목·연도는 서비스 `movies` 카탈로그, 팝콘 맛은 S15P21E106-439 고정 배정 산출물과
  [ADR-0020](../adr/0020-fixed-439-popcorn-flavor-geometry.md)을 기준으로 검증했다. 최종 구성과 취향 판별 신호는
  S15P21E106-542에서 확정한다.

- 목록 밖의 영화를 인기도에 따라 대신 넣지 않는다. 같은 요청은 항상 같은 순서를 반환한다
- 확정 영화가 없거나 대표 맛(`movies.representative_flavor_id`)이 배정되지 않았으면 해당 영화는 카드가 되지 않으며,
  다른 영화로 대체하지 않는다. 대표 맛 배정은
  [ADR-0020](../adr/0020-fixed-439-popcorn-flavor-geometry.md)의 파이프라인이 수행한다
- 온보딩을 마친 사용자는 카드를 받지 못한다. 제출만 막으면 카드를 고른 뒤에야 거절당한다

### 상태값

없음

### 미정 사항

없음

## API-024 초기 취향 제출

`POST` `/api/v1/onboarding/preferences`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-02 |
| 화면 | ④ 취향 초기 설정 |
| 미정 사항 | 응답에 생성된 평가·프레임 수를 실을지 (S15P21E106-647) |
| 비고 | - |

### 설명

카드에 대한 LIKE/DISLIKE 선택 결과를 저장하고 온보딩을 완료 처리한다. `차차 찾아갈래요`로 건너뛸 수도 있다.
선택은 LIKE 4.0점·DISLIKE 2.0점의 평가로도 저장되어 프레임과 팝콘이 함께 생긴다(팀 결정 2026-09-18, S15P21E106-647).

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{
  "preferences": [
    { "movieId": 550, "preference": "LIKE" },
    { "movieId": 680, "preference": "DISLIKE" }
  ],
  "skipped": false
}
```

- 제외한 카드는 배열에 포함하지 않는다
- `preferences`는 최대 40개다. 현재 API-023 확정 카드는 10개지만 API-024의 기존 방어 상한은 유지한다
- `skipped: true`이면 `preferences`는 빈 배열
- `movieId`는 JSON 정수, `preference`는 문자열, `skipped`는 boolean이다. 다른 JSON 타입은 값이 바뀔 수 있어
  변환하지 않고 거절한다

### Response

`200 OK`

```json
{ "onboardingCompleted": true, "savedCount": 2, "skipped": false }
```

### 오류 응답

- `400 VALIDATION_FAILED` `preference` 값 오류, 중복 `movieId`, 존재하지 않는 `movieId`,
  `preferences`·`skipped` 누락, `skipped: true`인데 `preferences`가 비어 있지 않음, 필드의 JSON 타입 불일치
- `409 ONBOARDING_ALREADY_COMPLETED`

### 처리 규칙

- 클라이언트가 보낸 `LIKE`/`DISLIKE`만 저장한다. `⊖`로 제외한 카드는 보내지 않아 저장하지 않는다
- `preferences`와 `skipped`는 모두 필수다. 빠진 필드를 기본값으로 채우면 건너뛰기 의사를 밝히지 않은 빈 제출이
  온보딩을 완료해 버린다
- 필드의 JSON 타입이 다르면 변환하지 않고 거절한다. `preference: 0`을 선언 순서로 읽거나 `movieId: 101.9`의
  소수점 아래를 버리면 보낸 값과 저장한 값이 다른데도 `200`이 나간다
- 온보딩 LIKE는 `4.0`, DISLIKE는 `2.0`의 평가로 저장한다. 프레임·팝콘·감상 기록·영화 평균 별점 갱신은
  API-036 평가 생성과 같은 규칙을 따른다. `onboarding_preferences` 행도 그대로 남긴다
- 이미 평가가 있는 영화는 건너뛰고 덮어쓰지 않는다
- 온보딩 평가에는 한줄평이 없다. 공개 범위와 스포일러 표시는 평가 생성의 기본값을 따른다
- 저장과 온보딩 완료 전이는 한 트랜잭션이며, 같은 사용자의 동시 제출은 계정 행 잠금으로 직렬화해 하나만 성공한다.
  실패한 요청은 아무것도 저장하지 않고 `409`를 받는다
- 성공한 온보딩 제출마다 [이벤트·Redis 계약](./streaming-contract.md)의 `ONBOARDING` 이벤트 한 건을 같은
  트랜잭션의 아웃박스에 기록한다. 건너뛴 제출도 `preferenceCount=0`으로 발행하여 기존 개인 K0 인기순
  후보 생성을 시작한다. 평가·취향 선택을 임의로 만들지 않는다
- 저장 후 초기 추천(API-045)이 가능하도록 추천 입력 버전을 갱신한다. 추천 세트 저장이 들어오기 전까지는
  `ONBOARDING` 이벤트 발행이 이 역할을 대신한다

### 상태값

- OnboardingPreference: `LIKE`, `DISLIKE`

### 미정 사항

- 응답에 생성된 평가·프레임 수를 실을지 (S15P21E106-647)

## API-025 OTT 목록 조회

`GET` `/api/v1/otts`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-03, FR-16 |
| 화면 | ④-1 OTT 설정, ⑪-5 OTT 관리 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

서비스가 지원하는 OTT 기준정보를 내려준다.

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
    { "ottId": 1, "code": "NETFLIX", "name": "넷플릭스", "logoUrl": "https://..." }
  ]
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 지원 OTT는 다섯 개이며 TMDb watch provider와 다음과 같이 매핑한다.

  | `code` | 이름 | TMDb watch provider id |
  |---|---|---|
  | `NETFLIX` | 넷플릭스 | 8 |
  | `DISNEY_PLUS` | 디즈니+ | 337 |
  | `WATCHA` | 왓챠 | 97 |
  | `WAVVE` | 웨이브 | 356 |
  | `TVING` | 티빙 | 1883 |

- 신규 환경은 승인 CSV(`backend/scripts/samples/ott-provider-mapping.csv`) 5종의 새 Flyway seed
  `V202609142240__seed_approved_ott_catalog.sql`로 초기화한다. 같은 code/provider 쌍의 기존 행과
  모든 참조는 보존하고 없는 항목만 추가한다. 식별 관계 충돌은 seed 전체 행 변경과 기동을 실패시킨다
- 이후 콘텐츠 적재는 같은 CSV 기준으로 추가·메타데이터 갱신을 수행한다. 목록에 추가하거나
  이름·로고를 바꾸려면 이 표와 CSV를 함께 고친다. 적용된 seed는 수정하지 않고 필요 시 새 버전을 만든다
- `logoUrl`은 값이 없으면 `null`이다. 승인 CSV와 seed가 로고 경로를 필수로 요구하므로 지원 5종에는
  값이 있지만, `otts.logo_url`과 응답 타입은 값 없음을 허용한다. `ottId`, `code`, `name`은 항상 값이 있다
- 서비스는 OTT 지원을 종료하지 않는다(2026-09-14 사용자 확정). 종료·비활성화와 이에 따른
  기존 구독 일괄 해제는 지원하지 않는다. 적재 경로는 추가와 갱신만 자동화한다.
  CSV에서 줄을 지워도 `otts` 행은 남는다. `user_otts`, `movie_ott_providers`, `watch_records`가
  모두 삭제를 제한하며, DB 정책은 참조가 없어도 기준정보를 물리 삭제하지 않는다.
  존재하는 ottId의 구독 설정·유지·사용자에 의한 해제는 기존 계약대로 허용한다.
  초기화 적용 전 점검·복구 및 지원 목록 보존 절차는
  [OTT 운영 안내](../../backend/scripts/OTT-CATALOG-OPERATIONS.md)를 따른다
- 서비스 `ottId`는 TMDb provider ID와 별개다. 적재는 기존 ID와 code/provider 쌍을 보존하며
  다른 쌍과의 충돌은 전체 카탈로그 적용을 실패시킨다
- `ottId` 오름차순으로 정렬한다. 기존 환경의 식별자를 보존하므로 CSV 순서와 항상 같다고 가정하지 않는다
- 기준정보이므로 캐시 가능. 응답에 사용자별 값이 없어 공유 캐시에도 담을 수 있다(`Cache-Control`에
  `max-age=86400`과 `public`. 지시자 순서는 의미가 없다)

### 상태값

없음

### 미정 사항

없음

## API-026 내 구독 OTT 조회

`GET` `/api/v1/users/me/otts`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-03 |
| 화면 | ⑪-5 OTT 관리 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

사용자가 구독 중으로 설정한 OTT 목록을 조회한다.

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
{ "ottIds": [1, 3] }
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 설정하지 않았으면 빈 배열
- 응답의 `ottIds`는 오름차순이다

### 상태값

없음

### 미정 사항

없음

## API-027 내 구독 OTT 설정

`PUT` `/api/v1/users/me/otts`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-03 |
| 화면 | ④-1 OTT 설정, ⑪-5 OTT 관리 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

구독 OTT 목록을 전체 교체한다. 온보딩과 설정 화면에서 공통으로 사용한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "ottIds": [1, 3] }
```

### Response

`200 OK`, API-026과 동일 본문

### 오류 응답

- `400 VALIDATION_FAILED` 존재하지 않는 `ottId`

### 처리 규칙

- 멱등. 빈 배열이면 전체 해제
- 요청의 중복 `ottId`는 제거하고 저장하므로 응답 길이가 요청보다 짧을 수 있다
- 응답의 `ottIds`는 API-026과 같은 오름차순이다
- 한 번에 보낼 수 있는 `ottId`는 50개까지이며 초과하면 `VALIDATION_FAILED`다. 지원 OTT 수보다 큰
  방어값이며 중복 제거 전의 요청 원소 수로 판단한다
- 변경 시 영화 상세의 OTT 정렬(FR-16)과 검색 필터 기본값에 반영된다

### 상태값

없음

### 미정 사항

없음
