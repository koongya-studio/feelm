# FEELM Monitoring

Spring Boot, 두 호스트, PostgreSQL, Redis와 Kafka의 메트릭을 Prometheus가 수집하고
Grafana가 시각화합니다. 서비스 노드 `J15E106`의 Node Exporter는 Compose 내부에서
수집하고, 분산 노드 `J15E106A`는 사설 IP에만 게시한 Node Exporter와 Kafka Exporter를
중앙 Prometheus가 수집합니다. Spark, HDFS와 브라우저 RUM 메트릭은 이 구성에 포함하지
않습니다.

## 보안 경계

- Prometheus와 Grafana 호스트 포트는 `127.0.0.1`에만 바인딩합니다.
- 분산 노드의 Node Exporter `9100/tcp`는 `DISTRIBUTED_NODE_IP`에만 바인딩합니다.
- 분산 노드의 Kafka Exporter `9308/tcp`도 `DISTRIBUTED_NODE_IP`에만 바인딩하며 Kafka
  broker에는 같은 호스트의 `127.0.0.1:9092`로만 접속합니다.
- 보안 그룹과 호스트 방화벽은 서비스 노드의 사설 IP에서 분산 노드 `9100/tcp`와
  `9308/tcp`로 향하는
  트래픽만 허용합니다. Compose의 사설 IP 바인딩만으로 송신 주체 제한이 완성되지는 않습니다.
- Nginx는 `/actuator/prometheus`를 프록시하지 않습니다.
- Grafana 익명 가입과 익명 접근은 비활성화합니다.
- `GRAFANA_ADMIN_PASSWORD`는 필수 Secret이며 실제 값은 `infra/.env`에만 둡니다.
- `GRAFANA_ALERT_HMAC_SECRET`은 Grafana와 내부 알림 릴레이 사이의 서명 Secret이며
  `infra/.env` 또는 GitLab CI/CD Variables에만 둡니다.
- 알림 릴레이 health 포트도 `127.0.0.1`에만 바인딩하며 외부에 공개하지 않습니다.
- 공개 Grafana 경로는 Nginx의 별도 Basic Auth와 Grafana 로그인을 모두 통과해야 합니다.
- 원격 접근은 HTTPS `/grafana/`를 사용하고, SSH 터널은 공개 경로 장애 시 복구 수단으로만
  유지합니다. Grafana와 Prometheus의 원본 포트는 공개하지 않습니다.

## Mattermost 장애 알림

Grafana는 HMAC-SHA256으로 서명한 일반 Webhook을 Compose 내부의
`mattermost-alert-relay`에 보냅니다. 릴레이는 기존 `deploy-*` 알림과 같은 Mattermost 계정 및
채널 설정으로 로그인하고, `/api/v4/posts` 게시 후 로그아웃합니다. Mattermost Incoming
Webhook 기능은 사용하지 않습니다. 계정 비밀번호는 Grafana에 전달하지 않고 릴레이에만
주입하며, 서명에는 timestamp를 포함해 5분이 지난 요청을 거부합니다.

서비스 환경의 GitLab CI/CD Variable은 다음 메타데이터로 등록합니다.

| Key | 환경 범위 | 보호 설정 | 값 |
| --- | --- | --- | --- |
| `GRAFANA_ALERT_HMAC_SECRET` | `service` | Protected, Masked and hidden, Expand variable reference 비활성화 | 충분히 긴 임의의 Webhook HMAC 서명 키 |

기존 `MATTERMOST_BASE_URL`, `MATTERMOST_LOGIN_ID`, `MATTERMOST_PASSWORD`와 채널 선택 변수는
deploy 알림과 동일한 값을 사용합니다. `MATTERMOST_PASSWORD`는 상시 릴레이에 주입되므로 개인
계정 대신 알림 전용 계정을 우선하고, 계정 변경·프로젝트 종료 시 즉시 폐기합니다.

파일 provisioning은 Grafana 기동 시 연락 지점, 메시지 템플릿과 아래 규칙을 함께 적용합니다.
각 규칙은 짧은 일시 이상을 `for` 동안 보류하고, 복구 뒤 2분 동안 상태를 유지해 flapping을
줄입니다. 같은 장애는 최초 알림 뒤 4시간에 한 번만 반복하고 복구 시 별도 알림을 보냅니다.

| 규칙 | 초기 임계치 | 지속 시간 | 심각도 |
| --- | --- | --- | --- |
| Backend target down | `up < 1` | 2분 | critical |
| Exporter target down | node, Kafka, PostgreSQL, Redis `up < 1` | 5분 | critical |
| Backend 5xx 비율 | 5분 비율 5% 초과 | 5분 | warning |
| Backend P95 지연 | 1.5초 초과 | 10분 | warning |
| Host CPU | 90% 초과 | 10분 | warning |
| Host memory | 90% 초과 | 10분 | warning |
| Host filesystem 여유 | 15% 미만 | 10분 | critical |

임계치 또는 지속 시간을 바꿀 때는
`grafana/provisioning/alerting/feelm-alerts.json`의 threshold `params`와 `for`를 같은 MR에서
변경하고, 변경 이유와 관측 기간을 기록합니다. Grafana UI에서 provisioned 규칙을 임시로
복제하거나 편집하지 않습니다. 알림 정책 트리는 팀이 UI에서 만든 다른 정책을 덮어쓰지 않도록
provisioning하지 않고, 각 규칙이 `FEELM Mattermost` 연락 지점을 직접 사용합니다.

### 테스트와 복구 확인

1. Grafana의 **Alerting → Contact points → FEELM Mattermost**에서 Test를 실행하고 기존 deploy
   알림 채널에 `ALERT` 테스트 메시지가 도착하는지 확인합니다. 서명 키나 요청 본문 전체를
   브라우저 개발자 도구, 캡처 또는 로그에 남기지 않습니다.
2. 운영 영향이 없는 승인된 점검 시간에 모니터링 exporter 하나만 중지합니다. Redis 서비스
   자체는 내리지 않고 `docker stop feelm-redis-exporter`만 실행합니다.
3. Prometheus에서 `up{job="redis"} == 0`을 확인하고 5분 뒤 Mattermost의 장애 알림을 확인합니다.
4. `docker start feelm-redis-exporter`로 즉시 복구하고 `up{job="redis"} == 1`을 확인합니다.
5. 상태 유지 2분과 다음 평가 주기가 지난 뒤 같은 채널에 `RECOVERY` 알림이 도착하는지
   확인합니다. 복구 알림이 없으면 Contact point delivery 상태와 Grafana 로그를 확인하되
   HMAC 서명 키를 출력하지 않습니다.

### Silence와 장애 확인

계획 점검 중에는 **Alerting → Silences**에서 `alertname` 또는 `component` matcher, 종료 시각과
사유를 명시해 silence를 만듭니다. 규칙을 pause하거나 provisioning 파일을 임시 변경하지
않습니다. 점검이 끝나면 exporter와 target이 정상인지 먼저 확인하고 silence를 만료시킵니다.

장애 알림을 받으면 Mattermost의 Grafana 링크에서 firing instance와 label을 확인하고,
Prometheus의 같은 식으로 실제 값을 확인합니다. target down은 아래 복구 절차를 따르고,
5xx·지연은 최근 배포와 백엔드 로그를, 호스트 자원은 `node_name`과 `mountpoint`를 기준으로
확인합니다. 복구 후 Mattermost의 `RECOVERY`까지 확인해야 종료합니다. Grafana 자체 또는
릴레이 전송 장애로 알림이 오지 않을 수 있으므로 배포 Job의 알림과 Grafana health를 함께
확인합니다.

## 기동

`infra/.env.example`을 기준으로 개인 `infra/.env`에 `GRAFANA_ADMIN_PASSWORD`,
`GRAFANA_ALERT_HMAC_SECRET`, 기존 Mattermost 계정·채널 변수와 `GRAFANA_HTPASSWD_PATH`를
추가합니다.
서비스와 모니터링 파일을 같은 Compose 프로젝트로 실행해야 Prometheus가 서비스 이름으로
백엔드와 exporter에 접근할 수 있습니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  up -d postgres redis backend prometheus mattermost-alert-relay grafana node-exporter postgres-exporter redis-exporter
```

분산 노드에서는 기존 분산 Compose 파일의 Node Exporter와 Kafka Exporter를 기동합니다.
둘 다 `restart: unless-stopped`로 Docker 데몬과 함께 복구됩니다. Node Exporter는 호스트의
`/`를 읽기 전용으로만 마운트하고 Kafka Exporter는 읽기 전용 root filesystem을 사용합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/distributed.yml \
  up -d node-exporter kafka-exporter

docker inspect feelm-node-exporter-distributed \
  --format '{{.HostConfig.RestartPolicy.Name}}'
docker port feelm-node-exporter-distributed 9100
docker inspect feelm-kafka-exporter \
  --format '{{.HostConfig.RestartPolicy.Name}} {{.HostConfig.ReadonlyRootfs}}'
curl --fail --max-time 5 "http://${DISTRIBUTED_NODE_IP}:9308/metrics"
```

두 번째 출력은 `<J15E106A 사설 IP>:9100`, 재시작 정책은 `unless-stopped`여야 합니다.
Kafka Exporter 출력은 `unless-stopped true`이고 `/metrics`에는 `kafka_brokers`,
`kafka_topic_partitions`와 Consumer가 실행된 뒤 `kafka_consumergroup_lag`가 있어야 합니다.
클라우드 보안 그룹의 인바운드는 서비스 노드 사설 IP에서 분산 노드의 `9100/tcp`와
`9308/tcp`로 향하는 트래픽만 허용합니다.
호스트에서 UFW를 사용하는 경우에도 같은 출발지와 목적지로 제한하며, 현재 SSH 허용 규칙을
확인하지 않은 채 방화벽을 활성화하거나 초기화하지 않습니다.

Kafka Exporter는 `network_mode: host`이므로 Docker의 `DOCKER-USER` 체인이 아니라 호스트
`INPUT` 정책에서 `9308/tcp`의 송신 주체를 제한합니다. 기존 방화벽 관리 방식에 맞춰 서비스
노드 사설 IP의 접근을 먼저 허용하고 나머지 접근을 차단합니다. 실제 IP나 전체 방화벽 규칙은
저장소와 작업 로그에 남기지 않습니다. Broker의 listener, 인증과 데이터 경로는 변경하지
않으며 exporter는 metadata와 Consumer Group offset을 조회합니다. 동시 scrape마다 Kafka를
재조회하지 않고 topic worker 수를 10으로 제한해 broker 부하를 제한합니다.

클라우드 보안 그룹을 변경할 수 없는 환경에서는 분산 노드의 Docker `DOCKER-USER` 체인에
서비스 노드 사설 IP의 `9100/tcp`만 허용하고 나머지는 차단합니다. Docker 기동 뒤 규칙을
재현하도록 별도 oneshot systemd 서비스를 등록하고, 전체 Docker 규칙을 그대로 영구 저장하지
않습니다. 서비스에는 허용 규칙을 먼저 추가하고 차단 규칙을 그다음에 추가해야 합니다.

```ini
[Unit]
Description=Restrict distributed Node Exporter access
Requires=docker.service
After=docker.service
PartOf=docker.service

[Service]
Type=oneshot
ExecStartPre=-/usr/sbin/iptables -D DOCKER-USER -p tcp -s <SERVICE_NODE_PRIVATE_IP>/32 --dport 9100 -j ACCEPT
ExecStartPre=-/usr/sbin/iptables -D DOCKER-USER -p tcp --dport 9100 -j DROP
ExecStart=/usr/sbin/iptables -I DOCKER-USER 1 -p tcp -s <SERVICE_NODE_PRIVATE_IP>/32 --dport 9100 -j ACCEPT
ExecStart=/usr/sbin/iptables -I DOCKER-USER 2 -p tcp --dport 9100 -j DROP
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
```

위 내용을 분산 노드의 `/etc/systemd/system/feelm-node-exporter-firewall.service`에 설치한 뒤
`systemctl daemon-reload`와 `systemctl enable --now feelm-node-exporter-firewall.service`를
실행합니다. `iptables -L DOCKER-USER -n -v --line-numbers`에서 서비스 노드 허용 규칙이
차단 규칙보다 앞에 있는지 확인하고, 서비스 노드에서는 HTTP 200, 공인망에서는 TCP 연결 실패를
각각 검증합니다. 실제 IP는 저장소에 기록하지 않습니다.

Docker Desktop의 Linux VM에서는 Node Exporter가 요구하는 루트 파일시스템의 `rslave`
마운트 전파를 지원하지 않을 수 있습니다. 이 경우 로컬에서는 `node-exporter`를 제외해
백엔드, PostgreSQL, Redis와 Grafana 연동만 검증합니다. Prometheus의 `node` Job이
`DOWN`으로 표시되는 것은 이 로컬 검증에서 예상된 결과이며, 호스트 메트릭은 Linux 서비스
서버에서 최종 검증합니다.

```powershell
$env:FEELM_ENV_FILE = 'C:\path\to\S15P21E106\infra\.env'
docker compose --env-file $env:FEELM_ENV_FILE `
  -f infra/compose/service.yml `
  -f infra/compose/monitoring.yml `
  up -d postgres redis backend prometheus mattermost-alert-relay grafana postgres-exporter redis-exporter
```

정상 원격 접속 주소는 다음과 같습니다.

```text
https://j15e106.p.ssafy.io/grafana/
```

Nginx Basic Auth 뒤에서도 Grafana 자체 로그인을 유지합니다. 공용 관리자 비밀번호를
팀원에게 전달하지 말고, Grafana의 Server admin 화면에서 사용자별 계정을 만든 뒤
Organization role을 `Viewer`로 지정합니다. 사용자 비밀번호와 Basic Auth 원문은 저장소,
명령행 인자, 로그와 화면 캡처에 남기지 않습니다.

공개 경로 장애 시에는 Grafana의 공개 root URL을 루프백으로 임시 전환한 뒤 SSH 터널을
복구 수단으로 사용합니다. 정상 설정 파일은 수정하지 않고 현재 셸의 환경변수로만
덮어쓰며 Grafana 데이터 볼륨은 유지합니다.

```bash
export GRAFANA_ROOT_URL=http://127.0.0.1:3000/
export GRAFANA_SERVE_FROM_SUB_PATH=false
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  up -d --no-deps --force-recreate grafana
```

이후 로컬 PC에서 아래 터널을 엽니다.

```bash
ssh -L 3000:127.0.0.1:3000 <service-host>
```

이후 브라우저에서 `http://127.0.0.1:3000`에 접속합니다. 관리자 계정은 사용자 생성과
복구에만 사용하고 일상 조회는 Viewer 계정으로 수행합니다. `FEELM / Service Overview`
대시보드는 provisioning으로 자동 생성됩니다.
`FEELM / Kafka Overview`에서는 exporter 가용성, broker 수, 토픽별 메시지 증가율,
partition·under-replicated 상태와 Consumer Group·partition별 lag를 확인합니다. 메시지
증가율은 broker의 현재 offset 증가량으로 계산한 근사치이며 byte 단위 처리량이나 요청
지연은 포함하지 않습니다.

공개 경로를 복구한 뒤 임시 환경변수를 제거하고 정상 root URL로 Grafana만 재생성합니다.

```bash
unset GRAFANA_ROOT_URL GRAFANA_SERVE_FROM_SUB_PATH
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  up -d --no-deps --force-recreate grafana
```

## develop 자동 배포

모니터링 Compose와 Prometheus·Grafana 설정이 보호된 `develop`에 병합되면
`deploy-monitoring` Job이 서비스 노드에서 자동 실행됩니다. MR, Web, API Pipeline과 보호되지
않은 브랜치는 배포하지 않습니다. Job은 먼저 원격 `develop` HEAD와 현재 commit이 같은지
확인하고, `ci-variable-preflight`의 실제 변수·GitLab 메타데이터 검사를 통과한 경우에만
`infra/scripts/deploy-monitoring.sh`를 실행합니다. 변수 계약과 등록 방법은 `infra/README.md`의
Deployment 절을 따릅니다.

배포 스크립트는 배포 Pipeline별 고유 Compose 프로젝트로 `monitoring.yml`만 해석하므로
PostgreSQL, Redis, backend, frontend와 Nginx 및 `-rollback` 컨테이너를 Compose 대상으로
선택하지 않습니다. 후보 프로젝트는 기존 `compose_default` 네트워크와
`compose_prometheus_data`, `compose_grafana_data` 볼륨을 명시적으로 공유합니다.
`config --quiet`와 이미지 pull을 컨테이너 변경 전에 수행하고, 기존 모니터링 컨테이너가
있으면 중지 후 이름 끝에 `-rollback`을 붙여 보존합니다. 후보는
`--no-deps --force-recreate`로 다음 서비스만 교체합니다.

```text
prometheus grafana mattermost-alert-relay node-exporter postgres-exporter redis-exporter
```

후보 기동 후 Prometheus readiness, Grafana database health, 알림 릴레이 health와 Prometheus의
필수 scrape target을 최대 24회, 5초 간격으로 검사합니다. 기대 target은 `backend` 1개, `node` 2개,
`kafka`·`postgres`·`redis` 각 1개이며 모두 `up=1`이어야 합니다. 검증이 성공하면 보존한
컨테이너만 삭제하고 `prometheus_data`, `grafana_data` 볼륨은 유지합니다. 스크립트는
`docker compose down`, `down -v`와 `docker volume rm`을 사용하지 않습니다.

Compose 적용 또는 health 검증이 실패하면 후보 컨테이너만 제거하고 보존한 컨테이너를 원래
이름으로 복구한 뒤 같은 health 검사를 다시 수행합니다. 첫 자동 배포처럼 기존 컨테이너가
없었다면 실패한 후보만 제거합니다. 복구 검증도 실패하면 Job은 수동 복구 필요 상태로
종료하며 Pipeline 성공으로 처리하지 않습니다.

Job 또는 호스트가 컨테이너 보존 이후 비정상 종료되면 다음 배포는 `-rollback` 컨테이너를
발견하고 실제 변경 전에 중단합니다. 서비스 노드에서 실행 중인 모니터링 컨테이너와 보존
컨테이너를 확인한 뒤 다음 명령으로 보존본만 원래 이름에 복구합니다. 이 명령은 볼륨이나
서비스 컨테이너를 삭제하지 않습니다.

```bash
bash infra/scripts/deploy-monitoring.sh --recover
```

복구 명령은 보존본과 같은 이름의 후보만 제거하고 보존 컨테이너를 다시 시작한 뒤 전체
모니터링 health를 검증합니다. 복구가 실패하면 반복 실행하거나 `-rollback` 컨테이너를
임의로 삭제하지 말고 `docker ps -a`와 각 모니터링 컨테이너 상태를 확인합니다. 컨테이너
로그에는 exporter 연결 오류가 포함될 수 있으므로 전체 로그나 환경변수를 CI 출력에 복사하지
않습니다. 이미지 버전 또는 Grafana 데이터 마이그레이션을 동반하는 변경은 이 컨테이너
롤백만으로 데이터 형식 복구를 보장하지 않으므로 별도 Jira에서 백업·복구 절차를 합의합니다.

## 검증

먼저 Compose 구문과 최종 설정을 확인합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  config --quiet
```

Nginx와 Grafana를 재생성하기 전에 Nginx 설정을 검사합니다. Grafana 데이터 볼륨과
Prometheus는 삭제하거나 재생성하지 않습니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  run --rm --no-deps nginx nginx -t
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  up -d --no-deps --force-recreate grafana nginx
```

외부 비인증 요청은 `401`, 인증 후 `/grafana/` 요청은 Grafana 로그인 화면으로 연결되어야
합니다. `/grafana/public/` 정적 파일이 정상 표시되고 브라우저 개발자 도구에서
`/grafana/api/live/ws` WebSocket 연결이 `101`로 전환되는지 확인합니다. Viewer 계정으로
대시보드 조회는 가능하지만 사용자·데이터소스·대시보드 편집은 거부되어야 합니다.
서비스 서버의 공인 주소에서 `3000`과 `9090` 직접 연결이 실패하고, 서비스 서버
루프백의 Grafana health와 Prometheus readiness는 계속 성공해야 합니다.

Nginx 기본 access log는 Basic Auth의 `Authorization` 헤더나 요청 본문을 기록하지 않습니다.
인증 실패를 확인할 때 전체 헤더, 쿠키, 비밀번호 또는 환경변수를 출력하지 않습니다.

컨테이너 기동 후 서비스 호스트에서 다음을 확인합니다.

```bash
docker exec feelm-backend curl --fail --silent http://localhost:8080/actuator/prometheus
curl --fail http://127.0.0.1:9090/-/ready
curl --fail http://127.0.0.1:3000/api/health
curl --fail 'http://127.0.0.1:9090/api/v1/query?query=up'
```

Prometheus의 `up` 조회에서 `backend`, `node`, `postgres`, `redis` Job이 모두 `1`이어야
합니다. 이어서 서비스 노드에서 분산 노드의 사설 경로와 exporter 응답을 확인합니다.

```bash
curl --fail --max-time 5 'http://<J15E106A 사설 IP>:9100/metrics'
curl --fail --max-time 5 'http://<J15E106A 사설 IP>:9308/metrics'
curl --fail --get 'http://127.0.0.1:9090/api/v1/query' \
  --data-urlencode 'query=up{job="node"}'
curl --fail --get 'http://127.0.0.1:9090/api/v1/query' \
  --data-urlencode 'query=up{job="kafka"}'
curl --fail --get 'http://127.0.0.1:9090/api/v1/query' \
  --data-urlencode 'query=sum by (consumergroup,topic) (kafka_consumergroup_lag{job="kafka"})'
```

`up{job="node"}` 결과에는 다음 두 시계열이 모두 `1`로 나타나야 합니다.

| 대상 | `node_name` | `node_role` |
| --- | --- | --- |
| 서비스 노드 Compose exporter | `J15E106` | `service` |
| 분산 노드 사설 IP exporter | `J15E106A` | `distributed` |

`up{job="kafka"}`는 `1`, `kafka_brokers{job="kafka"}`는 현재 단일 broker 구성에서 `1`이어야
합니다. `feelm.rating.v1`에 테스트 메시지를 쓰고 Consumer를 잠시 중단하면
`kafka_consumergroup_lag`가 증가하고, Consumer를 재개하면 감소해야 합니다. 운영 데이터에
시험 메시지를 넣지 말고 승인된 테스트 topic·group 또는 합성 환경에서 검증합니다. Consumer
Group이 한 번도 offset을 commit하지 않았다면 lag 시계열이 생성되지 않는 것이 정상입니다.

정상 경로는 서비스 노드에서 다음 명령으로 자동 확인할 수 있습니다.

```bash
python3 infra/scripts/verify_kafka_monitoring.py \
  --consumer-group feelm-foldin-consumer-v1 \
  --max-lag 0
```

lag 증가·회복 경로는 승인된 합성 topic과 group에서 Consumer를 중단한 뒤 `--min-lag 1`로
증가를 확인하고, 재개 후 `--max-lag 0`으로 회복을 확인합니다. Kafka Exporter 중단 시에는
같은 명령이 `Kafka exporter target이 DOWN입니다`로 실패해야 합니다. 실제 서비스의 정상
lag 허용값이 0보다 크다면 운영 합의값을 사용하며 검증을 통과시키기 위해 임의로 늘리지
않습니다.

Grafana의 `Service Overview` 대시보드에서 두 노드의 CPU, 메모리, 디스크 처리량,
filesystem 여유 비율과 네트워크 송수신량을 `node_name`별로 구분해 확인합니다. HTTP 요청이
발생한 이후에는 기존 백엔드 요청량, 오류율과 P95 지연 시간도 함께 표시됩니다.

## Target down 복구

분산 노드가 `DOWN`이면 공개 포트나 넓은 CIDR 허용으로 우회하지 않고 아래 순서로 확인합니다.

1. 분산 노드에서 `docker ps --filter name=feelm-node-exporter-distributed`와
   `docker logs --tail 100 feelm-node-exporter-distributed`를 확인합니다.
2. `docker port feelm-node-exporter-distributed 9100`이 분산 노드 사설 IP에만 바인딩됐는지
   확인합니다.
3. 서비스 노드에서 `${DISTRIBUTED_NODE_IP}:9100`으로의 사설 라우팅과 보안 그룹·호스트
   방화벽의 단일 출발지 허용을 확인합니다.
4. 호스트의 `curl`과 Prometheus의 `up{job="node"}` 조회 순서로 장애 경계를 좁힙니다.
5. 설정을 수정했다면 `docker compose ... config`를 먼저 통과시킨 뒤 해당 exporter 또는
   Prometheus만 재생성합니다. 데이터 볼륨이나 다른 분산 서비스를 내리지 않습니다.

설정 변경만으로 재부팅 후 복구를 완료로 간주하지 않습니다. 운영 적용 시 분산 노드 재부팅
후 exporter의 실행 상태, 사설 포트 바인딩과 두 `up` 시계열을 다시 확인해 검증 기록에
남깁니다.

Kafka target만 `DOWN`이면 공개 포트나 넓은 CIDR 허용으로 우회하지 않고 다음 순서로
확인합니다.

1. 분산 노드에서 `docker ps --filter name=feelm-kafka-exporter`와 exporter 로그를 확인합니다.
2. exporter 명령의 listen 주소가 분산 노드 사설 IP이고 broker 주소가
   `127.0.0.1:9092`인지 확인합니다.
3. 분산 노드의 `/metrics`, 서비스 노드에서 사설 IP의 `/metrics`, Prometheus
   `up{job="kafka"}` 순으로 장애 경계를 좁힙니다.
4. broker가 정상인데 exporter만 실패하면 exporter만 재생성합니다. Kafka나 Consumer를
   함께 재시작하거나 `kafka_data` 볼륨을 삭제하지 않습니다.
5. exporter를 중지해 Prometheus에서 `up=0`을 확인한 뒤 다시 기동해 `up=1`로 복구되는
   경로를 검증합니다.

볼륨을 삭제하면 Prometheus 이력과 Grafana 내부 상태가 함께 사라집니다. 운영 중에는
`docker compose down -v` 또는 `docker volume rm`을 사용하지 않습니다.
