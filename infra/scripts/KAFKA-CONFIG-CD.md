# Kafka 런타임 설정 CD

보호된 main에서 Kafka 또는 분산 Compose 설정이 변경되면 분산 Runner의 수동 배포 Job이
생성된다. Job은 현재 컨테이너의 Cluster ID와 `distributed` 환경 범위의
`KAFKA_CLUSTER_ID`가 일치하는지 확인한 뒤 Kafka 컨테이너만 재생성한다. Cluster ID 값은
로그에 출력하지 않는다.

Kafka named volume과 `/tmp/kraft-combined-logs`는 삭제하거나 초기화하지 않는다. 배포 후
Metadata Quorum 상태를 확인하고 격리된 임시 topic에서 produce-consume을 검증한 뒤 topic을
삭제한다. 후보 검증 실패 시 직전 정상 release의 Compose 설정으로 Kafka를 복구하고 같은
검증을 다시 수행한다.

527에서 준비한 `/var/lib/feelm-deploy/runtime-config`를 그대로 사용한다. 최초 main 배포 전
현재 실행 설정과 일치하는 main SHA를 한 번 기록한다.

    sudo -u gitlab-runner \
      bash infra/scripts/deploy-kafka-config.sh --initialize-state <main-sha>

초기화 후 main 파이프라인에서 `deploy-kafka-config`를 수동 실행한다. Broker 역할 전환,
KRaft metadata migration, retention 정책 변경과 애플리케이션 이벤트 계약 변경은 이 Job의
범위가 아니다.
