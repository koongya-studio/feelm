# ADR-0017: 개인 Fold-in은 Python Consumer, 전역 인기 집계는 Spark Structured Streaming으로 분리한다

| 항목 | 값 |
|---|---|
| 상태 | 채택 |
| 결정일 | 2026-09-01 |
| 결정자 | 팀 확정, S15P21E106-176에서 기록 |
| 관련 | ADR-0001, ADR-0002, ADR-0004, ADR-0016 대체, FR-10, FR-27, NFR-04, NFR-05, API-045, API-064, S15P21E106-176 |

## 맥락

ADR-0016은 사용자 한 명의 평가 변경에 따른 Fold-in과 여러 사용자의 행동 집계를 모두 Spark Structured Streaming에 배치했다. 그러나 두 계산은 처리 단위와 상태 특성이 다르다.

- Fold-in은 평가가 발생한 사용자 한 명의 전체 평가를 읽고 작은 선형계산과 Item Factor 내적을 수행한다. 사용자별로 독립적이고 이벤트 한 건을 명시적으로 끝까지 처리하는 Consumer 모델에 가깝다.
- 인기 영화는 여러 사용자의 `VIEW` 이벤트를 이벤트 시간 윈도우로 묶어 영화별 고유 사용자 수와 이전 윈도우 대비 상승량을 지속 집계한다. Watermark, 윈도우 상태와 Checkpoint가 필요한 분산 스트림 집계다.

Fold-in까지 Spark Streaming으로 수행하면 사용자 단위 계산을 위해 장기 실행 Spark Job과 Checkpoint에 결합되고, 개인 추천 장애와 전역 집계 장애가 같은 실행 단위에 묶인다. 반대로 전역 인기 집계를 일반 Python Consumer가 직접 수행하면 분산 윈도우 상태, 지연 이벤트와 재처리를 별도로 구현해야 한다.

## 결정

Kafka 이후의 실시간 처리를 계산 특성에 따라 두 실행 단위로 분리한다.

1. **Python Fold-in Consumer**는 `RATING`·`ONBOARDING` 이벤트를 소비한다. PostgreSQL에서 해당 사용자의 최신 평가·온보딩 값을 읽어 User Vector, Candidate Top-500, 행동 신호 적용 전의 기본 Top-N과 XAI 근거를 재계산하고 Redis에 기록한다.
2. **Spark Structured Streaming**은 여러 사용자의 `VIEW` 이벤트를 영화별로 집계해 실시간 인기 결과를 Redis에 기록한다. 조회·클릭·관심없음 같은 약한 신호는 사용자별 행동 Feature로 집계하되 개인 Top-N 재정렬은 수행하지 않는다.
3. **Spring Boot**는 PostgreSQL 커밋 후 원본 이벤트를 발행한다. 개인 추천 세트를 새로 만들 때 Redis의 기본 Top-N과 최신 행동 Feature를 읽어 경량 가중치 재정렬을 수행하고, 감상·관심없음 제외와 `TASTE`/`DISCOVERY` 구성을 적용한다. Fold-in이나 전역 인기 순위는 계산하지 않는다.

두 실행 단위는 Kafka Consumer Group, 장애 도메인과 복구 상태를 분리한다.

- Python Consumer는 Kafka Offset을 소비 진행 상태로 사용하고 PostgreSQL 재조회 기반 자연 멱등성을 보장한다.
- Spark Streaming은 전용 Consumer Group과 Checkpoint를 사용한다.
- Python Consumer 장애는 개인 추천 갱신에, Spark Job 장애는 행동 Feature·인기 집계에 각각 영향을 주도록 장애 범위를 분리한다.
- 전체 ALS 재학습과 Item Factor 산출은 Spark Batch가 담당하고, 전체 사용자 Candidate·기본 Top-N 서빙 키 재구축은 Python Consumer가 담당한다.
- `candidates`와 `topn`에는 같은 `candidateVersion`을 기록한다. Spring은 최신 `candidates.candidateVersion`보다 낮은 `topn`으로 새 추천 세트를 만들지 않으며, 버전이 맞는 기본 Top-N에 최신 `behavior`를 적용한다.
- 약한 신호는 현재 제공 중인 세트 버퍼를 무효화하지 않고 **다음 추천 세트 생성 시** 반영한다. 재정렬 대상은 기본 Top-N 100편으로 제한하므로 비용은 세트 생성 요청당 `O(N log N)`이며, Spark 집계 뒤 전체 사용자를 Python으로 다시 계산하지 않는다.

인기 집계의 윈도우·슬라이드·지연 허용값, 중복 조회 처리, 최소 사용자 수, 순위 점수식과 결과 반영 목표는 이 ADR에서 확정하지 않는다. 구현 전에 실데이터와 운영 환경을 기준으로 별도 계약에서 결정한다.

## 고려한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| Python Fold-in + Spark 집계 + Spring 경량 재정렬 (선택) | 사용자 단위 계산과 분산 집계를 각 실행 모델에 맞추고, 별도 재계산 이벤트 없이 다음 세트에 행동 반영 | 실행 단위 증가, Spring에 제한된 점수 조합 로직 필요, 기본 Top-N 밖 후보는 행동으로 승격 불가 |
| 모든 실시간 처리를 Spark Streaming에서 수행 | 파이프라인 기술이 하나이고 이벤트 연결이 단순 | 사용자 단위 Fold-in이 Spark Job·Checkpoint에 결합되고 인기 집계 장애와 함께 영향받음 |
| 모든 실시간 처리를 Python Consumer에서 수행 | Spark Streaming 운영을 생략할 수 있음 | 분산 윈도우·Watermark·Checkpoint를 직접 구현해야 하고 다중 사용자 집계 확장이 어려움 |
| Spring Boot에서 Fold-in과 인기 집계 수행 | 서비스 호출 구조가 단순 | 추천 계산의 Java 중복 구현과 서비스 API 자원 경합 발생 |

## 결과

- **얻는 것**: 개인 추천 갱신과 전역 인기 집계의 명확한 책임 경계, 독립 확장·배포·복구, Python 재실행 없이 다음 세트에 행동 Feature 반영
- **감수하는 것**: Python Consumer와 Spark Job의 별도 운영, Spring의 경량 재정렬 로직과 세트 소진까지의 행동 Feature 반영 지연 관리
- **후속 작업**: 인기 집계 윈도우·점수식·최소 사용자 수·반영 지연 확정, Python Consumer와 Spark 인기 집계 Job 구현
