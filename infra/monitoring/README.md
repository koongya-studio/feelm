# FEELM Monitoring

Spring Boot, 두 호스트, PostgreSQL과 Redis의 메트릭을 Prometheus가 수집하고 Grafana가
시각화합니다. 서비스 노드 `J15E106`의 Node Exporter는 Compose 내부에서 수집하고, 분산
노드 `J15E106A`는 사설 IP에만 게시한 Node Exporter를 중앙 Prometheus가 수집합니다.
Kafka, Spark, HDFS와 브라우저 RUM 메트릭은 이 구성에 포함하지 않습니다.

## 보안 경계

- Prometheus와 Grafana 호스트 포트는 `127.0.0.1`에만 바인딩합니다.
- 분산 노드의 Node Exporter `9100/tcp`는 `DISTRIBUTED_NODE_IP`에만 바인딩합니다.
- 보안 그룹과 호스트 방화벽은 서비스 노드의 사설 IP에서 분산 노드 `9100/tcp`로 향하는
  트래픽만 허용합니다. Compose의 사설 IP 바인딩만으로 송신 주체 제한이 완성되지는 않습니다.
- Nginx는 `/actuator/prometheus`를 프록시하지 않습니다.
- Grafana 익명 가입과 익명 접근은 비활성화합니다.
- `GRAFANA_ADMIN_PASSWORD`는 필수 Secret이며 실제 값은 `infra/.env`에만 둡니다.
- 원격에서 Grafana를 볼 때는 포트를 공개하지 않고 SSH 터널을 사용합니다.

## 기동

`infra/.env.example`을 기준으로 개인 `infra/.env`에 `GRAFANA_ADMIN_PASSWORD`를 추가합니다.
서비스와 모니터링 파일을 같은 Compose 프로젝트로 실행해야 Prometheus가 서비스 이름으로
백엔드와 exporter에 접근할 수 있습니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  up -d postgres redis backend prometheus grafana node-exporter postgres-exporter redis-exporter
```

분산 노드에서는 기존 분산 Compose 파일의 Node Exporter를 기동합니다. `restart:
unless-stopped`로 Docker 데몬과 함께 복구되며, 호스트의 `/`는 읽기 전용으로만 마운트합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/distributed.yml \
  up -d node-exporter

docker inspect feelm-node-exporter-distributed \
  --format '{{.HostConfig.RestartPolicy.Name}}'
docker port feelm-node-exporter-distributed 9100
```

두 번째 출력은 `<J15E106A 사설 IP>:9100`, 재시작 정책은 `unless-stopped`여야 합니다.
클라우드 보안 그룹의 인바운드는 `J15E106 사설 IP/32 -> J15E106A:9100/tcp`만 허용합니다.
호스트에서 UFW를 사용하는 경우에도 같은 출발지와 목적지로 제한하며, 현재 SSH 허용 규칙을
확인하지 않은 채 방화벽을 활성화하거나 초기화하지 않습니다.

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
  up -d postgres redis backend prometheus grafana postgres-exporter redis-exporter
```

서비스 서버 밖에서 접속할 때는 로컬 포트 포워딩을 사용합니다.

```bash
ssh -L 3000:127.0.0.1:3000 <service-host>
```

이후 브라우저에서 `http://127.0.0.1:3000`에 접속하고 사용자 `admin`과
`GRAFANA_ADMIN_PASSWORD` 값으로 로그인합니다. `FEELM / Service Overview` 대시보드는
provisioning으로 자동 생성됩니다.

## 검증

먼저 Compose 구문과 최종 설정을 확인합니다.

```bash
docker compose --env-file infra/.env \
  -f infra/compose/service.yml \
  -f infra/compose/monitoring.yml \
  config --quiet
```

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
curl --fail --get 'http://127.0.0.1:9090/api/v1/query' \
  --data-urlencode 'query=up{job="node"}'
```

`up{job="node"}` 결과에는 다음 두 시계열이 모두 `1`로 나타나야 합니다.

| 대상 | `node_name` | `node_role` |
| --- | --- | --- |
| 서비스 노드 Compose exporter | `J15E106` | `service` |
| 분산 노드 사설 IP exporter | `J15E106A` | `distributed` |

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

볼륨을 삭제하면 Prometheus 이력과 Grafana 내부 상태가 함께 사라집니다. 운영 중에는
`docker compose down -v` 또는 `docker volume rm`을 사용하지 않습니다.
