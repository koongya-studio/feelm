# S15P21E106-401 Fold-in fencing 실환경 검증

## 범위와 환경

2026-09-16, `origin/develop` `da1bbf4`의 Consumer 코드를 기반으로 한 격리 EC2 환경에서
검증했다. Consumer 이미지 `feelm-foldin-consumer:local`, Redis `8.2.9-alpine`, PostgreSQL
`16-alpine`, Kafka `4.3.1`을 사용했다. Redis·PostgreSQL·Kafka는 전용 Docker bridge network에
각각 별도 컨테이너로 띄웠고 호스트 포트를 공개하지 않았다. Kafka topic/group, Redis 키,
PostgreSQL 사용자 행은 실행마다 새로운 테스트 식별자를 사용했다. 운영 서비스의 컨테이너·topic·
group·Redis 키·DB 행은 변경하지 않았다.

`tools/verify_fencing_integration.py`는 Redis Lua의 old/new fence 및 부분 상태 복구를 검증하고,
실제 Kafka broker에서 같은 group의 두 Consumer가 소유권을 교체하는 동안 PostgreSQL에서 읽은
이전 snapshot이 뒤늦게 게시되는 순서를 재현한다. `tools/verify_fencing_outage.py`는 전용 Redis 또는
Kafka 컨테이너를 실제로 중지·재시작하는 사이에 게시·commit 실패와 replay를 검증한다.
`tools/verify_fencing_runner_e2e.py`는 실제 `runner.run()` 프로세스 두 개를 실행해 같은 순서의
assign→fence→snapshot→publish→commit 경로를 검증한다. 세 도구 모두 `FEELM_401_ISOLATED=YES`와
전용 컨테이너·DB 이름의 정확한 allowlist를 통과해야 한다. 테스트 user ID는 매번 임의 생성하고,
기존 DB 행은 삭제하지 않는다.

검증 도구는 실제 Redis Lua, Kafka Consumer group과 동기 commit, PostgreSQL 읽기 전용 snapshot을
사용한다. runner 검증에서는 모델 로딩과 점수 계산만 fixture로 대체하고 나머지 실행 루프를 사용한다.
전체 모델 산출물 로딩, 추천 품질, 실제 배포 설정·HDFS 연결 및 운영 부하는 검증하지 않았다.

## 재현 절차

1. 기존 서비스와 별도의 Docker network에서 위 세 서비스와 Consumer 이미지를 준비한다. Redis와
   PostgreSQL은 호스트 포트를 공개하지 않는다. PostgreSQL에는 전용 테스트 DB만 사용한다.
   Consumer 코드 사본은 이 Jira 전용 폴더에 놓고, 컨테이너에 bind mount한다.
2. 전용 환경 파일에 `FEELM_401_ISOLATED=YES`, `FEELM_401_KAFKA`,
   `FEELM_401_REDIS_URL`, `FEELM_401_POSTGRES_DSN`과 아래 두 결과 경로를 설정한다.
   실제 URI나 자격증명은 문서·명령 기록·Git에 넣지 않는다. 테스트 프로세스의 `PYTHONPATH`는 복사한
   `pipeline/consumer/src`를 가리킨다.
   `<401-test-endpoints-file>`에는 다음 항목도 포함한다. 경로는 컨테이너 안에서 보이는
   bind mount 경로다.

```text
FEELM_401_REPORT_PATH=/work/runtime/integration.json
FEELM_401_RUNNER_REPORT_DIR=/work/runtime/runner
PYTHONPATH=/work/pipeline/consumer/src
```

   실행 전 호스트에서 결과 디렉터리를 만들고, Consumer 이미지의 UID/GID `10001:10001`이
   쓸 수 있게 한다. 새 실행에는 비어 있는 `runner`, `redis3`, `kafka2` 폴더를 사용한다.
   기존 결과가 있으면 다른 401 전용 소스 사본/결과 루트에서 시작하고 덮어쓰지 않는다.

```bash
sudo install -d -o 10001 -g 10001 -m 0700 /home/ubuntu/S15P21E106-401/runtime
sudo install -d -o 10001 -g 10001 -m 0700 /home/ubuntu/S15P21E106-401/runtime/runner
sudo install -d -o 10001 -g 10001 -m 0700 /home/ubuntu/S15P21E106-401/runtime/redis3
sudo install -d -o 10001 -g 10001 -m 0700 /home/ubuntu/S15P21E106-401/runtime/kafka2
```

   `runner`는 시작 시 반드시 비어 있어야 하며, `integration.json`은 새 실행 전에 없어야 한다.
   이번 실행은 다음 형태였다. 격리 endpoint 이름은 도구의 allowlist와 동일해야 한다.
   이 환경은 Docker 내부 전용 PostgreSQL에만 `trust` 인증을 사용하며 운영 인증과 무관하다.

```bash
docker network create feelm401-net
docker run -d --name feelm401-redis --network feelm401-net redis:8.2.9-alpine \
  redis-server --save '' --appendonly no
docker run -d --name feelm401-postgres --network feelm401-net \
  -e POSTGRES_USER=feelm401 -e POSTGRES_DB=feelm401 \
  -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine
docker run -d --name feelm401-kafka --network feelm401-net \
  --env-file <401-kafka-settings-file> apache/kafka:4.3.1
docker run --network feelm401-net --env-file <401-test-endpoints-file> \
  --mount type=bind,src=/home/ubuntu/S15P21E106-401,dst=/work \
  --entrypoint /app/.venv/bin/python feelm-foldin-consumer:local \
  /work/pipeline/consumer/tools/verify_fencing_integration.py --report
```

   Kafka 환경 파일은 1 broker/1 controller의 KRaft, PLAINTEXT 내부 listener, replication factor 1,
   auto topic creation off로 구성했다. 호스트 포트와 운영 network에는 연결하지 않았다. 실제 실행한
   Docker network와 컨테이너 이름은 `feelm401-*`로 고정했다. 세 서비스 모두 호스트 포트를 열지 않았다.
3. 앞에서 실행한 통합 도구에 이어 같은 환경 파일과 bind mount를 사용해 Consumer 이미지의
   Python으로 아래 세 명령을 순서대로 실행한다. 각 도구는 새 topic/group과 DB fixture를 직접
   만들고 종료한다. 장애 도구에는 비어 있고 컨테이너가 쓸 수 있는 체크포인트 폴더를 준다.

```bash
docker run --network feelm401-net --env-file <401-test-endpoints-file> \
  --mount type=bind,src=/home/ubuntu/S15P21E106-401,dst=/work \
  --entrypoint /app/.venv/bin/python feelm-foldin-consumer:local \
  /work/pipeline/consumer/tools/verify_fencing_runner_e2e.py
docker run --network feelm401-net --env-file <401-test-endpoints-file> \
  --mount type=bind,src=/home/ubuntu/S15P21E106-401,dst=/work \
  --entrypoint /app/.venv/bin/python feelm-foldin-consumer:local \
  /work/pipeline/consumer/tools/verify_fencing_outage.py redis \
  --checkpoint-dir /work/runtime/redis3
docker run --network feelm401-net --env-file <401-test-endpoints-file> \
  --mount type=bind,src=/home/ubuntu/S15P21E106-401,dst=/work \
  --entrypoint /app/.venv/bin/python feelm-foldin-consumer:local \
  /work/pipeline/consumer/tools/verify_fencing_outage.py kafka \
  --checkpoint-dir /work/runtime/kafka2
```

4. 장애 도구가 `ready` 파일을 만들면 **해당 401 전용 컨테이너만** 중지하고 `go` 파일을 만든다.
   `failed`가 생기면 같은 컨테이너를 재시작해 준비 상태를 확인하고 `recovered` 파일을 만든다.
   Redis와 Kafka 시나리오는 서로 다른 빈 체크포인트 폴더에서 순서대로 실행한다. 운영 서비스는
   중지하지 않는다. 아래 명령에서 `<401-service>`는 Redis 또는 Kafka의 **전용 테스트 컨테이너**다.

```bash
docker stop --timeout 5 <401-service>
touch <401-runtime>/<scenario>/go
# failed 체크포인트와 실제 서비스 실패를 확인한 뒤
docker start <401-service>
# 해당 서비스가 준비된 뒤에만
touch <401-runtime>/<scenario>/recovered
```
5. 종료 코드와 요약 JSON, 전후 Redis 3키와 해당 group의 committed offset을 확인한다.
   검증이 끝나면 테스트용 컨테이너·전용 network·fixture만 식별해 정리한다. 실패해도 다른
   Docker 자원이나 Git worktree는 정리 대상에 포함하지 않는다.

## 결과

| 조건 | 실제 관측 |
| --- | --- |
| T1 이전 fence의 Redis Lua 게시 거부 | old token 게시 거부; 세 결과 키 변화 없음 |
| T2 새 fence의 원자 게시 | 새 token만 게시 순번 1, vector·candidates의 generationId 일치 |
| T3 부분 상태 복구 소유권 | old token은 손상된 세 키를 삭제하지 못함; 새 token만 복구 삭제 |
| T4 실제 Kafka rebalance | 이전 PostgreSQL snapshot 점수 1.0은 게시 거부, 이전 generation의 offset commit 거부. 새 snapshot 점수 5.0 게시 후 committed offset 1 |
| T4b 실제 `runner.run()` 재할당 | 이전 프로세스는 snapshot 1.0을 읽고 멈춤. 새 프로세스는 snapshot 5.0을 게시하고 offset 1 커밋. 이전 프로세스 재개 후 stale publish 거부, 이전 commit 호출 없음. Redis 3키·offset 불변 |
| T5 실제 Redis 중단·복구 | 연결 오류로 게시 중단, offset 미커밋. 재시작 뒤 새 Consumer replay로 게시 순번 1, committed offset 1 |
| T6 실제 Kafka 중단·복구 | Redis 게시 뒤 동기 commit 실패. broker 재시작 뒤 replay로 게시 순번 2, committed offset 1 |

보강 재실행의 네 검증 컨테이너 종료 코드는 모두 0이다. 처음 보강된 통합 도구 실행은 결과 JSON을
쓰기 전에 401 runtime 폴더 권한 때문에 실패했으므로 성공으로 세지 않았다. 폴더 권한을 조정하고
새 테스트 식별자로 재실행해 통과했다. 그 앞의 Redis/Kafka/PG 검증 단언은 실패 실행에서도 완료됐지만,
최종 증거는 재실행 결과만 사용한다. Redis 정상 게시 후 old publish 및 손상된 부분 상태에서 old
publish의 전후 세 키도 JSON에 각각 기록했다.

EC2의 `/home/ubuntu/S15P21E106-401/runtime/`에 `integration.json`, `runner/result.json`,
`redis3/result.json`, `kafka2/result.json` 원본 관측을 보존했다. 실행 ID는 각각
`6b8125de9d9c`, `a9fcfeeb2e9b`, `d389cc51c027`, `69915814d5ef`다. Consumer 이미지 digest는
`sha256:0c11c64afeebb568a7606848be7f917eab064b9ca3da1a08eb9c9afd1c7c9df8`, 실행된
`runner.py` SHA-256은 `e1c3b6b35ea597a9c3402c2a182850d08d655a67053de3e34c7d52712b3b2844`다.
원본 JSON에는 테스트용 값만 있으며 endpoint·인증 정보는 없다.

첫 Redis 장애 시도에서는 검증 도구가 Kafka group 소유권 만료 뒤 같은 Consumer로 commit하려다
`UNKNOWN_MEMBER_ID`를 받았다. 이는 기존 소유자가 commit할 수 없다는 실제 동작을 보여주지만
재시도 성공까지 입증하지 못하므로, 도구를 새 Consumer replay 경로로 수정하고 다시 실행해
위 T5 결과를 얻었다. 최초 시도를 성공으로 계산하지 않았다.

최초 검증 종료 후 401 전용 임시 컨테이너 6개와 전용 Docker network 1개를 제거했다. 그 실행의
테스트 DB·Kafka offset·Redis 값은 사라졌다. 보강 검증의 격리 컨테이너도 종료 후 정리하며,
EC2의 401 전용 소스·원본 결과 폴더는 후속 검토를 위해 남긴다.

## 실패 정리·재시도와 미검증 범위

- `ready` 전에 실패하면 어떤 서비스를 중단하기 전에 검증 도구를 수정하고 새 topic/group·빈
  체크포인트 폴더에서 재시작한다. Redis/Kafka가 중단된 상태로 남으면 **해당 전용 컨테이너만**
  재시작하고 준비 상태를 확인한다. `failed` 전에 중단되었다면 offset과 Redis 3키를 먼저 읽어
  이전 시도를 불완전으로 기록한다.
- commit 응답을 받지 못한 경우 Redis 게시 여부만으로 offset 성공을 추정하지 않는다. broker의
  committed offset을 읽고 새 Consumer가 같은 이벤트를 replay하는지 확인한다. Redis 결과 세 키는
  게시 순번과 두 payload의 generationId를 함께 비교한다.
- `runner.run()`은 실행했지만 모델 로딩·점수 계산을 fixture로 대체했다. 따라서 운영 모델 계산까지
  끝까지 실행한 시험은 아니다. 재할당 장벽은 실제 Consumer 프로세스의 PostgreSQL snapshot 직후
  만들었고, 기존 `process_user` 직접 호출 검증과 별도 증거로 기록했다.
  모든 가능한 rebalance 타이밍, Redis의 과거 snapshot 복원, 운영 클러스터·HDFS 장애는 미검증이다.
  Redis의 과거 fence 값으로의 복원은 현행 계약상 보장 범위 밖이다.
