# S15P21E106-222 서버 manager 파일럿

상태: **고정 소스 서버 파일럿의 probe·백엔드 82개/spotless·취소/timeout fixture 통과.
일반 MR 실행기·Runner 전환·S15P21E106-222 완료 승인은 아니다.**

아래는 최초 고정 파일럿 기록이다. 후속 입력/보고서 경계와 새 수명주기 경로 검증은
[작업 실행기 핵심 경로](JOB-EXECUTOR-CORE.md)에 별도로 기록한다.

## 승인 범위와 운영 보호

2026-09-05 KST 운영자 요청으로 서비스 서버에서 별도 파일럿을 수행했다.
운영 저장소의 checkout/pull은 하지 않았다. 랜딩의 bind mount와 운영 Docker,
기존 GitLab Runner, CI 설정, UFW/nftables, 전역 systemd 및 계정 설정은 변경하지 않았다.
기존 테스트용 캐시·저장 이미지 중 manifest에 기록된 파일만 읽어 복사했다.

새 디렉터리는 `/var/tmp/ci222-server-*`, root 소유 700이며 기존 파일을 덮어쓰지 않는다.
Python 스크립트 및 고정 backend 소스만 전송하고 각각 해시를 검증한다.
`systemd-container`는 현재 서버 systemd와 같은 버전의 deb를 **다운로드·추출만** 한다.
`apt install`, 서비스 enable 및 전역 런타임 교체는 하지 않는다.
다운로드 시 root 전용 폴더 때문에 `_apt` 접근 불가 경고가 발생했지만 설치는 실행하지 않았다.

## 코드

- `ci-server-pilot-stage.py`: 선택한 코드 및 검토 revision의 `git archive`만 새 서버 폴더로 전송.
- `ci-server-manager-pilot.py`: 입력 준비, 별도 실행 준비 서비스, 실제 nspawn 실행 서비스,
  읽기 전용 진단과 결과 수집. 기존 로컬 WSL 실행기의 환경 제한은 그대로 유지한다.
- `ci-server-sysfs-bootstrap.py`: 고정 대기 프로세스와 별도 namespace를 검증한 후,
  호스트 경로에 붙이지 않은 readonly sysfs mount를 파일럿 안에만 연결한다.
- `test_ci_server_manager_pilot.py`: 경로·업로드·기본 plan·자원 설정·정리 대상에 대한 회귀 검사.
- `test_ci_server_sysfs_bootstrap.py`: UID 매핑·고정 대상·readonly/private mount·시작 순서 검사.

최종 로컬 `test_ci_*.py` 178개 중 177개 통과, 1개 환경 조건 skip.
이 단위 테스트 성공은 실제 서버 테스트나 일반 MR 보안 승인을 대체하지 않는다.

실행 준비와 실제 실행 서비스는 순차적이며 각각 CPUQuota=200%, MemoryHigh=5G,
MemoryMax=6G, TasksMax=2048, RuntimeMaxSec=2400, KillMode=control-group이다.
사용자 MR 소스/임의 실행 명령을 받지 않는다. 고정 source만 실행 가능한 실험 도구다.
프로세스 시간/메모리 제한과 별개로 디스크 quota를 제공하지 않으며, 실행 전 여유 공간을 검사한다.

`DelegateSubgroup=controller`로 unit 최상위 cgroup을 비우고, 그 **검증된 임시 unit에만**
cpu/memory/pids controller를 전달한다. 실행 서비스의 Python은 nspawn으로 exec 교체되어
컨테이너의 cgroup 상위에 별도 Python 프로세스를 남기지 않는다.
호스트 root/service 공통 cgroup 변경이나 호스트 cgroup bind는 하지 않는다.
이 방식의 배경은 [systemd 255 자원 위임 문서](https://github.com/systemd/systemd/blob/v255/man/systemd.resource-control.xml)의
Delegate/DelegateSubgroup 설명과 실제 서버 읽기 전용 진단이다.

### sysfs 준비 변경과 제약

운영자 승인 후, 고정 BusyBox PID 1을 시작 대기 상태에 둔다. 별도 root helper는 root 소유
stage/run, 해당 임시 service의 정확한 payload cgroup, 실행 파일 inode, UID/GID 매핑,
호스트와 다른 user/mount/network/PID/cgroup namespace 및 rootfs inode를 확인한다.
대기 프로세스의 namespace FD를 고정하고 변경 여부를 다시 확인하며, MR 코드는 받지 않는다.

helper는 파일럿 network namespace로 먼저 진입해 sysfs context를 만든다.
`fsopen/fsconfig/fsmount`로 **경로에 아직 붙이지 않은** mount를 만든 뒤,
검증된 mount namespace와 rootfs 안에서만 `move_mount`한다. 목적지는 `/run/ci222-sysfs`,
속성은 readonly/nosuid/nodev/noexec/private이며 `/sys`나 `/proc`의 호스트 bind는 없다.
helper가 FD를 닫고 종료한 것을 확인한 후에만 operator가 시작 표식을 만들어 Docker를 실행한다.
이는 신뢰된 고정 파일럿용 root bootstrap이며 임의 MR namespace에 적용할 범용 API가 아니다.

기존 mount API로 target namespace에 들어간 뒤 새 sysfs를 만드는 시도는 EPERM이었다.
[Linux 6.17 mount 검사](https://github.com/torvalds/linux/blob/v6.17/fs/namespace.c)의
`mount_too_revealing`은 호출자 UID만이 아니라 mount namespace 소유자와 기존 노출을 검사한다.
관찰 결과와 일치하는 설명이며, 실행 커널의 해당 함수까지 추적한 것은 아니다.
새 방식은 [Linux mount API](https://man7.org/linux/man-pages/man2/fsopen.2.html)를 사용한다.

**full sysfs는 하위 디렉터리만 노출할 때보다 커널·장치 메타데이터를 더 보여준다.**
새 네트워크 namespace 및 readonly mount가 모든 커널 정보를 비밀로 만드는 것은 아니다.
동일 커널 취약점 위험도 남는다. 일반 MR 적용에는 이 노출과 bootstrap 신뢰 경계의 추가 검토가 필요하다.
일반 컨테이너와 별개로 기본 `--privileged`의 writable sysfs 요청은 거부되어야 한다.
이 실패만으로 격리를 승인하지 않고, explicit readonly sysfs bind를 사용한 고정 privileged
컨테이너를 실제 실행하여 rw remount 거부 및 host PID/network/cgroup·파일/socket 접근을 검사한다.

## 실제 서버 결과

- Ubuntu 24.04.4, 커널 6.17.0-1019-aws, systemd/nspawn 255.4-1ubuntu8.17.
- Docker 29.7.2 및 Temurin JDK 21.0.12.1 배포본 해시 검증 완료.
- source revision `9631afd9c616e047abbdd0ad7bf8192231f7f35c`, archive
  `b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1`.
- 캐시 manifest·선택된 캐시·이미지 archive 검사 완료. 이 기록은 실행 성공과 별개다.

| 단계 | 근거와 판정 |
| --- | --- |
| 최초 probe | 서비스 상위에 Python이 남아 내부 cgroup.controllers가 비었고 컨트롤러 설정 실패 |
| 읽기 전용 진단 | root, payload, supervisor의 cgroup 구성으로 원인 확인 |
| 자원 제어 보완 후 probe | 내부 `cpu memory pids`, `CGROUP_MANAGER_LEAF_READY`, `MANAGER_DOCKER_READY` 확인 |
| sysfs 보완 전 컨테이너 생성 | runc의 `/sys` mount에서 EPERM, Docker 125. 실패 기록 유지 |
| sysfs 진단 | 호스트와 다른 UID 매핑 `0 -> 201588736`, 내부 /sys는 readonly tmpfs와 sysfs 하위 bind들. AppArmor profile은 unconfined |
| sysfs 보완 후 probe | 작은 컨테이너 실제 실행, memory 64MiB / CPU 0.5 / PID 32 검사 성공. 호스트 합성 파일/socket bind 거부 |
| privileged 경계 fixture | 기본 writable sysfs 요청 125 거부. readonly sysfs를 명시한 실제 privileged 실행에서 rw remount 거부, host PID/net/cgroup 구분과 호스트 파일/socket 미노출 확인 |
| 이번 backend 실행 | **새 실행** Gradle 0, 1분 35초, test 및 spotlessCheck 성공. 82개, 실패/오류/skip 0, 필수 5개 container suite 전부 성공. 컨테이너 잔존 0 |
| 자원 사용 | backend 실행 서비스 2분 3.744초, memory peak 1.9G, swap peak 0. CPU/Memory/PID 한도 유지 |
| 취소 fixture | 별도 고정 sleep 컨테이너 ready 확인 후 서비스 중지, 전체 cgroup 제거 확인 |
| timeout fixture | 별도 고정 sleep 컨테이너 ready 후 coordinator 1초 대기 timeout을 발생시키고 서비스 중지, 전체 cgroup 제거 확인 |
| 종료 확인 | `ci222-server-*` cgroup 잔존 없음. 호스트 sysfs mount는 기존 `/sys`만 확인. 실패 이력과 파일은 보존 |
| 운영 확인 | 랜딩 HTTPS 200, 기존 Runner active, 운영 컨테이너 6개 실행 유지 |

sysfs mount 거부와 유사한 현상은 [systemd upstream issue #27994](https://github.com/systemd/systemd/issues/27994)에
보고되어 있다. 이번 서버의 정확한 커널 거부 지점까지 추적한 것은 아니므로 동일 결함으로 단정하지 않는다.
최근 커널 로그에 해당 AppArmor denial이 없고 payload profile도 unconfined이므로,
AppArmor를 원인으로 단정하거나 전역 해제하는 조치는 하지 않았다.

준비 입력: `/var/tmp/ci222-server-6gqql4ju`.
최종 실행 코드: `/var/tmp/ci222-server-cdmkn7q5`.
전송 archive SHA256: `3680b346a479c579ce2785be83d87b7a2ee0c5ebce0b6653a1b7ecdfdc153f64`.
자원 제어 보완 후 실패 증빙: `/var/tmp/ci222-server-4mjqk1s7/run-ez3m1cl1`.
sysfs 변경 전 읽기 전용 진단: `/var/tmp/ci222-server-ut0j8hjx/run-sosynw2y`.

최종 코드 stage 아래의 성공 증빙:

| 검사 | run 디렉터리 | 실행 service |
| --- | --- | --- |
| probe | `run-y0f050us` | `ci222-server-c1ae39aa681c.service` |
| backend | `run-gtays8t5` | `ci222-server-23776f2aa64a.service` |
| cancel | `run-hpnsc4t2` | `ci222-server-b0fbfb449158.service` |
| timeout | `run-umjahhr9` | `ci222-server-6394d94b2f6d.service` |

각 성공 run의 `execution.log`, `sysfs-bootstrap.log`, `result.json`, `summary.json`을 보존한다.
backend의 `evidence/`에 새 실행 결과와 테스트 XML을 보존했다.
초기 직접 서비스 시도는 해당 stage의 run 디렉터리 및 journal에 보존했다.

현재 10개 stage는 합계 약 5.7 GiB이며 삭제하지 않았다. 마지막 확인에서 root 여유 287 GiB(사용 8%).
준비 입력 약 1.1 GiB를 재사용하고 코드 수정 때 캐시·JDK·이미지를 다시 복사하지 않았다.
증빙 보존 후 정리할 때는 정확한 stage 목록과 활성 서비스/마운트 유무부터 확인해야 한다.

## 사용과 중단 기준

기본 실행은 계획만 표시한다. `--confirm-server-stage`는 실제 서버에 파일을 생성한다.

```bash
python3 infra/scripts/ci-server-pilot-stage.py
python3 infra/scripts/ci-server-manager-pilot.py plan
```

서버는 전송된 stage의 `scripts/ci-server-manager-pilot.py`로 실행한다.
`prepare --confirm-server-pilot`은 새 stage에만 입력을 만든다.
`diagnose|probe|backend|cancel|timeout --prepared-base <이미 준비된 stage> --confirm-server-pilot`
호출은 내부적으로 제한된 transient service를 만든다. 운영 저장소에서 Git을 변경할 필요 없다.

`internal-build`/`internal-launch`는 생성된 서비스에서만 호출되며 임의 셸용 진입점이 아니다.
취소/timeout 결과는 실제 실행한 고정 sleep 컨테이너 fixture에 한정한다.
실행 중 Gradle 취소, coordinator 강제 종료, 실제 40분 RuntimeMax 만료,
부팅 복원, 동시 작업 UID 분리, 악성 artifact/FD 전달 및 실제 GitLab job lifecycle은 미검증이다.
외부 통신 판정은 별도 network namespace와 기본 경로 부재 확인에 근거하며,
이번 probe가 모든 IPv4/IPv6 목적지의 연결을 직접 시도한 것은 아니다.

다음은 일반 MR 실행 경계와 작업별 수명주기 구현/검증이다. 전역 AppArmor/seccomp 해제,
host user namespace 사용, 호스트 `/sys`·운영 Docker socket 연결, 자원 검사 제거,
Testcontainers 테스트 제외로 우회하지 않는다. 런타임/보안 경계를 바꾸는 적용은
구체적 변경안과 위험을 운영자에게 알리고 합의한 뒤 진행한다.
서버 전체 업그레이드·재부팅·Runner 전환·Jira 완료 처리·커밋/푸시는 이번 실행에 포함하지 않았다.
