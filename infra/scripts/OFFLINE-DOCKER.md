# 222 별도 오프라인 Docker — 검토된 파일럿만

사용자는 별도 daemon/데이터/소켓과 외부 통신 차단 구성 작업을 승인했다.
서버 출력: 루트 여유 294G, 기존 rootless 이미지 982.4MB, 컨테이너/볼륨/빌드 캐시 0.
5GiB는 초기 여유 예산이지 최대 사용량 보장이 아니다. 이미지 export/import와 캐시 복사는
중복 저장을 만들며 반복 실행은 누적된다. 자동 prune/삭제는 하지 않는다.

## 구조

root 소유 system service의 `PrivateNetwork=yes`가 바깥 네트워크를 loopback만으로 만든다.
그 안에서 UID 1001의 별도 rootless Docker를 시작한다. slirp4netns가 있더라도 바깥쪽에
외부 경로가 없도록 한다. 시작 helper는 인터페이스와 IPv4/IPv6 route를 검사한다.
RootlessKit detach 모드는 이 별도 daemon에서만 끈다. 기존 daemon 설정은 유지한다.

- 데이터: `/home/feelm-ci-test/offline-docker-222/data/docker`
- 소켓: `/run/feelm-ci222-offline/docker.sock`
- RootlessKit 상태: `/run/feelm-ci222-offline/rootlesskit`
- 기존 두 Docker의 데이터/소켓/서비스는 수정하지 않는다.
- 최대 1시간 실행, 재시작/부팅 자동 시작 없음, 종료 시 해당 service cgroup만 정지한다.
- 기존 user-1001.slice의 합산 자원 제한 안에 둔다. 테스트는 동시 실행하지 않는다.

이는 고정 검토 코드의 오프라인 호환성 실험이다. 같은 UID의 기존 rootless Unix socket,
파일시스템, 사용자 bus, 공유 커널 접근까지 차단하지 않는다. 일반 MR 연결 금지.
네트워크 helper뿐 아니라 daemon과 컨테이너 각각의 실제 경로 검증이 필요하다.

## 적용 순서 (아직 서버 미실행)

1. 서버 설치본의 rootless script가 DETACH_NETNS 환경변수를 처리하는지 확인한다.
2. 새 데이터 디렉터리와 unit 경로가 없는지 확인한다. 기존 파일이 있으면 덮어쓰지 않는다.
3. 운영자가 launcher/config를 `/usr/local/lib/feelm-ci222`에 root:root 644로 설치한다.
   비밀 없는 offline-daemon.json은 여기 둔다. 기존 root 전용 `/etc/feelm-ci222` 권한은 풀지 않는다.
   새 데이터 상위 디렉터리는 feelm-ci-test 소유 700, unit은 `/etc/systemd/system`에 root:root 644.
4. systemd-analyze verify, daemon-reload 후 새 unit만 수동 시작한다. enable하지 않는다.
5. Type=simple의 active만으로 Docker ready를 판정하지 않는다. 전용 소켓 info에서 rootless,
   전용 데이터 경로, 서버 버전을 확인한다. 실패 시 기존 Docker로 fallback하지 않는다.
6. 운영자 권한으로 unit MainPID와 자식의 namespace, 인터페이스, route를 확인한다.
   Docker detached namespace 탐색은 기존 daemon PID를 자동 선택하는 helper를 재사용하지 않는다.
7. 준비 이미지 export/import는 아직 미구현이다. digest 검증 후 파일로 옮기고 image ID/tag를
   재확인하는 단계가 필요하다. pull로 보충하지 않는다. 이미지가 준비되기 전 backend 실행 금지.
8. 컨테이너 내부 통신/loopback mapped port, IPv4/IPv6 외부 경로 차단, daemon pull 실패와
   호스트 운영 서비스 정상 상태를 검증한다. 그 뒤 full offline backend 테스트를 연결한다.

서버 시작 전 확인할 명령:

```bash
grep -n 'DOCKERD_ROOTLESS_ROOTLESSKIT_DETACH_NETNS' /usr/bin/dockerd-rootless.sh
command -v ip slirp4netns newuidmap newgidmap
sudo ls -ld /home/feelm-ci-test/offline-docker-222 \
  /run/feelm-ci222-offline \
  /etc/systemd/system/feelm-ci222-offline-docker.service
```

새 경로 3개가 없다는 오류는 최초 설치 전 예상 결과다. 설치 명령은 이 결과 확인 후 제공한다.
실험 중단은 `sudo systemctl stop feelm-ci222-offline-docker.service`만 사용한다.
데이터는 남겨두며 기존 rootless/운영 Docker를 중단하거나 nft ruleset을 삭제하지 않는다.
실제 cgroup delegation, AppArmor, 시작 호환성은 서버에서 확인해야 한다.

## 근거와 검증 범위

- [systemd PrivateNetwork](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html#PrivateNetwork=)
- [Docker 다중 daemon 설정](https://docs.docker.com/reference/cli/dockerd/#run-multiple-daemons)
- [Docker rootless launcher](https://raw.githubusercontent.com/moby/moby/master/contrib/dockerd-rootless.sh)

로컬 단위 검사는 경로/환경 분리, IPv4/IPv6 route 거부, unit 수명/네트워크 설정을 검증한다.
실제 Docker 기동·이미지 이전·backend 테스트는 아직 실행하지 않았다.

## 후속 서버 확인 및 사용자 bus 수정

운영자 출력에서 별도 rootless daemon 기동, 전용 데이터 경로, systemd cgroup v2,
호스트와 다른 바깥 netns의 loopback-only/route 없음, Python 이미지 파일 해시와 가져온
image ID 일치를 확인했다. 이후 자원 제한을 지정한 컨테이너 실행은 scope 생성의
`Interactive authentication required`로 실패했고 Python 검사는 실행되지 않았다(exit 125).
잔존 컨테이너는 없으며 사용자 manager는 running, UID 1001 bus socket도 존재했다.

별도 XDG_RUNTIME_DIR은 유지하면서 실행 환경에
`DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1001/bus`를 명시한다.
자원 제한 제거, polkit 권한 추가 또는 운영 Docker 변경은 하지 않는다.
같은 UID의 사용자 manager 접근은 허용되므로 미신뢰 MR sandbox로 간주하지 않는다.
사용자 manager가 생성하는 scope는 새 daemon service 밖에 놓일 수 있어 실제 cgroup 위치와
종료 시 정리도 후속 확인한다. 수정 후 컨테이너 생성 성공은 아직 서버에서 재검증하지 않았다.

## bus 수정 후 서버 증빙과 임시 포트 검사

운영자가 컨테이너 실행 성공, 지정 IPv4/IPv6 대상 연결 실패(errno 101), 종료 0을 확인했다.
컨테이너에서 memory.max=134217728, cpu.max=50000 100000, pids.max=64를 확인했고,
별도 bridge의 두 컨테이너 HTTP 통신과 정리도 PASS였다. 호스트 상위 cgroup과
서비스 종료 시 사용자 scope 정리는 아직 미검증이다.

`ci-offline-port-check.py`는 전용 socket/data-root/rootless/UID/빈 컨테이너 목록을 확인하고,
전용 docker.pid의 dockerd UID/매핑 및 직접 user/net/mount namespace를 기존 helper로
검증한다. namespace FD를 고정하고 namespace 안에서 신원을 다시 검사한다.
기존 온라인 daemon의 detached netns 경로는 변경하지 않는다. PID를 인자로 받지 않는다.
임시 서버는 loopback random port만 공개하며 클라이언트는 고정 HTTP 응답 토큰을 비교한다.
DNS 설정, 인터넷 접속, 이미지 pull, backend 실행, 운영 규칙 변경은 하지 않는다.
정리는 이번에 만든 container/network ID만 대상으로 한다. Docker 요청 timeout 시 생성 여부가
불명확할 수 있으므로 오류 후 ps/network 목록을 확인하고 무작정 재실행하지 않는다.

새 검사기와 `ci-rootless-network-check.py`를 같은 읽기 가능한 설치 폴더에 둔 뒤
feelm-ci-test로 `python3 -B ci-offline-port-check.py run --confirm`을 실행한다.
root 실행은 거부한다. 성공 기준은 NAMESPACE_TARGET, NAMESPACE_IDENTITY,
OFFLINE_LOOPBACK_PORT: PASS, PROBE_CLEANUP: PASS, OFFLINE_PORT_EXIT=0이다.
로컬 회귀 테스트만 수행했으며 새 임시 포트 검사 자체의 서버 결과는 아직 없다.

## 임시 포트 통과 후 전체 backend 실행

운영자 출력에서 namespace identity, OFFLINE_LOOPBACK_PORT, cleanup 모두 PASS와 종료 0을
확인했다. Redis/pgvector/Ryuk 3개 이미지도 원본 digest 검사 후 파일로 이전하고 image ID가
일치했다. 준비 입력은 `offline-inputs/9627ce2a9b774f11a71743bcfb8aec93`, 캐시 2986파일,
406181461 bytes이며 준비 종료 0이다. 위 증빙은 운영자가 제공한 결과다.

`ci-offline-backend-run.py`는 같은 폴더에 offline prepare/port/network/backend helper와
rootless-backend 지원 파일이 필요하다. 기존 검토 archive는 원래 pilot-222 경로에 유지한다.
`plan`은 무변경이며 `run --prepared-dir ... --confirm-reviewed-offline-pilot`만 실행한다.
feelm-ci-test의 `systemd-run --user --scope`에서 실행하여 user-1001.slice 합산 제한을 확인한다.
새 Docker는 최대 1시간 수명이므로 빈 상태를 확인한 뒤 테스트 전에 새 서비스만 재시작하고
전용 Docker API 준비를 확인한다. 기존 Docker/guard는 재시작하지 않는다.

소스는 고정 archive SHA와 파일별로 비교하고 지원 코드·캐시·이미지 ID/OS/architecture를
재확인한다. 추가 파일/링크, 이전 실행 폴더, 잔존 컨테이너가 있으면 거부한다. 준비 manifest는
불변 증빙으로 남기며 별도 execution-started/result 파일을 쓴다. 캐시는 이번 실행으로 변한다.
실패하더라도 시작 표시는 지우지 않고 새 입력 폴더로 재준비한다. 소스/캐시를 다른 작업이
동시에 수정하지 않아야 하며 미검토 MR이 쓴 캐시는 신뢰하지 않는다.

검증한 새 Docker user/net/mount FD 안에서 전체 `clean test spotlessCheck --offline`을 실행한다.
개인 DNS mount, 인터넷 허용, pull fallback, Ryuk 비활성화, 테스트 생략을 추가하지 않는다.
30분 timeout 시 프로세스 그룹을 종료하고 컨테이너는 조회만 한다. 자동 prune는 하지 않는다.
Gradle 0, 보고서 구조·필수5클래스 통과, 총82건/skip0, 잔존컨테이너0을 모두 요구한다.
보고서는 준비 폴더 backend/build에 보존한다. 실행 중 daemon 종료나 예외 시 결과 파일이
없을 수 있으며 이를 성공으로 취급하지 않는다. 실제 full offline 실행은 서버에서 아직 미검증이다.
호스트 파일/사용자 bus/동일 UID 소켓 격리와 상위 cgroup 정리 검증은 여전히 남아 있다.
