# API 명세 — 추천

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-031](#api-031-유사-영화-추천) | 유사 영화 추천 | GET | `/movies/{movieId}/similar` | 1차 MVP | 선택 |
| [API-045](#api-045-기본-추천-조회) | 기본 추천 조회 | GET | `/recommendations` | 1차 MVP | 회원 |
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
        "summary": "좋아하신 영화와 스릴러 장르가 같아요.",
        "factors": [
          { "type": "GENRE", "label": "스릴러" }
        ]
      }
    }
  ]
}
```

### 오류 응답

- `503 RECOMMENDATION_UNAVAILABLE`: 현재 추천 세트가 없는 경우
- `503 RECOMMENDATION_PREPARING`: 현재 회차를 소진했고 다음 회차도 만들지 못해 보여줄 카드가 없는 경우. 재시도하면 회복될 수 있는 상태이며 장애와 구분한다. 다음 회차 생성이 Redis 후보를 읽지 못했거나 남은 유효 후보가 없을 때만 이 코드를 쓴다
- `401 UNAUTHORIZED`: 소진 판정과 다음 회차 생성 사이에 계정이 비활성화된 경우. 준비 중 상태로 덮지 않는다. 다만 후보를 읽는 단계가 앞서므로, 같은 요청에서 Redis 후보도 읽지 못했으면 위의 `503 RECOMMENDATION_PREPARING`이 우선한다
- `500 INTERNAL_ERROR`: 빈 슬롯의 다음 후보를 노출 상태로 기록하지 못했거나, 다음 회차 생성이 DB 오류로 실패한 경우. 같은 요청에서 시도한 노출 변경은 롤백되며, 앞선 평가·관심없음 저장과 기존 세트·노출 이력에는 영향을 주지 않는다. 같은 사용자의 동시 조회가 `(generation_id, batch_no)` 고유 인덱스에 걸린 경우는 오류로 보지 않고 먼저 만들어진 회차를 읽어 응답한다

### 처리 규칙

- 기본 구성은 `TASTE` 2편 + `DISCOVERY` 1편이다. 사용 가능한 `DISCOVERY` 후보가 없으면 `TASTE` 3편으로 구성한다
- 조회는 선택한 항목의 `exposed_at`을 기록하는 쓰기 트랜잭션이다. 최초 노출뿐 아니라 평가·관심없음으로 빈 슬롯이 생긴 조회에서도 보충 항목을 노출 상태로 기록한다. 유효 카드 구성이 변하지 않으면 추가 쓰기가 없다
- 같은 추천 세트를 조회하는 동안 이미 노출 중인 유효한 카드는 그대로 유지한다. 조회 시 현재 평가·관심없음 상태를 다시 확인하고, 보충 후보도 같은 기준으로 다시 검증해 빈 슬롯만 현재 세트의 다음 미노출 후보로 채운다. 새 Candidate 알림으로 생성한 세트가 최신이 되면 그 세트의 카드를 노출한다
- 유효한 TASTE가 2편보다 적으면 다음 미노출·유효 TASTE 후보를 먼저 채운다. DISCOVERY가 없으면 다음 DISCOVERY를 우선 채우고, 후보가 없을 때만 TASTE로 대체해 최대 3편을 반환한다
- 보충된 TASTE는 기존 유효 TASTE 뒤, DISCOVERY 앞에 배치한다. 예를 들어 `A(TASTE), B(TASTE), X(DISCOVERY)`에서 A가 빠지면 `B, C, X` 순서다
- 한 번이라도 노출된 항목은 추천 이력 원본이므로 삭제하거나 `exposed_at`을 되돌리지 않는다. 평가·관심없음으로 현재 화면에서 빠졌어도 API-048에는 당시 노출 항목으로 남는다
- 이미 노출 이력이 있는 세트에서 보충 후보가 없으면 새 회차를 만들거나 오류를 반환하지 않고 `items`에 남은 유효 카드 1~2편만 반환한다. 다음 조회에서도 같은 규칙으로 다시 시도한다
- 최초 노출은 목표 구성을 채우지 못해도 확보한 만큼 노출한다. 유효한 TASTE가 부족하면 확보한 카드만 노출하고, 같은 요청에서 보충을 한 번 더 시도한다. 그래도 채우지 못하면 1~2편을 그대로 반환하며, 유효 카드가 남아 있으므로 다음 회차는 만들지 않는다
- **버퍼 소진과 다음 회차**: 현재 회차에 노출 여부와 무관하게 유효 카드가 한 편도 없으면 소진으로 보고, 같은 Redis generation의 다음 회차를 만들어 그 카드를 노출한다. 소진 판정은 서빙 조회와 같은 기준(서비스 DB에 있는 영화, 평가·관심없음 제외)을 쓴다. 회차는 `recommendation_sets.batch_no`로 구분하며 알림으로 만든 첫 회차는 0이다. Redis generation이 이미 바뀌었으면 새 generation의 첫 회차(0)를 만든다. 다음 회차는 같은 generation의 이전 회차가 저장한 영화를 제외한다. 유효 카드가 1편이라도 남아 있으면 회차를 만들지 않는다 — 남은 카드가 있는 채로 회차를 만들면 최신 세트로 전환하는 순간 그 카드가 화면에서 사라진다
- 다음 회차 생성은 조회 트랜잭션 밖에서 수행하고, Redis 후보도 사용자 행 잠금 밖에서 읽는다. 잠금을 잡은 뒤 서빙 대상 세트와 소진 상태를 다시 확인하므로, 판정 이후 평가 철회 등으로 카드가 되살아났으면 회차를 만들지 않고 기존 세트를 계속 서빙한다. 같은 사용자의 동시 조회에서도 회차는 한 번만 늘어난다. 생성에 실패하면 기존 세트·노출 이력을 바꾸지 않고 `503 RECOMMENDATION_PREPARING`을 반환한다
- 다음 회차의 첫 노출 문구는 알림 경로와 같이 저장 전에 확인된 근거로 확정하고, LLM 보강은 응답과 분리해 별도 실행기에서 수행한다. 조회 응답이 LLM 호출을 기다리지 않는다
- `TASTE`는 `RATING`·`ONBOARDING` 이벤트마다 PostgreSQL의 현재 `ratings`를 한 snapshot으로
  다시 읽어 결정론적으로 재계산한다. 이벤트 payload는 재계산 신호일 뿐 계산 입력으로 쓰지
  않는다. 온보딩 LIKE/DISLIKE는 Backend가 각각 `4.0`·`2.0` 평가로 저장하며,
  `onboarding_preferences`는 화면·감사 기록으로만 유지하고 Consumer가 직접 조회하지 않는다.
  Fold-in·콘텐츠·DISCOVERY의 사용자 선호 계산 원천은 `ratings` 하나다. 서로 다른 유효 입력 영화 수와 관계없이
  가용한 ALS·콘텐츠 성분으로 후보를 계산한다. 콘텐츠 산출물은 선택 사항이며, 한 성분 이상이 점수를
  낼 수 있을 때 각 지원 모집단의 백분위를 결합해 `taste` 최대 500편을 게시한다. 입력이 없거나 두
  성분 모두 사용자 프로필을 만들 수 없으면 서비스 가능한 영화에 `0.0` 동점 점수를 부여하고
  `movieId` 오름차순으로 `taste`를 게시한다.
- `DISCOVERY`는 내부
  8×128 영화 그룹에서 별도로 고른 `discovery` 최대 100편이며, Spring이 두 목록을 합쳐
  `TASTE 2 + DISCOVERY 1`을 구성한다. 그룹 결과가 없으면 빈 배열, 모든 적격 그룹의 후보가 부족하면
  100편 미만을 게시하며 전체 개인화 모집단으로 보충하지 않는다(FR-10·FR-12).
- DISCOVERY는 저이력·고궁합 그룹을 개인별로 고른 뒤, 그룹 안에서는 모든 사용자에게 같은
  `max(TMDB 투표 수 백분위, KOBIS 누적 관객 백분위)` 순서로 우선 10편씩 모집한다. 상위 10개
  그룹만으로 100편을 채우지 못하면 같은 그룹들의 11위 후보를 적합도 순으로 한 편씩 모집하고,
  계속 부족하면 12위, 13위 순으로 층별 확장한다. 상위 10개 그룹을 모두 소진해도 부족하면 11번째
  적격 그룹을 1위부터 끝까지 모집하고, 계속 부족하면 12번째 이후 그룹을 적합도 순으로 소진한다.
  모집된 후보는 현재 평가·서빙 모델의 사용자 개인 예측 점수로 최종 정렬한다. 현재 모델이 점수를
  낼 수 없는 영화는 게시 가능 모집단에서 제외하지만,
  전체 개인화 순위를 DISCOVERY 후보 보충에 사용하지 않는다.
- API-024 한 번의 온보딩 제출은 `ONBOARDING` 이벤트 한 건만 발행하고, 선택별
  `RATING` 이벤트를 추가로 발행하지 않는다. 기존 온보딩 보정 상수와 이진 방향 fallback은 사용하지 않는다.
- 서비스 DB에 없는 영화, 평가한 영화와 관심없음 영화는 제외한다. 감상·OTT 클릭 이력은 제외나 재가중에 사용하지 않는다
- 같은 `generationId`의 버퍼가 있으면 같은 `recommendationSetId`를 반환한다. 소진 후 만든 다음 회차는 같은 `generationId`에 `batch_no`가 하나 큰 별도 세트이며, `(generation_id, batch_no)`가 고유하다. Candidate 게시 완료 알림을 받으면 현재 Redis generation으로 새 버퍼를 생성하고 최신 세트로 즉시 전환한다. API-045는 저장된 최신 세트를 읽으며 조회마다 Redis Candidate를 확인하지 않는다. 이전 세트의 노출·추천 이력은 보존한다. 관심없음·평가로 생긴 빈 자리는 다음 API-045 조회가 현재 최신 세트에서 보충한다
- 온보딩을 건너뛰고 평가가 없는 사용자의 개인 후보 보충 순서 개선과 후보 모집단 밖 POPULAR 확장은
  S15P21E106-483에서 다룬다.
  `discovery-popularity`는 선택된 그룹 안의 공통 후보 진입 순서이며 fallback 목록이 아니다
- Python Consumer가 Redis에 갱신한 TASTE·DISCOVERY 후보를 Spring이 종류별 점수로 정렬하고 동점을 결정적으로 섞는다. TASTE를 최대 20편 저장한 뒤 DISCOVERY에서 이미 저장한 TASTE 영화는 건너뛰고 다음 고유 후보로 최대 20편을 채운다. 조회 시 TASTE 2편과 DISCOVERY 1편을 꺼내되, 사용 가능한 DISCOVERY가 없으면 다음 TASTE를 사용한다
- 버퍼는 PostgreSQL `recommendation_items`에 저장하고 `exposed_at`으로 노출 여부를 구분한다. `reason` JSON에는 Consumer가 전달한 구조화된 `reasons[]` 원본과 사용자용 `summary`·`factors`를 함께 저장한다. Candidate 알림 경로는 버퍼 커밋 전에 확인된 영화·장르 근거로 첫 노출용 문구를 확정한다. 근거가 없거나 필요한 표시명을 조회할 수 없을 때만 중립 문구를 저장한다. API에는 `summary`·`factors`만 반환하며, 이전 버전의 원본-only 항목에는 `새로운 영화를 만나보세요.`와 빈 `factors`를 반환한다.
- LLM 호출은 버퍼 저장 트랜잭션 뒤에 수행한다. Candidate 알림 경로에서는 이유 보강을 별도 실행기로 제출하며, Kafka offset 확정은 이 작업을 기다리지 않는다. LLM은 확인된 영화·장르 근거에 대해 허용된 표현 유형만 고르고, Spring이 최종 문장을 조립한다. 모델 오류·타임아웃·빈/무효 응답은 확인된 근거의 결정적 문구로 대체한다. 이유는 첫 노출 전까지만 갱신하며 노출 후에는 같은 스냅샷을 카드·상세·이력에서 재사용한다. 생성 완료 전에 첫 조회가 항목을 노출해도 커밋 때 저장한 근거 문구가 유지된다.
- 버퍼가 커밋된 뒤 이유 보강의 DB 조회·갱신만 실패하면 이미 저장한 세트를 반환한다. 미노출 이유는 같은 generation 재요청에서 다시 보강한다. 그 전에 첫 조회가 노출해도 커밋 때 저장한 근거 문구가 유지된다. 버퍼 저장 자체나 카드 조회 DB 장애는 이 fallback의 대상이 아니다.
- v6 TASTE 근거는 `SHARED_GENRE` 또는 `TOP_RATED_SOURCE`, DISCOVERY 근거는 `DISCOVERY_AGGREGATE_AFFINITY` 또는 `DISCOVERY_PERSONAL_SCORE`다. `reasons[]`의 첫 항목을 표시명으로 해석할 수 없으면 뒤의 유효한 항목을 순서대로 찾는다. 영화 ID는 서비스 영화 제목으로, 장르 코드는 서비스 표시명으로 변환한다. `SHARED_GENRE`와 장르가 있는 `DISCOVERY_PERSONAL_SCORE`는 확인된 공통 장르만 언급한다. `sourceMovieIds`와 `matchedGenreCodes`는 각각의 목록이라 특정 source 영화와 장르의 짝을 증명하지 못하므로 두 값을 한 문장에 묶지 않는다. `TOP_RATED_SOURCE`는 확인된 source 영화 제목을 문구에 넣되 후보와 source 영화가 비슷하다고 단정하지 않는다. 표시할 제목이 60자를 넘으면 앞 59자와 줄임표를 사용한다. 그룹 근거의 `topContributionCoverage`는 그룹 적합도 근거의 집중도이지 후보 영화 최종 점수 설명률이 아니다. v6에 없는 감독·배우 공통성은 언급하지 않는다.
- Consumer는 `vector`·`candidates`·version 키를 원자 게시한다. Spring은 `candidates.taste`와
  `candidates.discovery`를 읽어 제외·재정렬하고 최종 세트를 생성한다. 별도 Redis `topn`은
  사용하지 않는다. 후보가 없거나 유효하지 않으면 짧은 재시도 후 `503 RECOMMENDATION_UNAVAILABLE`을 반환한다.
- 새 세트 생성 시 추천 이력(FR-33)에 시각·유형·이유·후보를 저장한다
- 현재 v6 근거에서 확인 가능한 `reason.factors[].type`은 `GENRE`다. 다른 factor 타입은 실제 입력 계약과 표시명 검증이 합의될 때 추가한다.

### 구현·연동 상태

- S15P21E106-415에서 DISCOVERY 후보 생성 정책과 Consumer 구현을 완료했다.
- S15P21E106-471에서 Redis Candidate v3 `taste`/`discovery` 그룹화와 `generationId` writer를
  반영했고, S15P21E106-481에서 TASTE 후보별 구조화 단일 `reason` 객체를 추가해 Candidate v4가 됐다.
  S15P21E106-488은 당시 전체 평가 또는 온보딩 이력으로 계산한 저이력 그룹 적합도와 상위 기여 비율을
  DISCOVERY 전용 단일 `reason` 객체로 추가해 Candidate v5로 올렸다. S15P21E106-649부터
  온보딩 선택은 4.0·2.0 평가로 변환된 `ratings` 입력으로만 같은 계산에 참여한다.
  S15P21E106-505는 입력 수 제한을 제거하고
  당시 DISCOVERY 보충 후보용 `DISCOVERY_PERSONAL_SCORE`를 추가해 Candidate v6로 올렸다. S15P21E106-194에서
  API-045/046과 TASTE 2 + DISCOVERY 1 노출·보충을 구현했다. Candidate v6 Spring reader는
  Backend가 추가·통합 검증해야 하는 소비 계약이다. S15P21E106-164는 기존 v4 배열 계약을 유지하고,
  S15P21E106-498에서 v6 배열 계약 대응과 사용자용 이유 스냅샷 생성을 함께 진행한다. 전환은 v5/v6 reader 선반영, Consumer v6 전환과 활성 후보
  재게시, v6·`generationId` 검증, v5 호환 제거 순서로 진행한다.
- S15P21E106-482에서 그룹·그룹 내부 대중성 번들의 HDFS 게시, 두 URI 동시 활성화와 rollback을 검증한다.
- S15P21E106-415 재개선에서 상위 10개 그룹의 내부 순위를 층별 확장하고, 이 그룹들을 소진한 뒤에만
  후순위 적격 그룹으로 확장하며 DISCOVERY의 전체 개인화 후보 보충을 제거한다.
- S15P21E106-483은 개인 후보 보충 순서를 개선하고 후보 모집단 밖까지 확장하는 별도 POPULAR 목록의
  정책·산출물·서비스 연동을 담당한다.

### 상태값

- RecommendationType `TASTE`, `DISCOVERY`

### 미정 사항

- [ ] Candidate v6 Spring reader와 기존 두 목록 조립의 연동을 통합 검증해야 함
- [ ] TMDB/KOBIS 공개 신호 후보 자격 하한을 합의하고 S15P21E106-482에서 운영 활성화해야 함
- [x] 콜드 스타트 임시 전략 — 입력이 없으면 `0.0` 동점 TASTE 게시
- [ ] 콜드 스타트 POPULAR 순서·후보 모집단 확장 — S15P21E106-483 이후
- [x] Candidate v6 근거의 사용자 문구 방식 — LLM이 확인된 근거의 허용 표현을 선택하고 Spring이 문장을 조립
- [ ] Re-ranking 가중치와 추천 이유 생성 방식

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

영화를 관심없음으로 표시한다. 추천 카드에서 호출한 경우 선택적으로 추천 항목 출처를 기록한다. 영화 상세 등 다른 화면에서도 모든 영화에 사용할 수 있으며, 카드 보충은 다음 기본 추천 조회(API-045)가 현재 상태를 확인해 수행한다.

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
  "movieId": 550
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 ALREADY_DISMISSED`

### 처리 규칙

- 관심없음은 `user_movie_dismissals`에 사용자·영화별 1건으로 저장하며 평가·팝콘 버킷·프레임에 영향이 없다
- 추천 외 화면에서 호출하면 `recommendation_item_id`는 NULL이다
- `recommendationItemId`를 보냈다면 본인 추천 항목이며 경로의 `movieId`와 일치하는지 검증한다
- 관심없음 영화는 이후 모든 개인 추천에서 제외한다. 유사 영화 추천(API-031) 제외 여부는 미정
- 관심없음 저장은 추천 보충과 별도 트랜잭션이다. 저장 성공 후 프론트가 API-045를 다시 조회하며, 보충 실패가 관심없음 저장을 롤백하지 않는다
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
최신순은 같은 사용자의 버퍼 저장 순서(세트 ID 내림차순)로 판단한다. `generatedAt`은 생성 시각 표시값이며 서버 시계가 역전되어도 최신 세트 판정을 바꾸지 않는다.

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
| 화면 | EXT-01 ~ EXT-05 나에게 맞는 OTT 찾기 (7.8 설정의 배너로 진입) |
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
- 취향 일치도 %, OTT별 추천 이유, 취향 항목별 일치도는 응답에 없으며 화면도 표시하지 않는다. 위의 유사도 금지 규칙에 따른 것이며 화면 쪽 결정은 S15P21E106-563에 있다

### 상태값

없음

### 미정 사항

- [ ] S15P21E106-483의 검증된 POPULAR 목록이 준비되면 취향 입력이 없는 사용자의 OTT 집계와 화면 문구에 반영
- [x] 화면 배치 — 7.8 설정의 `나에게 맞는 OTT 찾기` 배너에서 EXT-01 ~ EXT-05로 들어간다. 홈을 없애면서 배너가 옮겨졌고 다른 입구는 없다(S15P21E106-563 · 지시서 CR-18)
