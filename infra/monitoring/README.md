# FEELM Monitoring

Spring Boot, 서비스 노드, PostgreSQL과 Redis의 메트릭을 Prometheus가 수집하고 Grafana가
시각화합니다. Kafka, Spark, HDFS와 브라우저 RUM은 이 1차 구성에 포함하지 않습니다.

## 보안 경계

- Prometheus와 Grafana 호스트 포트는 `127.0.0.1`에만 바인딩합니다.
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
합니다. Grafana 대시보드에는 HTTP 요청이 발생한 이후 요청량, 오류율과 P95 지연 시간이
표시됩니다.

볼륨을 삭제하면 Prometheus 이력과 Grafana 내부 상태가 함께 사라집니다. 운영 중에는
`docker compose down -v` 또는 `docker volume rm`을 사용하지 않습니다.
