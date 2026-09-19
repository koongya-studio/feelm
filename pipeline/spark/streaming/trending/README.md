# 급상승 영화 VIEW 집계

`jobs/streaming/trending.py`는 `feelm.view.v1`을 소비해 API-064용 Redis 스냅샷을
게시하는 Spark Structured Streaming 작업이다. 입력은 공통 `VIEW` 봉투이며 회원과
영화 ID가 양수인 정상 이벤트만 사용한다.

현재 구현은 `feelm.view.v1`을 읽는 단일 Spark 쿼리에서 유효 VIEW를 걸러내고
`eventId`를 10분 watermark로 중복 제거한다. `foreachBatch`의 정적 집계가 5분
버킷별 영화·회원 집합을 Redis에 기록한다. 중간 Kafka topic이나 두 번째 Kafka
consumer는 없다. 각 버킷의 영화 인덱스와 회원 집합은
`feelm:trending:bucket:{UTC 5분 시각}:movies` 및
`feelm:trending:bucket:{UTC 5분 시각}:movie:{movieId}:users`에 저장하고
150분 TTL을 둔다. 이 키는 Spark 작업의 내부 상태이며 Spring은 읽지 않는다.
영화 목록 미러·회원 집합의 기대 크기와 SHA-256 지문·활성 시간대 메타데이터와 별도 기록 완료 시간대 집합도 함께 기록한다.
쓰기 전과 게시 전에는 이 값을 실제 버킷과 비교한다. 누락이나 같은 인원수의 회원 교체가 감지되면 작업을 실패시켜
새 순위 게시를 멈추고 마지막 정상 B를 유지한다. 같은 Redis에서 버킷과 모든 검증
메타데이터가 한꺼번에 이전 상태로 돌아간 경우는 탐지할 수 없다.

같은 애플리케이션의 메인 실행 흐름은 재시작 직후 최신 UTC 5분 경계를 확인하고,
이후에도 Spark 입력과 독립적으로 경계를 감지한다. 경계마다 Spark가 그때 Kafka에서
사용 가능한 입력을 버킷에 반영할 때까지 기다린 뒤 최근 24개 버킷을 읽는다.
처리에 실패하면 새 B를 게시하지 않고 마지막 정상 B를 유지한다. 입력 처리가 다음
경계를 넘기면 최신 경계에서 다시 처리를 확인한다. 현재 경계의 최근 120분 밖에 있는
과거 시각 VIEW는 버킷에 쓰지 않는다. 현재·직전 60분의 영화별 고유 조회 회원 수를
각각 계산한다. 회원이 여러 5분 버킷에 있어도 해당 60분 안에서는 한 명이다.
최근 60분에 3명 이상 조회했고 증가분이 양수인 영화만 최대 30편 게시한다.
후보가 없으면 게시하지 않으며, 첫 유효 결과 전에는 API가 초기 고정 A를 사용한다.
순위·점수·Redis JSON/TTL은 [API-064](../../../../docs/api/report-trend.md#api-064-급상승-영화)와
[스트리밍 계약](../../../../docs/api/streaming-contract.md#5-redis-키)을 따른다.

```bash
cd pipeline/spark
PYTHONPATH=. spark-submit \
  --packages org.apache.spark:spark-sql-kafka-0-10_2.13:4.1.3 \
  jobs/streaming/trending.py \
  --kafka-bootstrap-servers '<kafka-bootstrap>' \
  --checkpoint-uri '<전용-checkpoint-URI>' \
  --redis-url '<Redis-접속-URL>'
```

Checkpoint는 다른 Spark 쿼리와 공유하지 않고 재시작 시 같은 URI를 사용한다.
버킷 기록 실패는 micro-batch 실패로 전파되어 재시도되며 Redis 집합 쓰기는
같은 배치를 다시 적용해도 회원 수가 부풀지 않는다. 게시 실패는 작업을 실패시켜
재시작 후 재시도한다. 이 작업은 단일 활성 인스턴스로 운영해야 한다.
Redis 두 스냅샷 키는 한 트랜잭션에서 기록한다. `current`의 TTL은 15분이고
`last-good`에는 TTL이 없어 마지막 유효 B가 남는다. 같은 5분 경계의 배치 재시도는 이미 게시한
스냅샷을 유지해 `rankDelta`를 다시 계산하지 않는다.

로컬 검증은 `uv run pytest -q tests/streaming/test_trending_*.py`와
`uv run ruff check .`로 수행한다. 실제 Kafka·Redis·HDFS 재시작 및 게시 지연은
main 반영 후 운영 환경에서 검증한다.

로컬 테스트는 Spark 상태와 빈 구간 게시를 검증하지만 실제 Kafka·Redis·HDFS
재시작, 게시 지연, 장기 부하와 단일 인스턴스 배포는 main 반영 후 검증한다.
