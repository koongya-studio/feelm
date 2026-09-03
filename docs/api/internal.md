# 내부 API 명세 — Spring Boot ↔ FastAPI

> **대체된 초안:** ADR-0012가 ADR-0016을 거쳐 ADR-0017로 대체되어 FastAPI 추천 서빙 서비스는 사용하지 않습니다.
> 아래 내용은 이전 설계의 추적을 위해 보존하며 현재 구현 계약으로 사용하지 않습니다. 현재 흐름은
> 현재 계약은 `Spring Boot → Kafka → Python Consumer / Spark Structured Streaming → Redis → Spring Boot`입니다.

[ADR-0012](../adr/0012-realtime-recommendation-serving-location.md)에 따라 실시간 추천 서빙 계산(Fold-in·재정렬·파티 집계·XAI·취향 분석)은
FastAPI 서비스가 수행하고, Spring Boot가 이 문서의 내부 API로 호출합니다. **클라이언트(React)에 공개되는 API가 아니며**, 공개 계약은
[API 명세](./README.md)를 따릅니다.

> ⚠️ 이 문서는 BE가 작성한 **초안**입니다. 데이터 접근 경계와 입출력 형식은 AI 담당 리뷰 후 확정하며, 협의 항목은 각 절의 미정 사항에 표시했습니다.

## 공개 API ↔ 내부 API 매핑

| 공개 API | 내부 호출 | 비고 |
|---|---|---|
| API-045 기본 추천 조회 | `POST /internal/v1/recommendations` | 세트 버퍼 미스·입력 변경 시에만 호출 |
| API-046 추가 영화 추천 | `POST /internal/v1/recommendations` (`excludeMovieIds`에 기존 노출분 포함) | 버퍼 소진 시에만 호출 |
| API-047 관심없음 표시 | 내부 호출 없음 | 버퍼의 같은 타입 다음 후보로 교체, 소진 시 `replacement: null`. 해당 영화는 다음 버퍼 생성 시 `excludeMovieIds`로 제외 |
| API-058 파티 영화 추천 | `POST /internal/v1/party-recommendations` | |
| API-040 취향 분석·키워드 | `POST /internal/v1/taste-analysis` | 평가 변경 후 비동기 갱신 |
| API-031 유사 영화 추천 | `GET /internal/v1/movies/{movieId}/similar` | 배치 사전 계산 결과 조회 |
| (없음 — 운영) | `GET /internal/v1/health` | Spring 헬스체크·서킷브레이커 판단 |

## 공통 규약

### 주소·전송

- Base URL: `http://recommendation:8000/internal/v1` — 같은 Docker 네트워크의 서비스명으로 접근하며 외부에 노출하지 않는다 (가정)
- 경로의 `v1`은 공개 API처럼 유지해야 하는 호환성 약속이 아니라, 두 서비스가 따로 재배포되는 과도기에 신구 버전이 잠깐 공존할 수 있게 하는 장치다. 큰 계약 변경 시에만 `v2`를 병행했다가 이전 버전을 제거한다
- `Content-Type: application/json; charset=utf-8`, 키는 camelCase, 시간은 ISO 8601 UTC — 공개 API와 동일
- 호출 방향은 **Spring → FastAPI 단방향**. FastAPI는 Spring을 호출하지 않는다

### 인증

- 내부망 전용이지만 방어선으로 공유 시크릿 헤더 `X-Internal-Token`(환경변수)을 검증한다 (가정)
- 사용자 인증은 Spring이 완료한 상태이므로 내부 요청에는 토큰 대신 `userId`를 본문으로 전달한다

### 데이터 접근 경계 (2026-08-31 MR !22 리뷰 합의)

| 데이터 | 접근 주체 | 방식 |
|---|---|---|
| Item Factor, 콘텐츠 유사도 | FastAPI | 배치 산출물(Parquet/Redis)을 기동 시 메모리 로드 |
| Candidate Top-500 | FastAPI | Redis 조회 |
| 사용자 평가·관심없음·감상 제외 목록 | **Spring이 요청 본문으로 전달** | 쓰기·트랜잭션 의존을 만들지 않기 위해 본문 전달 유지 |
| 영화 임베딩(pgvector) | FastAPI | PostgreSQL **읽기 전용 롤**로 직접 조회 |
| 행동 Feature(상세 조회·OTT 클릭 등) | FastAPI | Spark Streaming이 Redis에 적재한 것을 추천 생성 시 조회 |
| 영화 메타데이터(제목·포스터 등) | Spring | 내부 응답은 `movieId`만 반환, 메타데이터는 Spring이 결합 |
| 추천 이력·세트 저장 | Spring | 내부 응답을 받아 PostgreSQL에 저장 |

FastAPI의 PostgreSQL 접근은 **영화 임베딩(pgvector) 읽기 전용**으로 한정합니다. 평가·제외 목록은 쓰기·트랜잭션 의존을 피하기 위해 Spring이 본문으로 전달합니다(평가가 많은 사용자도 1인 수백 건 수준). 이 경계는 MR !22 리뷰에서 합의했습니다.

### 영화 ID 기준

- 내부 API의 `movieId`는 **서비스 DB ID**다. MovieLens ID와의 매핑은 FastAPI 내부에서 처리하며 계약에 노출하지 않는다
- ALS 모델에 없는 신작은 오류로 제외하지 않고 장르·감독·배우·키워드·임베딩 기반 **콘텐츠 점수만으로 점수화**한다

### 추천 캐시 무효화 정책

| 신호 | 예 | 처리 |
|---|---|---|
| 강한 신호 | 별점 등록·변경·삭제, 온보딩 변경 | `inputVersion` 증가 + 세트 버퍼 즉시 무효화 → 다음 요청에서 IN-01 재호출 |
| 관심없음 | 추천 카드 관심없음 | **버전 유지, 세트 유지** — 현재 버퍼의 같은 타입 후보로 교체하고 다음 버퍼 생성 시 `excludeMovieIds`로 제외 (MR !22 합의: 대안 A) |
| 약한 신호 | 영화 상세 조회, OTT 링크 클릭 | 버전 유지. Spark Streaming이 Redis 행동 Feature만 갱신하고, TTL 만료나 다음 버퍼 재생성 때 반영 |

### 타임아웃·장애 격리 (NFR-05)

- Spring 호출 타임아웃: 개인 추천 2초, 파티 추천 5초, 취향 분석 5초 (설정값)
- 재시도는 하지 않는다. 타임아웃·5xx·연결 실패 시 Spring은 공개 API에서 `503 RECOMMENDATION_UNAVAILABLE`을 반환하고 영화·검색·평가 기능은 유지한다
- Spring은 서킷브레이커(Resilience4j, 가정)로 연속 실패 시 일정 시간 호출을 차단하고 `/health`로 복구를 확인한다

### 오류 응답

공개 API와 같은 구조를 사용한다.

```json
{ "code": "MODEL_NOT_READY", "message": "Item Factor가 아직 로드되지 않았습니다.", "details": {} }
```

| HTTP | code | 의미 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 필수 필드 누락·형식 오류 |
| 401 | `INTERNAL_TOKEN_INVALID` | 공유 시크릿 불일치 |
| 404 | `MOVIE_NOT_IN_MODEL` | 서비스 DB에 없거나 임베딩·모델 정보가 전혀 없는 영화 — IN-04 유사 조회 시. IN-01은 신작을 콘텐츠 점수 폴백으로 처리하므로 이 오류를 내지 않는다 |
| 503 | `MODEL_NOT_READY` | 기동 직후 Factor 미로드, 배치 교체 중 |
| 500 | `INTERNAL_ERROR` | 계산 오류 |

## 엔드포인트

### IN-01 개인 추천 계산

`POST /internal/v1/recommendations`

Fold-in으로 사용자 벡터를 갱신하고 후보를 재정렬해 `TASTE`/`DISCOVERY` 구성으로 반환한다. 후보를 **한 번에 20편(설정값)** 반환하고 Spring이 세트 버퍼로 저장해 3편씩 노출하는 구조라, 추가 추천·관심없음 교체 시 매번 호출하지 않는다.

**Request**

```json
{
  "userId": 1024,
  "size": 20,
  "ratings": [
    { "movieId": 550, "score": 5 },
    { "movieId": 680, "score": 2 }
  ],
  "onboardingPreferences": [
    { "movieId": 27205, "preference": "LIKE" }
  ],
  "excludeMovieIds": [550, 680, 27205, 13],
  "flavorCounts": { "FLAVOR_X": 12, "FLAVOR_Y": 0 },
  "inputVersion": "v41"
}
```

| 필드 | 설명 |
|---|---|
| `size` | 반환할 후보 수. 기본 20 — 설정값 `recommendation.buffer-size`. 구성은 항상 기본 규칙(TASTE 2 + DISCOVERY 1 인터리브, 후보 부족 시 TASTE로 채움) |
| `ratings`·`onboardingPreferences` | Fold-in 입력. Spring이 PostgreSQL에서 조회해 전달 |
| `excludeMovieIds` | 감상 기록·관심없음·현재 세트. FastAPI는 추가 판단 없이 제외만 한다 |
| `flavorCounts` | 맛별 팝콘 수 — `DISCOVERY`(임계치 미만 맛) 후보 선정용 |
| `inputVersion` | Spring이 관리하는 추천 입력 버전. 응답에 그대로 반영(에코) |

**Response** `200 OK`

```json
{
  "inputVersion": "v41",
  "modelVersion": "als-2026-08-30",
  "items": [
    {
      "movieId": 807,
      "type": "TASTE",
      "score": 0.91,
      "flavorCode": "FLAVOR_X",
      "reason": {
        "summary": "스릴러를 높게 평가하셨어요",
        "factors": [
          { "type": "GENRE", "label": "스릴러" },
          { "type": "SIMILAR_MOVIE", "label": "파이트 클럽", "movieId": 550 }
        ]
      }
    }
  ]
}
```

- 콜드 스타트(평가·온보딩 모두 없음)면 인기 기반 후보로 `TASTE`를 채우고 `reason.summary`에 명시한다 (가정)
- `items`는 `TASTE` 2편 + `DISCOVERY` 1편이 반복되는 **인터리브 순서**로 정렬해 반환한다. Spring은 응답 전체(20편)를 PostgreSQL `recommendation_items`에 저장하고(`exposed_at` NULL = 미노출, MR !22 합의: PostgreSQL 저장안) API-045·046에서 앞에서부터 3편씩 노출하며, API-047 관심없음은 버퍼의 다음 같은 타입 후보로 교체한다. 같은 타입 후보가 소진되면 `replacement: null`을 반환하며 이때도 내부 API를 호출하지 않는다
- FastAPI 재호출은 **추천 입력 변경(강한 신호) 또는 버퍼 소진 시에만** 일어난다
- Fold-in·재정렬 시 Redis의 행동 Feature(상세 조회·OTT 클릭 등)를 반영한다
- ALS 모델에 없는 신작 후보는 제외하지 않고 콘텐츠 점수(장르·감독·배우·키워드·임베딩)만으로 점수화한다
- `modelVersion`은 Spring이 추천 세트에 저장한다(재현·디버깅용)

**오류**: 공통 오류만 해당

### IN-02 파티 추천 계산

`POST /internal/v1/party-recommendations`

파티원 벡터를 집계식으로 결합해 함께 보기 좋은 영화를 반환한다. 집계식(Average / Least Misery / 개선식)은 FR-22 비교 실험 후 확정한다.

**Request**

```json
{
  "partyId": 9001,
  "size": 3,
  "members": [
    { "userId": 1024, "ratings": [{ "movieId": 550, "score": 5 }] },
    { "userId": 2048, "ratings": [{ "movieId": 550, "score": 4 }] }
  ],
  "excludeMovieIds": [550]
}
```

- `excludeMovieIds`: 파티원 중 한 명이라도 감상한 영화 (가정, API-058 미정 사항과 연동)

**Response** `200 OK`

```json
{
  "modelVersion": "als-2026-08-30",
  "aggregation": "LEAST_MISERY",
  "items": [
    {
      "movieId": 807,
      "type": "PARTY",
      "score": 0.84,
      "reason": {
        "summary": "모두가 좋아하는 스릴러",
        "memberFit": [
          { "userId": 1024, "score": 0.91 },
          { "userId": 2048, "score": 0.78 }
        ]
      }
    }
  ]
}
```

### IN-03 취향 분석·키워드 계산

`POST /internal/v1/taste-analysis`

평가 데이터를 장르·국가·시대·감독으로 집계하고 사전 정의 키워드를 산출한다. Spring이 평가 변경 후 비동기로 호출하고 결과를 `taste_analyses`에 저장한다.

**Request**

```json
{
  "userId": 1024,
  "ratings": [{ "movieId": 550, "score": 5 }]
}
```

- 영화의 장르·국가·연도·감독은 FastAPI가 배치 메타데이터(Parquet)에서 결합한다 (가정 — Spring이 전달하는 방식과 협의)

**Response** `200 OK` — API-040 응답의 `analysis`·`keywords`와 동일 구조

**오류**: `400 INSUFFICIENT_DATA` 평가 수가 기준(설정값) 미만 — Spring은 `insufficientData: true`로 저장

### IN-04 유사 영화 조회

`GET /internal/v1/movies/{movieId}/similar?size=10`

콘텐츠 기반 유사도(배치 사전 계산)를 조회한다.

**Response** `200 OK`

```json
{
  "baseMovieId": 550,
  "items": [
    { "movieId": 807, "similarity": 0.87, "reason": { "summary": "같은 감독, 스릴러", "factors": [{ "type": "DIRECTOR", "label": "데이비드 핀처" }] } }
  ]
}
```

**오류**: `404 MOVIE_NOT_IN_MODEL`

### IN-05 헬스체크

`GET /internal/v1/health`

**Response** `200 OK`

```json
{ "status": "UP", "modelVersion": "als-2026-08-30", "factorLoadedAt": "2026-08-31T03:00:00Z", "candidateKeyCount": 182034 }
```

- Factor 미로드·배치 교체 중이면 `503` + `MODEL_NOT_READY`. Spring 서킷브레이커의 복구 판단에 사용한다

## 배치 산출물과의 관계

- FastAPI가 소비하는 배치 산출물(Item Factor Parquet 경로, Candidate Redis 키, 영화 메타데이터)의 형식은 **Redis 키 설계·HDFS 레이아웃 문서(작성 예정)** 에서 정의한다
- 배치가 새 모델을 적재하면 FastAPI는 무중단으로 Factor를 교체하고 `modelVersion`을 올린다 (방식 미정 — 파일 스왑 vs 리로드 API)
- Spark Streaming은 상세 조회·OTT 클릭 등 행동 Feature를 Redis에 지속 적재하며, 키 형식은 같은 문서에서 정의한다

## 미정 사항 (AI 담당 협의)

- [ ] 행동 Feature 목록·가중치 — Spark Streaming 설계 시 확정
- [ ] 약한 신호 캐시 TTL 값 (설정값)
- [ ] IN-03의 영화 메타데이터 결합 주체 (FastAPI 배치 데이터 vs Spring 전달)
- [ ] 내부 인증: 공유 시크릿 헤더(가정) vs 네트워크 격리만
- [ ] 모델 교체 방식(무중단 리로드)과 `MODEL_NOT_READY` 기간의 공개 API 동작(503 vs 이전 캐시 유지)
- [ ] 추천 이유 문장 생성 방식: 템플릿(가정) vs LLM — IN-01·IN-02 `reason.summary`
- [ ] 콜드 스타트 정책(인기 기반 가정)과 `DISCOVERY` 임계치 값
- [ ] 타임아웃·서킷브레이커 임계치 (설정값 초기값)
