# 전체 인프라 Bootstrap/Reconcile 안전 게이트

`bootstrap-reconcile.py`는 전체 인프라 수동 파이프라인이 상태를 변경하기 전에 각 노드에서
실행하는 안전 게이트다. 서비스 컨테이너, 영속 볼륨 내용, HDFS 메타데이터와 Kafka Cluster
ID를 변경하지 않는다. 볼륨 내용 확인에는 네트워크가 차단되고 root filesystem과 대상
mount가 read-only인 일회성 probe 컨테이너를 생성한 뒤 즉시 제거한다.

## 모드

- `bootstrap`: 신규 서버에서 누락되거나 비어 있는 영속 상태의 최초 초기화를 허용한다.
- `reconcile`: 기존 상태가 모두 존재할 때만 일반 재배포를 허용한다. 누락된 볼륨이나 초기화되지
  않은 HDFS/Kafka가 있으면 실패하며 자동 초기화로 전환하지 않는다.

CI 실행 모드는 `reconcile`이 기본값이다. `bootstrap`은 추가 확인 문자열이 일치할 때만
빈 영속 상태의 최초 초기화를 허용한다. 두 모드 모두 보호된 `main`에서 시작한 전용 Web
Pipeline의 수동 Job으로만 실행된다.

## 실행 예시

서비스 노드:

```bash
python3 -I -B infra/scripts/bootstrap-reconcile.py \
  --mode reconcile --node service --env-file infra/.env
```

분산 노드:

```bash
python3 -I -B infra/scripts/bootstrap-reconcile.py \
  --mode bootstrap --node distributed --env-file infra/.env --json
```

검사는 Compose 라벨로 영속 볼륨을 찾는다. 같은 논리 볼륨 라벨을 가진 볼륨이 둘 이상이면
대상을 임의로 선택하지 않고 실패한다. HDFS와 Kafka 볼륨 내용은 네트워크가 없고 root
filesystem과 대상 mount가 모두 read-only인 일회성 컨테이너로 읽는다. 검사 이미지가 로컬에
없으면 자동 pull하지 않고 실패한다.

### HDFS

- `current/VERSION`이 있으면 기존 NameNode로 판정하고 두 모드 모두 포맷을 금지한다.
- 볼륨이 없거나 비어 있을 때만 `bootstrap`에서 `initialize-once`를 반환한다.
- 내용은 있지만 유효한 NameNode 메타데이터가 없으면 손상 또는 경로 불일치 가능성이 있으므로
  포맷하지 않고 수동 확인을 요구한다.

### Kafka

- 기존 `meta.properties`의 `cluster.id`가 `KAFKA_CLUSTER_ID`와 같을 때만 재사용한다.
- ID가 다르면 새 ID로 덮지 않고 실패한다.
- 볼륨이 없거나 비어 있을 때만 `bootstrap`에서 최초 초기화를 허용한다.
- 내용은 있지만 유효한 `cluster.id`가 없으면 초기화를 거부한다.

## Topic 보장

Kafka 기동 후 다음 스크립트를 실행한다.

```bash
bash infra/scripts/ensure-kafka-topics.sh
```

`docs/api/streaming-contract.md`의 8개 고유 Topic(`rating`, `dismiss`, `view`,
`recommendation-click`, `ott-click`, `candidate-updated`, `party-refresh`,
`party-candidate-updated`)을 `--if-not-exists`로 보장하고 각 Topic의 partition 수와
각 partition의 복제 수를 검증한다. 별도 자동 Job·재실행 방법과 실패 처리 규칙은
[`KAFKA-TOPICS.md`](KAFKA-TOPICS.md)를 따른다.
기존 Topic을 삭제하거나 partition 수를 줄이지 않는다. 기존 설정이 기대값과 다르면 자동
변경하지 않고 실패한다.

## 보호된 수동 Pipeline 실행

GitLab의 **Build > Pipelines > New pipeline**에서 보호된 `main`을 선택하고 다음 Pipeline
변수를 입력한다. 평소 `main` push Pipeline에는 이 Job이 생성되지 않는다.

| 변수 | 값 |
| --- | --- |
| `INFRA_RECOVERY_RUN` | 반드시 `true` |
| `INFRA_RECONCILE_MODE` | 일반 복구는 `reconcile`, 신규 빈 서버 최초 구성만 `bootstrap` |
| `INFRA_BOOTSTRAP_CONFIRM` | `bootstrap`일 때만 `INITIALIZE_EMPTY_STATE` |
| `INFRA_BACKEND_IMAGE` | Backend의 `repository@sha256:...` |
| `INFRA_FRONTEND_IMAGE` | Frontend의 `repository@sha256:...` |
| `INFRA_CONSUMER_IMAGE` | Consumer의 `repository@sha256:...` |
| `INFRA_SPARK_IMAGE` | Spark의 `repository@sha256:...` |
| `INFRA_SPARK_REVISION` | 위 Spark 이미지의 OCI revision label과 일치하는 40자 commit SHA |

Pipeline 생성 후 `infra-bootstrap-reconcile` 수동 Job을 승인한다. Job 실행 시 입력 변수를 자식
Pipeline에 전달하며, 아래 순서를 `needs`로 직렬화한다. 서비스·분산 노드의 사전 검사가 모두
통과하기 전에는 어떤 컨테이너도 변경하지 않는다. 각 노드의 기존 `service`/`distributed`
범위 Protected 변수와 File 변수는 그대로 사용한다.

`reconcile`은 기존 영속 볼륨을 전제로 삭제된 컨테이너를 다시 만들 수 있지만, 누락되거나 빈
영속 상태를 초기화하지 않는다. `bootstrap`도 기존 HDFS `current/VERSION`이나 Kafka
`meta.properties`를 발견하면 재포맷 또는 Cluster ID 재생성을 하지 않고 그대로 재사용한다.
Spark는 입력 digest와 revision이 기존 배포 manifest와 일치할 때만 컨테이너를 복구하며,
manifest가 없는 최초 `bootstrap`에서만 실행 상태를 검증한 뒤 manifest를 초기화한다.

## 실행 순서

전체 순서는 아래로 고정한다.

```text
사전 검사 → PostgreSQL → Redis → HDFS → Kafka → Kafka Topic → Spark
→ Backend → Consumer → Frontend/Nginx → 모니터링·보조 서비스 → 전체 검증
```

- PostgreSQL 변경이 필요한 경우 S15P21E106-652의 백업·검증·rollback 배포 계약을 재사용한다.
  이미 후보 이미지로 실행 중이면 상태만 검증하고 재생성하지 않는다.
- 분산 관측 및 Spark History는 S15P21E106-653의 개별 배포·검증 스크립트를 재사용한다.
- 모니터링은 S15P21E106-645의 `deploy-monitoring.sh`를 재사용한다.
- 기존 일반 CD와 같은 `resource_group`을 사용해 동일 서비스의 동시 변경을 막고, 부모 Job은
  자식 Pipeline 완료까지 전용 `infra-bootstrap-reconcile` 잠금을 유지한다.

## 로컬 계약 테스트

```bash
python3 -B -m unittest infra.scripts.test_bootstrap_reconcile -v
```

Shell 및 전체 인프라 CI 계약 검증:

```bash
bash -n infra/scripts/bootstrap-reconcile-step.sh infra/scripts/ensure-kafka-topics.sh
python3 -I -B infra/scripts/run-infra-config-tests.py
```
