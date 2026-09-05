# 222: 부팅 시 통신 차단과 테스트 사용자 시작 검사

현재 단계는 **서버 설치·정상 시작 및 임시 서비스 실패 경로 검증 완료, 재부팅 검증 대기**다.
기존 GitLab Runner 및 `.gitlab-ci.yml`은 변경하지 않는다.
Jira 222의 저장소 계획을 기준으로 작성했으며, 이번 작업에서 Jira 담당자·상태와 열린 MR은
실시간 조회하지 못했다. 222 완료나 미검토 MR 실행 승인이 아니다.

## 설계와 제한

- `ci-guard-service.py`는 root 소유·다른 사용자 쓰기 불가 경로의 코드/설정만 사용한다.
  기존 `ci-network-guard.py`의 승인된 규칙 생성 함수를 재사용한다. UID 1001과 전용 그룹을
  확인하고 실제 운영 공인 IP 최소 2개를 서버 설정에 요구한다. 예시 설정 그대로는 실패한다.
- 별도 임시 network namespace 안에서 같은 커널/nft로 기준 규칙을 생성하고 JSON을 읽는다.
  호스트 `inet feelm_ci_222` JSON과 비교한다. dump 메타데이터·handle·익명 counter 값만 제외하며,
  marker·UID·주소·DNS·IPv6·hook/priority/policy·규칙 순서·추가 객체는 비교 대상이다.
  prefix 합치기/암시적 reject 표현은 nft 자체가 정규화한다. 명령은 timeout 30초로 제한한다.
- `verify`: 호스트 규칙 조회만 수행한다. 규칙이 없거나 다르면 실패하고 설치·교체하지 않는다.
  기준 생성은 일회성 별도 namespace 안에서만 이루어진다.
- `ensure`: 없으면 `create table`로 전용 테이블만 원자적으로 생성하고 전체 규칙을 재검사한다.
  있으면 검증만 한다. 불일치/사후 검사 실패 시 자동 삭제·교체·flush하지 않는다.
- `start-check`: 위 검증과 현재 사용자 slice의 CPU 200%/memory.high 5GiB/max 6GiB/pids 2048을
  검사한다. 제한이 다르면 실패한다. 설정을 자동 수정하지 않는다.
- `feelm-ci-guard.service`: UFW 뒤, `user@1001.service` 앞에서 ensure를 수행한다.
  중지 시 규칙을 제거하는 ExecStop은 없다. 기존 nftables.service는 disabled로 유지한다.
- `user@1001.service` 전용 drop-in: Requires+After로 guard에 의존하며 root 권한의 고정
  ExecStartPre에서 매 사용자 서비스 시작 시 start-check를 수행한다. 테스트 계정에 sudo를
  부여하지 않는다. 검사 실패를 건너뛰는 ExecCondition/`-` 접두사는 사용하지 않는다.

**중요한 경계:** 사용자 서비스 시작 시점의 검사다. 이미 실행 중인 사용자 서비스에 drop-in을
추가해도 검사가 소급 실행되지는 않는다. 사용자 서비스 밖의 `sudo -u`/SSH 직접 실행이나
매 job 시작을 이 설정만으로 통제하지 못한다. 실행 도중 root가 nft 규칙을 제거하면
RemainAfterExit 서비스가 active여도 차단이 사라질 수 있다. 시작 전 검사에도 조회와 실행 사이
시간 차이가 있다. 지속 감시·job별 실행 경계·우회 검증·실제 Runner 등록은 후속 단계다.
따라서 일반 MR을 연결하지 않는다. guard 서비스 stop은 Requires 관계로 테스트 사용자 서비스를
중지할 수 있으므로 단순 상태 확인 용도로 stop/restart하지 않는다.

## 운영자가 확인한 선행 증빙

- `a5b0483` 설치 후 전용 guard 전체 규칙 검증과 start-check, unit 구문·의존성 검사 종료 0.
  2026-09-04 13:30:40 UTC guard 서비스 active(exited), enable 등록 확인.
- 2026-09-04 13:31:45 UTC 테스트 user@1001 재시작의 실제 ExecStartPre 실행/종료 0,
  GUARD_VERIFIED와 START_GATE: PASS 확인. 사용자 서비스 active/running,
  rootless Docker 응답 및 운영 컨테이너 6개 실행 유지 확인.
- 다음 [격리된 서비스 실패 경로 검사](GUARD-FAILURE-CHECK.md)는 별도 임시 서비스에서 수행한다.
  운영 규칙을 삭제하거나 user@1001을 다시 재시작할 필요는 없다.

- `ea3556c` 서버 보고서 재검사: 82건, 실패/오류/생략 0, 필수 클래스 누락 없음,
  REPORT_STRUCTURE_VALID=True, REPORT_RECHECK_EXIT=0. test Docker 컨테이너 목록 비어 있음.
  기존 Gradle 실행의 종료 코드 2는 변경하지 않는다.
- `/etc/systemd/system.control/user-1001.slice.d/`에 4개 자원 제한 파일과 값이 저장된 것을 확인했다.
  현재 적용값도 일치한다. 재부팅 후 재검증은 아직 하지 않았다. 기존 `/run` 설정은 유지한다.
- UFW enabled, nftables.service disabled. nftables.service의 ExecStop은 전체 ruleset flush이므로
  해당 서비스를 활성화/재시작하지 않는다. `ip filter`, `ip6 filter`, `ip nat`, `ip6 nat`,
  `ip raw` 및 `inet feelm_ci_222` 테이블이 존재한다.

## 1. 서버 pull 후 읽기 전용 확인 — 우선 여기까지만

서비스 서버 ubuntu 계정에서 실행한다. 서버 재부팅·운영 Docker/UFW 재시작은 하지 않는다.

```bash
cd ~/S15P21E106
git pull --ff-only
git log -1 --oneline
command -v nft unshare python3 systemd-analyze
systemctl cat user@1001.service --no-pager
sudo ls -ld /usr/local/lib/feelm-ci222 /etc/feelm-ci222 \
  /etc/systemd/system/feelm-ci-guard.service \
  /etc/systemd/system/user@1001.service.d
sudo -u feelm-ci-test -H docker --host unix:///run/user/1001/docker.sock ps -a
```

새 설치 경로의 `No such file or directory`는 예상된다. 이미 존재하면 내용·소유권과 담당자를
먼저 확인하고 덮어쓰지 않는다. 사용자 서비스 기존 drop-in과 namespace 제한도 검토한다.

## 2. 경로 확인 후 코드·설정만 설치 (서비스 시작/활성화 아님)

아래는 새 전용 경로임이 확인된 경우에만 수행한다. 코드/설정은 테스트 홈이나 checkout이
아닌 root 관리 경로에 설치한다. 저장소 커밋을 검토하고 설치 후 diff를 확인한다.

```bash
sudo install -d -o root -g root -m 0755 /usr/local/lib/feelm-ci222
sudo install -d -o root -g root -m 0700 /etc/feelm-ci222
sudo install -o root -g root -m 0644 \
  infra/scripts/ci-network-guard.py infra/scripts/ci-guard-service.py /usr/local/lib/feelm-ci222/
sudo install -o root -g root -m 0600 \
  infra/scripts/systemd/guard.example.json /etc/feelm-ci222/guard.json
sudoedit /etc/feelm-ci222/guard.json
```

`uid`는 1001로 유지한다. `deny_ips`에는 **현재 전용 nft 테이블에 설정한 실제 서비스·분산 서버
공인 주소 전체**를 JSON 문자열 배열로 입력한다. 도메인/사설 IP/예시 주소를 넣지 않는다.
실제 값은 Git에 올리지 않는다. IP 변경이나 추가 프록시/LB가 있으면 정책 범위를 재검토한다.

```bash
sudo /usr/bin/python3 -I -B /usr/local/lib/feelm-ci222/ci-guard-service.py verify
sudo /usr/bin/python3 -I -B /usr/local/lib/feelm-ci222/ci-guard-service.py start-check
sudo systemd-analyze verify "$PWD/infra/scripts/systemd/feelm-ci-guard.service"
```

기대: GUARD_VERIFIED 및 START_GATE: PASS. 실패 시 여기서 멈춘다. 설정 불일치를 확인하지 않고
기존 테이블을 삭제하거나 검사 조건을 완화하지 않는다. 전체 backend 테스트를 다시 돌리지 않는다.

## 3. 검사 결과 확인 후 별도 승인하여 서비스 연결

위 검사와 기존 사용자 unit 검토가 통과한 후 수행한다. 아래 설치 명령은 기존 동명 파일이
없다는 전제이며, 있으면 중단한다. user@1001 재시작은 아래에 포함하지 않는다.

```bash
sudo install -o root -g root -m 0644 \
  infra/scripts/systemd/feelm-ci-guard.service /etc/systemd/system/feelm-ci-guard.service
sudo install -d -o root -g root -m 0755 /etc/systemd/system/user@1001.service.d
sudo install -o root -g root -m 0644 \
  infra/scripts/systemd/user-1001-guard.conf /etc/systemd/system/user@1001.service.d/60-feelm-ci-guard.conf
sudo systemctl daemon-reload
sudo systemd-analyze verify feelm-ci-guard.service user@1001.service
sudo systemctl start feelm-ci-guard.service
sudo systemctl enable feelm-ci-guard.service
systemctl status feelm-ci-guard.service --no-pager --full
systemctl show user@1001.service -p Requires -p After -p ExecStartPre --no-pager --full
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

각 명령 성공을 확인하고 다음으로 넘어간다. 서비스 시작 실패 시 enable하지 않는다.
연결된 user@의 실제 시작 거부는 아직 검증되지 않았다. 테스트 실행이 없는 유지보수 시점에
테스트 사용자 서비스만 재시작하여 gate를 검증하는 절차를 별도로 합의한다. 운영 규칙을
일부러 지워 실패를 유도하지 않는다. 실패 주입은 격리된 테스트 환경에서 한다.

## 복구 원칙

검사 실패는 테스트 실행을 중지한 상태로 원인을 해결한다. 임의 MR을 실행하기 위해 gate를
제거하지 않는다. 서비스 연결을 되돌릴 필요가 있으면 테스트 작업을 중단하고 운영자가
설치한 정확한 두 unit 파일을 확인·백업한 뒤 복구한다. guard stop/disable만으로 drop-in이
사라지지 않으며, 테이블도 지워지지 않는다. 기존 임시 rollback으로 방화벽만 제거하면
계정의 접근이 다시 가능해지므로 사용하지 않는다. 운영 UFW/Docker/nftables.service는 유지한다.

## 검증 및 근거

Windows 단위 테스트와 별도 network namespace의 Linux 실 nft 회귀 테스트를 제공한다.
실 nft 검사는 생성/기존 일치/추가 규칙 거부/규칙 없음 거부/무관한 테이블 보존을 검사한다.
이번 수정에서 Windows 80개 통과(실 nft 1개는 플랫폼상 생략), 로컬 WSL Linux 81개 전체
통과를 확인했다. `systemd-analyze verify`의 guard unit 구문 검사는 종료 0이었다.
WSL의 Windows 마운트 권한 경고와 기존 snapd unit의 RestartMode 경고가 있었으며,
실제 설치 시 root:root/0644로 복사한다. 이후 서버 user@ drop-in 적용과 정상 재시작은 확인했다.
실제 부팅 및 실패 조건의 시작 거부는 별도 검증 단계다.
서버에서 이 테스트를 실행하려면 운영자 검토 후 아래 명령을 사용한다. 호스트 규칙은 변경하지 않는다.

```bash
sudo env CI222_LINUX_NETNS_TEST=1 python3 -B infra/scripts/test_ci_guard_service_linux.py
```

- [systemd service 실행·실패 처리](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml)
- [systemd 의존성과 순서](https://github.com/systemd/systemd/blob/main/man/systemd.unit.xml)
- [nft JSON 출력과 handle](https://git.netfilter.org/nftables/tree/doc/libnftables.adoc)

공유 커널/파일·Unix socket·디스크 quota, IPv6 실차단, 부팅/복구 및 실행 중 정책 소실,
미검토 MR job 격리는 아직 완료되지 않았다. 이번 구현으로 222를 종료하지 않는다.
