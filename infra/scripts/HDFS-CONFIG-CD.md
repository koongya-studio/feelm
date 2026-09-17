# HDFS 런타임 설정 CD

보호된 main에서 HDFS 또는 관련 Compose 설정이 변경되면 수동 승인 Job 두 개가 생성된다.
서비스 노드의 DataNode 1을 먼저 배포하고 검증한 뒤 분산 노드의 NameNode와 DataNode 2를
순서대로 배포한다. 각 단계에서 두 DataNode의 live 상태와 격리된 HDFS 경로의 쓰기·읽기를
검증한다. Compose 명령은 named volume을 삭제하거나 NameNode를 포맷하지 않는다.

서비스·분산 Runner 호스트에 각각 최초 한 번 공통 release 저장소를 준비한다.

    sudo install -d -o gitlab-runner -g gitlab-runner -m 700 \
      /var/lib/feelm-deploy/runtime-config

GitLab에는 같은 이름의 File 타입 Protected 변수 HDFS_COMPOSE_ENV_FILE을 service와
distributed 환경 범위에 각각 등록한다. 값은 각 노드에서 검증된 Compose 환경 파일이다.
Secret 값이나 파일 내용은 Job 로그에 출력하지 않는다.

자동 배포 전에 현재 실행 설정과 일치하는 main SHA를 각 노드에서 한 번 초기화한다.

    sudo -u gitlab-runner env \
      HDFS_COMPOSE_ENV_FILE=/path/to/approved/infra.env \
      bash infra/scripts/deploy-hdfs-config.sh --initialize-state service <main-sha>

분산 노드에서는 service 대신 distributed를 사용한다. 초기화는 실행 중인 설정과 지정한
SHA가 실제로 일치하는지 운영자가 확인한 뒤 수행한다.

후보와 직전 정상본은 커밋별 immutable release로 보관한다. 배포 실패 시 공통 배포 엔진이
직전 release의 Compose 설정으로 같은 서비스들을 복구하고 검증한다. 서비스 노드 Job 성공
후 분산 노드 Job이 실패하면 서비스 노드는 새 설정, 분산 노드는 이전 설정으로 남을 수
있으므로 분산 노드 로그의 수동 복구 안내를 확인하고 서비스 노드의 이전 SHA 재배포 여부를
운영자가 결정한다.
