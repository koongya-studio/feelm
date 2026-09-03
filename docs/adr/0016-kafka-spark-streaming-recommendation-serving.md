# ADR-0016: 실시간 추천 갱신은 Kafka와 Spark Structured Streaming으로 수행한다

| 항목 | 값 |
|---|---|
| 상태 | 대체 |
| 결정일 | 2026-08-31 |
| 결정자 | 팀 확정, S15P21E106-127에서 기록 |
| 관련 | ADR-0001, ADR-0002, ADR-0004, ADR-0012 대체, NFR-01, NFR-04, NFR-05, API-040, API-045, API-058, ADR-0017에서 대체, S15P21E106-127 |

## 맥락

초기·주기적 ALS 학습과 전체 Candidate 생성은 Spark Batch가 담당하지만, 새 평점과 최근 행동은 다음 배치까지 기다리지 않고 추천에 반영해야 한다. ADR-0012는 이를 FastAPI 요청 시점 계산으로 분리했으나 Kafka와 Spark Structured Streaming을 도입하면 기존 Spark 산출물과 실행 환경을 재사용해 사용자 단위 갱신을 처리할 수 있다.

## 결정

FastAPI 추천 서빙 서비스는 두지 않고 Spring Boot, Kafka, Spark Structured Streaming, Redis로 실시간 추천 흐름을 구성한다. Spring Boot는 서비스 데이터를 PostgreSQL에 먼저 저장한 뒤 사용자 행동 이벤트를 Kafka에 발행한다.

- `RATING` 이벤트는 기존 ALS Item Factor를 재사용해 해당 사용자의 User Vector만 Fold-in하고 Candidate Top-500을 재생성한다. 전체 ALS와 Item Factor는 다시 학습하지 않는다.
- `VIEW`, `RECOMMENDATION_CLICK`, `OTT_CLICK` 이벤트는 Recent Behavior Feature를 집계한다.
- Spark Structured Streaming은 Candidate와 행동 Feature를 결합해 가중치 Re-ranking을 수행하고, 점수에 실제로 사용한 Feature로 근거 기반 XAI를 생성한다.
- User Vector, Candidate Top-500, Recent Behavior Feature, 최종 Top-N과 추천 근거는 Redis에 저장한다.
- Spring Boot는 Redis의 최신 개인 추천 결과를 조회해 React 웹앱에 제공하고 추천 이력 스냅샷은 PostgreSQL에 저장한다.
- 파티 추천은 Spring Boot가 Redis의 파티원별 Candidate 점수를 결합해 평균 만족도·최저 만족도·편차 기반으로 요청 시 집계한다. 파티 규모가 작고 모델 추론이 없는 결정적 계산이므로 별도 Python 서빙 계층을 두지 않는다.
- 유사 영화 추천은 오프라인에서 생성한 E5 임베딩을 Spring Boot가 PostgreSQL/pgvector에서 Top-K로 조회한다.
- OTT 추천은 Spark가 생성한 사용자별 영화 점수를 사용하고 Spring Boot가 미감상·구독 상태·OTT 제공처를 적용해 집계한다.

이벤트는 `eventId`를 기준으로 멱등 처리하고 Spark Checkpoint로 재시작 위치를 보존한다. Streaming 지연이나 장애 시 마지막으로 성공한 Redis 결과를 제공하고 유효 결과가 없을 때만 추천 API가 `503`을 반환하며 영화·검색·평가 기능은 유지한다.

## 고려한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| Kafka + Spark Structured Streaming (선택) | Spark Batch 산출물·클러스터 재사용, 이벤트 기반 사용자 단위 갱신, 행동 Feature 집계 통합 | Streaming 운영·Checkpoint·멱등성 관리 필요 |
| FastAPI 요청 시 계산 | Python 추천 로직을 독립 서빙 가능 | Spark Streaming과 실시간 계산 책임 중복, 별도 서비스 운영 |
| Spring Boot에서 Fold-in·개인 Re-ranking 전체 계산 | 서비스 호출 구조가 단순 | Python/Spark 추천 로직을 Java로 중복 구현해야 함 |
| 배치 결과만 제공 | 운영이 단순 | 평점과 최근 행동이 다음 배치까지 반영되지 않음 |

## 결과

- **얻는 것**: Batch와 Streaming을 연결한 일관된 Spark 추천 파이프라인, 평점 사용자 단위 Candidate 갱신, 약한 행동 신호 반영, 별도 FastAPI 운영 제거
- **감수하는 것**: 이벤트 스키마·Redis 키·Checkpoint·재처리 정책과 Streaming 모니터링 필요
- **후속 작업**: Kafka Topic·이벤트 계약, Fold-in 상태 저장, Redis Candidate 점수 구조, Re-ranking 가중치 실험, Spring 파티·OTT 집계식, 추천 지연 SLO와 장애 복구 절차 확정
