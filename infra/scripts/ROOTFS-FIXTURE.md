# 222 로컬 rootfs 고정 검증

사용자 승인 범위: 서버에 설치하지 않고 로컬에서 작업 전용 파일시스템·프로세스 격리 실험.
`ci-rootfs-fixture.py`는 WSL 커널에서만 run을 허용한다. 서버용 실행기가 아니다.
외부 패키지 설치, 계정 생성, Docker 실행, 방화벽/CI/Runner 변경을 하지 않는다.

## 구성

로컬 root 관리자가 새 `/tmp/ci222-rootfs-*`에 기존 static BusyBox만 복사한다.
검증 파일, 가짜 호스트 파일과 Unix socket은 모두 이 임시 폴더에 만든다. 실제 비밀을 읽지 않는다.
고정 child는 outer UID/GID 65534로 내린 뒤 user/map-root, PID, mount, net, IPC, UTS
namespace를 만든다. proc는 새 PID namespace용으로 mount하고 rootfs에 chroot한다.
작업 실행 전에 capability effective/permitted/bounding/ambient를 모두 없애고
no_new_privs를 설정한다. 상속 FD는 0~2만 유지하고 작업 코드는 고정 shell 검사뿐이다.
chroot 단독 검사가 아니라 user/PID/mount/net·권한 축소를 함께 시험한다.

호스트 파일시스템을 bind하지 않는다. rootfs는 호스트 root 소유이고 mapped UID65534가
수정할 수 없다. 새 프로세스가 볼 수 있는 proc/root, host canary/socket, host mount 경로의
부재와 namespace 차이, capability0, NNP1, loopback-only를 확인한다.
mount/파일쓰기 실패는 권한 거부 메시지까지 확인한다. 도구 누락 등 임의 실패를 통과로 취급하지 않는다.

## 실행 및 결과

기본 plan은 변경이 없다. root로 아래 명령을 **로컬 WSL에서만** 실행한다.

```bash
python3 -I -B infra/scripts/ci-rootfs-fixture.py run --confirm-local-fixture
```

첫 실험은 /dev/null 부재로 음성 검사가 실제 실행되지 않는 오류가 발견되어 통과 증빙에서
제외했다(`/tmp/ci222-rootfs-q3cdz753`). 코드 수정 후 재실행 결과:

```text
LOCAL_EVIDENCE_DIR: /tmp/ci222-rootfs-299awiie
ROOTFS_FIXED_PROBE: PASS
HOST_PATHS_AND_CANARY: ABSENT
CAPABILITIES: ZERO; NO_NEW_PRIVS: 1
NOT_DOCKER_OR_MR_APPROVAL
LOCAL_ROOTFS_EXIT=0
```

검사 출력/result.json/rootfs는 임시 폴더에 보존한다. 성공 시 unshare 프로세스가 끝나며
namespace mount가 소멸한다. listener도 close한다. 무단 재귀 삭제나 전역 unmount를 하지 않는다.
timeout/SIGKILL 경로의 전체 프로세스 정리는 이 시험에서 검증하지 않았다.

## 아직 증명하지 않은 사항

- Docker daemon을 이 rootfs 안에서 기동하는 데 필요한 파일·권한·cgroup 지원.
- Docker API의 bind mount, privileged/host PID 요청, 장치 접근 및 namespace 우회 공격.
- Java/Gradle/Testcontainers 전체82개 테스트를 이 새 경계에서 실행하는 호환성.
- host UID65534를 사용하는 다른 프로세스와의 격리. 실제 배치는 재사용 nobody가 아닌
  작업 전용 UID 설계가 필요하다. 이 fixture는 고정 코드만 실행하며 일반 MR 입력을 받지 않는다.
- seccomp/LSM, 커널 취약점, 지속성·cancel·timeout·scope 정리·보고서 수집 공격.
- 서버 kernel/AppArmor 차이, 실제 Runner lifecycle, protected develop 빌드·배포 유지.

다음 구현의 판단 기준은 **daemon에 필요한 권한을 추가해도 호스트 경계가 유지되는가**다.
이번 cap0 결과를 Docker에 그대로 적용 가능한 설정으로 배포하지 않는다.
rootfs·계정·executor 운영 도입은 이번 로컬 실험 승인과 별개다.

## Docker 검증 착수 시 로컬 전제조건 확인

후속 사용자 요청으로 WSL Ubuntu-22.04를 조회했다. dockerd/containerd/runc/rootlesskit,
newuidmap/newgidmap/systemd-nspawn 실행 파일은 PATH에 없고 `/usr/bin`의 Docker 엔진 파일,
`/var/run/docker.sock`도 없다. docker-ce/docker.io/containerd/containerd.io/runc/uidmap의
설치 정보 출력도 없었다. 발견된 docker 명령은 Windows Docker Desktop 배포 경로뿐이며
WSL 엔진이 있다는 증거가 아니다. `/mnt/wsl`에는 resolv.conf만 있었다.

따라서 Docker 기동/API 우회/종료 정리 검사는 아직 실행하지 못했다. 호스트 Docker Desktop을
시작하거나 통합 설정을 바꾸지 않았다. 검증 전용 Linux Docker 도구 준비는 새 의존성 추가이므로
사용자 승인 후 별도로 진행한다. 기존 고정 rootfs 통과를 Docker 검증으로 대체하지 않는다.

## 승인 후 로컬 Docker 도구 준비

사용자 승인으로 공식 static Docker 29.7.2 배포본을
`/tmp/ci222-docker-tools-5kfxpj66`에만 풀었다. 전역 패키지/서비스는 설치하지 않았다.
도구 버전: dockerd 29.7.2(6a43e3d), containerd 2.3.3, runc 1.4.3.
다운로드 SHA256: `803d433f226db4776e1768fd319fc6c6e4935a456acf84fcc0080818b854bc8f`.
이 해시는 다운로드 동일성 기록이며 독립된 서명 검증을 했다는 의미가 아니다.
출처: https://download.docker.com/linux/static/stable/x86_64/docker-29.7.2.tgz

첫 shell 호출의 변수 전달 오류로 `/docker.tgz`에 중복 다운로드됐지만 해시 일치를 확인하고
그 파일 하나만 삭제했다. 정상 임시 폴더의 원본과 추출 도구는 유지한다.
`ci-local-docker-tools.py`는 WSL 전용 준비 스크립트이며 실행 시 새 임시 폴더에 다운로드한다.
도구의 --version만 실행했고 daemon 기동/API 우회/컨테이너 정리 검증은 아직 하지 않았다.

## 실제 Docker 기동 시도 — 미통과 (2026-09-05 KST)

이후 `ci-local-docker-tools.py --probe-rootfs`로 준비와 고정 기동 시도를 한 호출로 묶었다.
이전 /tmp 도구 경로가 후속 호출에서 없어졌고, 공식 tar의 빌드 소유 UID1001도 확인했다.
추출 시 새 임시 디렉터리 안에서만 root 소유/755로 정규화하여 소유 검사 자체는 유지했다.
다운로드 SHA는 위와 같으며 새 도구 경로는 `/tmp/ci222-docker-tools-hdpvh64f`였다.

Docker 모드는 기존 cap0/NNP/user/PID/mount/net 검사를 유지하고, 새 rootfs의 scratch/run/tmp만
mapped UID65534가 쓰도록 한다. Docker static 도구를 복사하고 최대12초 기동을 시도한다.
이 동작은 성공 판정 실행기가 아니라 bootstrap 오류 수집용이다. API 성공을 검사하지 않으므로
daemon이 계속 살아있어도 timeout 뒤 미통과로 처리한다. Docker 컨테이너는 만들지 않는다.

`/tmp/ci222-rootfs-o92uqbbo` 결과: 기초 rootfs 검사 PASS, Docker29.7.2 실행,
managed containerd 기동까지 진행한 뒤 network controller 초기화 실패.

```text
failed to create port driver client: $ROOTLESSKIT_STATE_DIR needs to be set
DAEMON_ATTEMPT_EXIT=1
DOCKER_COMPATIBILITY: NOT_PASSED; no API/container success established
LOCAL_ROOTFS_EXIT=2
```

최소 rootfs에 실제 RootlessKit 제어 프로세스와 상태 socket 구성이 없다는 bootstrap 문제다.
환경변수 문자열만 채우면 해결된다고 가정하지 않으며, cap0가 직접 원인이라는 결론도 내리지 않는다.
후속 로컬 ps 조회에서 dockerd/containerd/rootlesskit/runc 프로세스 출력은 없었다.
이는 이번 실패 후 프로세스 조회이며 정상 컨테이너 cancel/timeout 정리 검증의 대체가 아니다.
Docker API의 host bind/namespace 우회, 내부 컨테이너 실행, full backend 검사는 아직 미실행이다.
다음은 RootlessKit과 필요한 권한을 포함한 daemon 관리 영역 설계이며, 기존 cap0 작업 영역에
무작정 권한을 추가하거나 서버에 이 fixture를 적용하지 않는다.

### 추가 로컬 manager 실험 — 미완료

`ci-local-manager-check.py`는 서버 설치기가 아닌 별도 WSL 실험이다.
기본 실행은 계획만 출력하며 실행하려면 `--confirm-local-experiment`가 필요하다.
임시 폴더에 systemd-container/systemd 패키지를 추출하고 사용자 namespace와
private network 안에서 Docker 29.7.2 관리 프로세스를 시험했다. 기존 RootlessKit
구성과 동일한 방식이라고 간주하지 않는다. 전역 패키지 설치나 서버 변경은 없다.

최종 실행 `/tmp/ci222-manager-e44wblk2` 결과:

```text
MANAGER_DOCKER_READY
unable to apply cgroup configuration: cannot enter cgroupv2
"/sys/fs/cgroup/docker" with domain controllers -- it is in an invalid state
MANAGER_CHECK_EXIT=125
```

전용 Delegate=yes scope에서도 동일했다. 따라서 daemon 기동만 확인했으며,
컨테이너 자원 제한과 후속 host-bind 차단 검사는 도달하지 못했다.
1번 완료나 Runner 적용 승인으로 해석하면 안 된다. 다음 조사 대상은 격리
manager 내부의 cgroup 계층/위임 구성이다. 이를 우회하려고 자원 제한을 제거하지 않는다.
129개 단위 테스트 실행은 128개 통과/1개 건너뜀이며, 신규 manager 실험의
실행 성공을 의미하지 않는다. 임시 파일은 남기며 재부팅 후 보존을 보장하지 않는다.

### cgroup 위임 수정과 다음 차단점 (2026-09-05)

읽기 전용 진단 `/tmp/ci222-manager-o168jxuo`에서 임시 scope의 `payload`는
호스트와 다른 cgroup namespace이고 UID 0이 호스트 UID 201588736에 매핑됨을 확인했다.
`supervisor`만 호스트 namespace에 있었다. 격리 안의 cgroup 루트에는 PID 1이 남아 있었고
`cgroup.subtree_control`은 비어 있었다. 해당 루트는 호스트 전체 루트가 아니라
`/system.slice/ci222-local-ci222-manager-o168jxuo.scope/payload`에 대응했다.

수정은 namespace·비-root UID 매핑·domain 상태 확인 후 관리 프로세스를 `manager`
하위 그룹으로 옮겨 부모를 비우고 CPU/memory/pids controller를 활성화한다.
이는 cgroup v2의 [No Internal Process Constraint](https://cdn.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html#no-internal-process-constraint)를 따르는 구조다.
호스트 전체 cgroup bind나 제한 제거를 사용하지 않았다.

재실행 `/tmp/ci222-manager-9rk30ycq`:

```text
CGROUP_MANAGER_LEAF_READY
MANAGER_DOCKER_READY
unable to join session keyring: unable to create session key: operation not permitted
MANAGER_CHECK_EXIT=125
```

기존 cgroup 오류는 재현되지 않았고 OCI 초기화의 키링 단계로 진행했다.
실제 workload 실행 및 자원 제한값 검증은 아직 통과하지 않았으므로 완료가 아니다.
키링 호출의 외부 보안 필터 허용 범위 변경은 자동 적용하지 않았다.
호스트 경로/Docker API 검사도 이 오류 이후에 있으므로 미실행이다.
135개 단위 테스트 중 134개 통과, 1개 환경 조건으로 건너뜀. 이는 동적 격리 승인이 아니다.
진단 전용 옵션과 종료 시 해당 임시 scope 정리, 경로 검사 fail-fast를 추가했다.

### 승인된 keyctl 예외와 로컬 고정 검사 통과 (2026-09-05)

사용자가 로컬 실험의 최소 키링 호출 허용을 승인했다. nspawn 외부 필터에
`--system-call-filter=keyctl` 하나만 추가했다. `add_key`, `request_key`, 전체
seccomp 해제, 호스트 cgroup bind는 추가하지 않았다. 서버 보안 정책은 바꾸지 않았다.
이 예외의 운영 채택이나 임의 코드에 대한 안전성을 승인한 것은 아니다.

최종 실행 증빙 경로: `/tmp/ci222-manager-l1_4nxzl/result.json` (임시 보존).

```text
CGROUP_MANAGER_LEAF_READY
MANAGER_DOCKER_READY
MANAGER_CONTAINER_EXECUTED
HOST_FILE_SOCKET_BIND_REJECTED
MANAGER_PATH_PROBE_PASSED
PRIVILEGED_HOST_NAMESPACE_PROBE_PASSED
MANAGER_PROBE_END
MANAGER_CHECK_EXIT=0
LOCAL_SCOPE_STOPPED; HOST_CANARY_UNCHANGED
```

- 컨테이너 안에서 memory.max=67108864, cpu.max="50000 100000", pids.max=32를
  정확히 비교했다. 외부 임시 scope에는 MemoryMax=2G, CPUQuota=200%, TasksMax=512를 설정했다.
- 호스트에 실제 생성한 합성 파일·Unix socket 각각의 bind 요청은 종료 125와
  해당 경로의 `bind source path does not exist` 오류를 모두 확인했다. 임의 실패를 성공으로 세지 않는다.
- Docker daemon의 `/`를 bind한 컨테이너에서 합성 호스트 경로, 사용자 bus 경로와
  운영 Docker socket 경로가 보이지 않는지 확인했다. 실제 Secret 원문을 읽지 않는다.
- `--privileged --pid host --network host --cgroupns host` 고정 요청에서도 PID/net/cgroup
  namespace는 실제 호스트와 다르고 UID는 비-root 호스트 UID에 매핑됐다.
  `/proc/1/root`를 통한 합성 파일 접근도 불가했다. IPv4/IPv6 기본 경로 부재를 확인했다.
- 검증 중 BusyBox `ip route show default`가 docker0 연결 경로까지 반환했고, 최소 이미지에
  `/tmp`가 없어 추가 검사가 실패했다. 전체 경로를 읽어 기본 경로를 직접 판별하고
  임시 파일 대신 here-document를 사용하도록 수정했다. 두 실패 실행은 성공 증빙에서 제외했다.
- 컨테이너 목록이 비어 있음을 확인하고 임시 scope를 중지했으며 inactive/unknown을 확인했다.
  합성 호스트 파일의 내용은 그대로였다. 강제 종료/timeout/관리자 장애의 전체 검증은 별개다.

단위 테스트 138개 중 137개 통과, 1개 환경 조건으로 건너뜀. diff 공백 검사 통과.
**로컬 Docker 실행 및 고정 접근 경계 검사는 통과했다. 일반 MR 격리 완료는 아니다.**
이 새로운 manager 방식에서 backend 82개 재실행, 내부 Testcontainers 네트워크/포트 호환성,
키링·FD·namespace 진입·수명주기 공격 검사 및 실제 Runner 적용은 아직 남는다.
기존 서비스 서버의 오프라인 backend 성공을 이 새 manager 방식의 성공으로 옮겨 적지 않는다.
파일은 로컬 수정 상태이며 커밋/푸시는 하지 않았다.
