# 222: 검토된 backend 수동 호환성 검사

네트워크 사전 검사 `02dbfd2`가 서버에서 통과한 뒤의 단계다. **미신뢰 MR Runner가 아니다.**
현재 `.gitlab-ci.yml`, 운영 Docker, backend 소스와 테스트 계약을 변경하지 않는다.
검토된 backend의 서버 수동 테스트 결과는 아래에 기록한다. Jira 222를 완료하거나 기존 Runner
태그를 전환하지 않는다. 현재 Jira 담당자·상태 및 열린 MR은 이번 수정에서 실시간 조회하지 않았다.

## 실행 범위와 제한

- 운영자가 검토한 전체 커밋 SHA와 그 커밋의 `backend` Git archive만 입력한다.
  SHA256은 전달 중 변경 확인이다. 스크립트가 커밋과 archive의 Git 관계나 코드의 안전성을
  자동 증명하지 않는다. 운영자가 실제 Git archive 명령 및 검토한 revision을 확인해야 한다.
- `.git`, 개인 `.env`, build/cache, 심볼릭 링크, 상위 경로 이동, 특수 파일, 중복 경로와
  과도한 크기를 거부한다. 테스트 홈 아래 새 UUID 폴더에만 풀며 운영 체크아웃은 건드리지 않는다.
- `feelm-ci-test` 전용 계정 및 Docker 소켓/데이터 경로를 외부와 namespace 내부에서 확인한다.
  실행 전 test Docker의 모든 컨테이너가 없어야 한다. 다른 테스트·Runner가 동시에 실행되면 안 된다.
- 새 HOME, Gradle 캐시, 빈 Docker 설정 폴더를 사용한다. 운영 인증정보·환경변수·기존
  `~/.gradle/init.d`·Testcontainers 사용자 설정을 전달하지 않는다. 실제 DB/JWT 비밀값을 넣지 않는다.
  테스트 자체의 H2 설정·테스트용 JWT 및 Testcontainers 서비스 연결을 그대로 사용한다.
- 공개 Gradle/Maven 의존성, `redis:8-alpine`, `pgvector/pgvector:0.8.6-pg17-bookworm`,
  Testcontainers 보조 이미지 다운로드가 발생할 수 있다. 새 저장소 로그인이나 운영 토큰은 사용하지 않는다.
  backend에 기록된 기존 이미지 태그를 유지하며 결과의 digest 확인은 서버 증빙 단계다.
- 이미 검증한 detached network 진입과 private DNS를 사용한다. `DOCKER_HOST` 및 Ryuk의
  소켓은 `/run/user/1001/docker.sock`, 테스트의 임시 포트 접속 주소는 내부 `127.0.0.1`이다.
- 외부 init script가 Java SPI 보조 코드를 컴파일해 이번 테스트 classpath에만 추가한다.
  GenericContainer 생성 시 공개 포트를 loopback으로 제한하고 privileged=false,
  메모리 768MiB/CPU 1개/PID 256 제한을 적용한다. 기존 테스트 source set에는 파일을 추가하지 않으므로
  Spotless의 검사 범위를 변경하지 않는다. network host/custom 모드는 거부한다.
- 이 SPI는 **보안 강제 경계가 아니다.** 검토된 테스트의 GenericContainer 생성만 조정하며,
  임의 MR 코드가 Docker API를 직접 호출하면 우회 가능하다. namespace 내부 mapped UID 0,
  테스트 소켓 접근 및 동일 사용자 파일 접근 권한을 가지므로 실제 미신뢰 MR용 실행 설계는 별도로 남는다.
- `clean test spotlessCheck --no-daemon` 전체를 실행한다. 테스트 제외나 Ryuk 비활성화는 하지 않는다.
  Gradle worker 2, 테스트 JVM 1개/heap 768MiB로 제한한다. 사용자 slice의 기존 CPU 200%,
  memory.high 5GiB/max 6GiB, pids.max 2048 이하를 확인하고 해당 slice 안에서만 시작한다.
- 최소 여유 공간 5GiB를 확인한다. 이는 실행 중 디스크 quota가 아니다. 실행 폴더·Gradle 캐시·
  이미지가 남으므로 반복 실행 전에 용량을 확인한다. 자원 제한과 nft는 아직 runtime 설정이다.
- 30분 timeout/중단 시 실행한 프로세스 그룹에 종료 신호를 보낸다. 비정상 종료로 분리된 자식
  프로세스나 Docker 자원이 남을 수 있으므로 사용자 scope 및 잔존 자원을 확인한다. 자동 prune은 없다.
- JUnit XML의 실제 건수와 실패/오류, PostgreSQL migration 4개 및 Redis AuthContractTest의
  실행·비생략·통과를 확인한다. Gradle 종료 0만으로 완료라고 하지 않는다.
  종료 후 자연 정리를 최대 30초 동안 재조회하며, 컨테이너가 남으면 실패 처리한다.
  개별 Docker 조회에도 30초 timeout이 있으므로 조회 지연만큼 총 소요 시간이 늘어날 수 있다.
  컨테이너를 강제로 삭제하지 않는다. 정리가 늦으면 먼저 잔존 상태를 확인하고
  전체 테스트를 즉시 재실행하지 않는다. 네트워크·볼륨·이미지 정리는 별도 검토한다.

## 서버 반영 후 준비 (운영자 ubuntu 계정)

아래 명령은 **이 스크립트가 포함된 커밋을 pull한 다음** 실행한다. GitLab/Jira 상태는 변경하지 않는다.
스크립트와 보조 파일은 root 소유로 복사하지만 테스트 실행 자체는 테스트 계정으로 수행한다.

```bash
cd ~/S15P21E106
git status --short --branch
git log -1 --oneline
sudo python3 infra/scripts/ci-network-guard.py status

sudo install -d -o root -g root -m 0755 /home/feelm-ci-test/pilot-222
sudo install -o root -g root -m 0644 \
  infra/scripts/ci-rootless-network-check.py infra/scripts/ci-rootless-backend-check.py \
  /home/feelm-ci-test/pilot-222/
sudo install -d -o root -g root -m 0755 \
  /home/feelm-ci-test/pilot-222/rootless-backend/META-INF/services
sudo install -o root -g root -m 0644 \
  infra/scripts/rootless-backend/LoopbackContainers.java \
  infra/scripts/rootless-backend/pilot.init.gradle \
  /home/feelm-ci-test/pilot-222/rootless-backend/
sudo install -o root -g root -m 0644 \
  infra/scripts/rootless-backend/META-INF/services/org.testcontainers.core.CreateContainerCmdModifier \
  /home/feelm-ci-test/pilot-222/rootless-backend/META-INF/services/
```

**현재 HEAD의 backend를 운영자가 검토한 경우에만** 다음 export를 실행한다.
커밋되지 않은 변경은 포함하지 않는다. 다른 커밋을 검증하려면 그 전체 SHA를 명시적으로 선택한다.

```bash
REVIEWED_REVISION=$(git rev-parse HEAD)
SOURCE_ARCHIVE=$(mktemp /tmp/ci222-backend-XXXXXXXX.tar)
git archive --format=tar --output="$SOURCE_ARCHIVE" "$REVIEWED_REVISION" backend
SOURCE_SHA256=$(sha256sum "$SOURCE_ARCHIVE" | cut -d ' ' -f1)
sudo install -o root -g root -m 0644 "$SOURCE_ARCHIVE" \
  "/home/feelm-ci-test/pilot-222/backend-$REVIEWED_REVISION.tar"
printf 'REVIEWED_REVISION=%s\nSOURCE_SHA256=%s\n' "$REVIEWED_REVISION" "$SOURCE_SHA256"
```

## 실행 (준비 결과 검토 후)

`REVIEWED_REVISION`, `SOURCE_SHA256`은 위 준비한 값을 사용한다. 새 셸이면 운영자가 다시 설정한다.
아래 실행은 의존성 다운로드와 테스트 컨테이너 생성을 시작한다. 외부 또는 운영 Secret을 넣지 않는다.
guard marker·UID·DNS/운영 IP 차단 대상이 맞는지 확인한 뒤 `--confirm-guard-active`를 사용한다.

```bash
sudo -u feelm-ci-test -H \
  env XDG_RUNTIME_DIR=/run/user/1001 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1001/bus \
  systemd-run --user --scope --unit="ci222-backend-$(date -u +%Y%m%d%H%M%S)" \
  python3 /home/feelm-ci-test/pilot-222/ci-rootless-backend-check.py run \
  --archive "/home/feelm-ci-test/pilot-222/backend-${REVIEWED_REVISION:?revision 필요}.tar" \
  --revision "$REVIEWED_REVISION" --sha256 "${SOURCE_SHA256:?sha256 필요}" \
  --confirm-reviewed-code --confirm-guard-active
echo "백엔드 검사 종료 코드: $?"
```

`BACKEND_RUN_DIR`는 재검토·JUnit HTML/XML 확인용으로 남는다. 로그 원문에는 테스트가 출력한
내용이 포함될 수 있으므로 사용자에게 공유할 때도 인증정보가 없는지 먼저 검토한다.

```bash
sudo python3 infra/scripts/ci-network-guard.py status
docker ps --format 'table {{.Names}}\t{{.Status}}'
sudo -u feelm-ci-test -H env XDG_RUNTIME_DIR=/run/user/1001 \
  docker --host unix:///run/user/1001/docker.sock ps -a
```

성공 기준: Gradle 성공, 필수 컨테이너 테스트 suite 통과, `PRIVATE_DNS_CLEANUP: PASS`,
잔존 테스트 컨테이너 없음, 종료 코드 0 및 운영 서비스 유지. 실패하면 보고서·오류를 확인하고
테스트 제외, privileged 허용, Ryuk 중단, 운영 소켓 전환, 방화벽 해제로 통과시키지 않는다.

## 로컬 검증 및 근거

- Python 모의 테스트: archive 경계, 환경 격리, 필수 테스트 보고서, cgroup 조건 및 기존 network 테스트.
- Java 21 + 로컬 Testcontainers 2.0.5/docker-java-api 3.7.1에서 보조 클래스 컴파일 확인.
- Gradle 9.7.1의 `compileCi222Policy --offline --no-daemon --init-script ...` 작업 성공을 확인했다.
  서버의 전체 backend 실행 결과는 아래와 같다. 모든 이미지 digest·생성 컨테이너의 실제
  포트/권한 검사와 미신뢰 MR 격리 승인은 별도로 남는다.
- [Testcontainers 환경·소켓·host 설정](https://java.testcontainers.org/features/configuration/)
- [전체 GenericContainer 생성 SPI](https://java.testcontainers.org/features/advanced_options/)

## 2026-09-04 서버 실행 증빙 및 보고서 판별 수정

운영자가 제공한 실행 로그와 JUnit XML 메타데이터 기준이다.

- 검토 revision: `9631afd9c616e047abbdd0ad7bf8192231f7f35c`
- archive SHA256: `b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1`
- 실행 scope: `ci222-backend-20260904125804.scope`
- 결과 경로: `/home/feelm-ci-test/backend-checks/ee737a8331104a619b767a40ae996129`
- `clean test spotlessCheck`: `BUILD SUCCESSFUL in 2m 24s`, 9 actionable tasks executed.
- 전체 82건, 실패 0 / 오류 0 / 생략 0. private DNS 정리 PASS, 운영 컨테이너 6개 실행 유지.
- 필수 클래스: AuthContractTest 11건, AccountMigrationTest 11건,
  ContentWatchMigrationTest 11건, TasteRecommendationPartyMigrationTest 8건, V1MigrationTest 9건.
  각 클래스 모두 실패·오류·생략 0이다.

원래 스크립트는 `testsuite.name`의 한글 DisplayName을 Java 클래스명과 비교해 필수 5개를
누락 판정했다. 수정본은 `testcase.classname`의 **정확한 전체 패키지명**으로 판별한다.
여러 XML에 걸쳐 같은 클래스의 실패·생략이 있으면 통과시키지 않으며, 실제 testcase 수와
보고된 tests 수가 다르거나 testcase가 없으면 통과시키지 않는다.

원래 종료 코드는 **2**로 보존한다. 종료 직후 잔존 ID `731a516b59a0`가 관측됐으나 이후
운영자 inspect에서 `no such object`로 확인됐다. 해당 컨테이너 종류는 확인하지 못했으므로
Ryuk이라고 단정하지 않는다. 수정본은 자연 정리 대기를 추가하며, 전체 잔존 여부는 별도 조회한다.

### 전체 테스트 재실행 없이 저장 XML 재검사

수정 커밋을 pull한 뒤 아래를 실행한다. `report`는 XML만 읽으며 Gradle, Docker, namespace,
방화벽을 실행·변경하지 않는다. 기존 실행의 종료 코드·환경 정리·보안 승인을 대체하지 않는다.

```bash
sudo install -o root -g root -m 0644 \
  infra/scripts/ci-rootless-backend-check.py /home/feelm-ci-test/pilot-222/
sudo -u feelm-ci-test -H \
  python3 /home/feelm-ci-test/pilot-222/ci-rootless-backend-check.py report \
  --run-dir /home/feelm-ci-test/backend-checks/ee737a8331104a619b767a40ae996129
echo "보고서 재검사 종료 코드: $?"
sudo -u feelm-ci-test -H \
  docker --host unix:///run/user/1001/docker.sock ps -a \
  --format 'table {{.Names}}\t{{.Status}}'
```

기대: 82건/실패·오류·생략 0, `REQUIRED_CONTAINER_SUITES_NOT_PASSED: []`,
`REPORT_STRUCTURE_VALID: True`, `REPORT_RECHECK_EXIT=0`, 테스트 Docker 목록에 컨테이너 없음.
운영자가 `ea3556c`의 서버 재검사에서 위 기대값 전체와 빈 컨테이너 목록을 확인했다.
원래 실행의 종료 코드 2는 보존한다. 자원 제한은 `/etc` 영구 파일 저장까지 확인했으며,
guard 영속화, IPv6 및 우회 경계 검토,
실제 MR Runner 설계·등록·전환 검증은 남아 있다.
