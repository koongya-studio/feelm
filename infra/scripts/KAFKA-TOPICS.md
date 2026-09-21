# Kafka Topic 프로비저닝 (S15P21E106-726)

`ensure-kafka-topics.sh`는 [이벤트 계약](../../docs/api/streaming-contract.md)의 8개
Topic을 선언하고 없는 Topic만 생성한다. 기존 전체 인프라 복구 Pipeline도 같은 스크립트를
사용한다. 새 라이브러리 없이 실행 중인 Kafka 컨테이너의 `kafka-topics.sh`를 호출한다.

| Topic | 기본 파티션 | 복제 수 |
| --- | --- | --- |
| `feelm.rating.v1` | 3 | 1 |
| `feelm.dismiss.v1` | 3 | 1 |
| `feelm.view.v1` | 3 | 1 |
| `feelm.recommendation-click.v1` | 3 | 1 |
| `feelm.ott-click.v1` | 3 | 1 |
| `feelm.candidate-updated.v1` | 3 | 1 |
| `feelm.party-refresh.v1` | 3 | 1 |
| `feelm.party-candidate-updated.v1` | 3 | 1 |

## 실행

Kafka가 기동된 분산 노드에서 저장소 루트 기준으로 실행한다.

```bash
bash infra/scripts/ensure-kafka-topics.sh
```

로컬 Kafka는 컨테이너 이름을 지정한다.

```bash
KAFKA_CONTAINER=feelm-kafka-local bash infra/scripts/ensure-kafka-topics.sh
```

기존 스크립트의 선택 환경변수는 다음과 같다. `infra/.env`를 자동으로 읽지 않으며
현재 shell에서 전달한다. 복제 수는 단일 Broker 계약에 맞춰 1로 고정한다.

| 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `KAFKA_CONTAINER` | `feelm-kafka` | 실행 중인 컨테이너 |
| `KAFKA_BOOTSTRAP_SERVER` | `127.0.0.1:9092` | 컨테이너 내부에서 접근할 Broker 주소 |
| `KAFKA_TOPIC_PARTITIONS` | `3` | 모든 선언 Topic의 기대 파티션 수, 양의 정수 |
| `KAFKA_TOPIC_READY_ATTEMPTS` | `12` | Broker 목록 조회 시도 횟수, 양의 정수 |
| `KAFKA_TOPIC_READY_INTERVAL_SECONDS` | `5` | 시도 사이 대기 초, 0 이상 정수 |

## CI Job

`provision-kafka-topics`는 보호된 `main`의 push Pipeline에서 `.gitlab/ci/infra.yml` 또는
Topic 스크립트 변경 시 자동 실행한다. `e106-distributed` Runner와 `distributed` 환경을
사용하고 현재 `main` HEAD와 다른 오래된 커밋은 거부한다. 실패는 Pipeline 실패로 처리한다.
Kafka 설정 배포·복구와 같은 `distributed-runtime-kafka` 잠금으로 동시 실행을 막는다.

이 Job은 기존 Broker의 Topic만 보장한다. Broker 최초 기동은 기존 전체 복구 Pipeline을
사용한다. 일반 Backend·Consumer 배포의 선행 Job으로 연결하지 않으므로 새 이벤트를 사용하는
서비스를 배포하기 전 이 Job 성공 여부를 확인한다. 재실행은 해당 커밋이 여전히 `main` HEAD인
Pipeline의 Job Retry 또는 위 shell 명령을 사용한다.

## 실패와 보존 규칙

1. Broker 목록 조회에 성공해야 진행한다. 조회 실패를 Topic 부재로 취급하지 않는다.
2. 존재하는 모든 선언 Topic의 파티션 수, 요약 복제 수와 각 파티션의 replica 할당 개수를
   먼저 검증한다. 파티션 행 누락·중복과 불완전한 메타데이터도 실패한다.
3. 검증이 통과하면 누락 Topic만 `--create --if-not-exists`로 생성한다.
4. 생성 후 전체를 재검증해 동시 생성으로 설정이 달라진 경우도 실패한다.

기존 Topic·메시지·offset·다른 Topic을 삭제하거나 설정을 변경하지 않는다. 도중에 실패해도
이미 생성된 Topic은 남겨 두며, 원인을 해결한 후 재실행한다. 이는 여러 Topic에 대한 원자적
트랜잭션이 아니다. 스크립트 외부의 관리자 변경까지 잠그지는 않는다.

설정 불일치는 담당자와 계약·실제 메타데이터를 확인한다. 기존 데이터 보존을 위해 이 Job으로
파티션 증가, 복제 재할당, retention 변경, 삭제 후 재생성을 수행하지 않는다. 메시지의 통상적인
보존기간 만료·compaction은 Broker의 기존 정책을 따른다.

CLI 메타데이터 형식은 [Kafka 4.3 운영 문서](https://kafka.apache.org/43/operations/basic-kafka-operations/)를 참고한다.

## 검증

```bash
bash -n infra/scripts/ensure-kafka-topics.sh
python3 -B -m unittest infra.scripts.test_ensure_kafka_topics -v
python3 -I -B infra/scripts/run-infra-config-tests.py
```

상태를 유지하는 Docker 대역으로 실제 shell 스크립트를 실행해 최초 생성·재실행·부분 존재,
설정 불일치·메타데이터 누락·조회 및 생성 오류를 검증한다. 실제 Broker 검증은 별도 환경에서
수행해야 하며, 운영 Topic을 테스트 목적으로 삭제하거나 변경하지 않는다.
