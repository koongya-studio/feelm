# 분산 보조 컨테이너 CD

이 문서는 `S15P21E106-653`에서 추가하는 분산 노드 보조 컨테이너 배포기의 운영 계약을
정리한다. 보호된 `main` push에서 관련 파일이 변경되면 분산 Runner의
`deploy-distributed-observability`와 `deploy-spark-history` Job이 자동 실행된다.

## 배포 단위

`deploy-distributed-observability.sh`는 다음 컨테이너만 관리한다.

- `feelm-kafka-exporter`
- `feelm-kafka-ui`
- `feelm-node-exporter-distributed`

`deploy-spark-history.sh`는 HDFS의 `/spark-events` 디렉터리를 멱등 초기화한 뒤
`feelm-spark-history-server`만 관리한다. Kafka, HDFS NameNode·DataNode, Spark
Master·Worker와 Consumer는 두 배포기의 재생성 대상이 아니다. 두 스크립트는
`docker compose down`, `down -v` 또는 볼륨 삭제 명령을 사용하지 않는다.

관측 배포는 Kafka 설정 배포와 같은 `distributed-runtime-kafka` resource group을 사용한다.
Spark History 배포는 Spark 코드 배포와 같은 `distributed-runtime-spark` resource group을
사용한다. 따라서 각각 의존하는 핵심 런타임이 변경되는 동안 보조 컨테이너를 동시에
재생성하지 않는다.

## 배포와 검증

두 스크립트는 `DISTRIBUTED_NODE_IP`가 설정된 분산 노드에서 실행한다. 별도 환경 파일이
필요하면 `COMPOSE_ENV_FILE`에 경로를 지정한다. 지정한 파일이 없으면 컨테이너를 변경하기
전에 실패한다.

관측 배포는 Compose 구문과 이미지 pull을 먼저 확인하고 기존 세 컨테이너를
`-rollback` 이름으로 보존한다. 후보 컨테이너를 `--no-deps --force-recreate`로 기동한 뒤
다음을 검증한다.

- Kafka Exporter의 `kafka_brokers` metric
- Node Exporter의 `node_exporter_build_info` metric
- Kafka UI의 `/actuator/health` 응답
- 세 컨테이너의 배포 revision 라벨이 현재 `CI_COMMIT_SHA`와 일치하는지

Spark History 배포는 같은 pipeline의 Spark 코드 배포가 있으면 양 노드의 성공 확정을 기다린다.
실행 중인 `feelm-spark-master` 코드 이미지의 `io.feelm.spark.runtime-image` label에서 기반
런타임 digest를 읽고, 전달된 CI runtime digest와 일치하는지 확인한 뒤 pull한다. label 누락,
가변 태그 또는 불일치는 기존 컨테이너를 중단하기 전에 거부한다. 새 label이 없는 이전 코드
이미지에서는 먼저 새 코드 배포를 완료해야 한다. HDFS 초기화 컨테이너가 `/spark-events`를 만들고 권한을 `1777`로 맞춘 뒤에만
기존 History Server를 보존하고 후보를 기동한다. 이후 HDFS 디렉터리·권한, History Server
API와 배포 revision 라벨 및 런타임 image digest를 검증한다.

## 실패와 수동 복구

후보 기동 또는 검증이 실패하면 후보만 제거하고 `-rollback` 컨테이너를 원래 이름으로
복원한다. 최초 배포라 보존본이 없으면 실패한 후보만 제거한다. 복구 검증도 실패하거나
이전 실행에서 `-rollback` 컨테이너가 남았다면 자동 배포를 반복하지 않는다.

History의 rollback은 이미 성공한 Spark 코드 배포를 되돌리지 않는다. 실패 pipeline에서
History와 Master 기반 런타임이 다르면 원인을 해결하고 History Job을 재시도한다.
전체 Spark 코드를 별도로 rollback한 경우에도 History를 다시 배포해 기반 digest를 맞춘다.

운영자가 실행 중인 컨테이너와 보존본을 확인한 뒤 대상별 복구 명령을 사용한다.

```bash
bash infra/scripts/deploy-distributed-observability.sh --recover
bash infra/scripts/deploy-spark-history.sh --recover
```

복구 명령은 저장된 컨테이너만 되돌리며 이미지, HDFS 데이터와 Docker volume을 삭제하지
않는다. 복구 후에도 검증이 실패하면 컨테이너 로그와 HDFS 상태를 확인하고 수동 복구 필요
상태로 유지한다.
