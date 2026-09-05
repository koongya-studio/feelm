# 222: 실행 중 정책 소실 대응 — 오프라인 실험과 실행 구조 제안

범위: 검토된 고정 fixture의 수명주기 검증. 기존 CI, Runner, 서버 guard/user@ 및 운영 Docker를
변경하지 않는다. 이번 작업에서 Jira의 실시간 담당자·상태·MR은 조회하지 못했다.
일반 MR용 운영 구현은 아직 아니다. 서버 신규 명령 실행 전에 코드 검토와 별도 승인이 필요하다.

## 먼저 구분할 두 문제

1. **접근 자체의 차단:** 호스트 nft 규칙이 사라진 순간부터 poll 감지까지 노출될 수 있다.
   감시 주기를 짧게 하거나 watcher 하나를 더 두는 것은 이 간격을 없애지 않는다.
2. **이상 시 작업 종료:** 감지 뒤 작업을 종료하고 다음 작업을 받지 않는 기능이다.
   감시 프로세스가 멈춰도 상위 관리자가 작업을 종료해야 한다.

이번 fixture는 두 번째 문제의 일부를 검증한다. 첫 번째 문제를 회피하기 위해 고정 실험은
PrivateNetwork=yes, loopback 이외 interface 없음이 확인된 **외부 통신 없는 namespace**에서만
실행한다. 시험 규칙을 삭제해도 그 namespace에 운영망 interface가 새로 생기지 않는다.
하지만 root 권한의 fixture이므로 악성 코드의 namespace 탈출·로컬 파일/Unix socket 접근
차단 증거로 삼을 수 없다. 실제 네트워크 연결이나 IPv6 공격 시험도 하지 않는다.

## 실제 MR 실행 구조 후보 — 아직 적용하지 않음

우선 검토할 후보는 의존성 준비와 테스트 실행의 분리다.

- 준비 단계: 검토된 도구·고정된 의존성으로 이미지/캐시를 준비한다. 다운로드가 필요하다는
  이유로 신뢰하지 않는 MR의 Gradle/build script를 운영 권한으로 실행하지 않는다.
  변경된 의존성의 검토·캐시 누락 처리 정책이 필요하다.
- 실행 단계: 작업별 전용 UID/파일/프로세스/네트워크 경계 안에서 미리 준비된 입력만 사용한다.
  rootless Docker도 같은 작업 경계 안에 있어야 하며, 현재 외부 통신 가능한 공용 rootless
  daemon 소켓을 연결하는 것만으로는 오프라인 경계가 성립하지 않는다.
- 실제 테스트 컨테이너 간 통신은 허용하되 운영망 경로는 만들지 않는다.
  backend 전체 테스트와 이미지/Gradle 캐시의 오프라인 호환성은 새로 검증해야 한다.
- 감시자는 job과 다른 신뢰 주체로 두고, job이 감시 코드·상태·알림을 변조하거나 다른 cgroup으로
  탈출하지 못하게 한다. 작업 실패 이후에는 새 작업을 승인하지 않고 운영자가 원인을 검토한다.
- 상위 systemd의 watchdog 및 cgroup 종료를 보조 수단으로 사용한다. Docker가 별도 daemon에서
  만든 컨테이너는 일반 자식 프로세스와 다르므로 단순 KillMode만으로 정리된다고 가정하지 않는다.

위 내용은 아키텍처 후보이지 확정된 CI/의존성 정책 변경이 아니다. 실제 Runner 구현 전에
오프라인 실행 가능성, rootless daemon의 위치, 파일·소켓·capability 경계와 사람의 복구 절차를
검증/합의해야 한다. 임시 root fixture를 그대로 Runner executor로 사용하면 안 된다.

## 이번 구현: `ci-guard-runtime-check.py`

기본 `plan`은 변경하지 않는다. `run --confirm`만 새 UUID의 system service 4개를 순서대로 생성한다.
검토된 root supervisor가 테스트용 nft 규칙과 기준 JSON을 준비하고 기존 `verify()`를 호출한다.
본 작업은 고정 `/bin/sleep` 하나뿐이다. 일부러 새 session으로 분리해도 같은 서비스 cgroup의
종료 대상이 되는지 확인한다. 임의 명령/저장소 코드/Gradle/Docker/Secret은 입력받지 않는다.

| 경우 | 주입·판정 | 본 작업 |
| --- | --- | --- |
| valid | 초기/반복 검사 성공 후 정상 종료 | 종료 확인 |
| missing | 실행 중 임시 테이블 삭제 후 missing 감지, main 종료 1 | systemd가 종료, PID 재사용도 구별 |
| modified | 실행 중 accept 규칙 추가 후 mismatch 감지, main 종료 1 | systemd가 종료 |
| watchdog | 정상 시작 후 supervisor의 heartbeat 중단 | systemd Result=watchdog 및 main/child 종료 |

Type=notify, NotifyAccess=main, WatchdogSec=3, KillMode=control-group, Restart=no,
RuntimeMaxSec=20, 시작 timeout 30초, 종료 timeout 3초, CPU 100%/메모리 256MiB/PID 64다.
watchdog signal은 SIGKILL, core dump는 비활성화한다. 이 숫자는 **짧은 실험용**이며 실제
Gradle/nft 지연을 고려한 운영값이 아니다. 검사 중단과 RuntimeMax timeout을 구분해 판정한다.

직접 주입한 장애 직후 재검사하는 fixture이므로 실제 감시 탐지 지연의 상한이나 모든 race를
측정하지 않는다. 새 MR job의 admission 제어도 아직 구현하지 않았다. 기존 시작 거부 시험과
이번 실행 중 종료 시험을 조합해도 일반 MR의 보안 경계를 증명하는 것은 아니다.

## 로컬/서버 검증 방법

로컬 WSL 실험에서 VALID/MISSING/MODIFIED/WATCHDOG 모두 PASS, 자식 종료와
`CLEANUP_REQUIRED: []`, 전체 종료 코드 0을 확인했다. 최초 실행의 자동 회수 unit 정리 오판을
수정한 뒤 재실행했다. 회귀 테스트는 Windows 95개 통과/실 nft 1개 생략, Linux 96개 전체 통과다.
이후 운영자가 `4196796` 서버 runtime fixture에서도 4개 경우 PASS, 자식 종료,
`CLEANUP_REQUIRED: []`, 종료 0 및 운영 컨테이너 6개 실행 유지를 확인했다.
`/run/ci222-runtime-_56d3aok`를
`/var/lib/feelm-ci222-evidence/ci222-runtime-_56d3aok`로 복사하고 비교 종료 0을 보고했다.
이 작업에서 서버 보존 파일을 직접 재조회하지는 않았다.

이번에는 로컬 Linux에서 먼저 실행한다. 서버에는 설치·등록하지 않는다.
운영자가 새 커밋과 실행 범위를 승인한 뒤에만 다음과 같이 서버에서도 시험할 수 있다.

```bash
python3 infra/scripts/ci-guard-runtime-check.py plan
sudo /usr/bin/python3 -I -B infra/scripts/ci-guard-runtime-check.py run --confirm
echo "실행 중 검사 종료 코드: $?"
```

기대는 VALID/MISSING/MODIFIED/WATCHDOG 각각 PASS, `CLEANUP_REQUIRED: []`,
`RUNTIME_CHECK: PASS; OFFLINE_FIXTURE_ONLY; NOT_RUNNER_ISOLATION_APPROVAL`, 종료 0이다.
단순 연결 실패나 서비스 실패만으로 성공시키지 않는다. 초기 검증·주입 결과·network identity·
systemd Result/MainPID 및 자식 PID/starttime 증빙을 함께 검사한다.

원본 증빙/코드 복사본은 root 전용 `/run/ci222-runtime-*` 아래 남는다. 반복 시 누적되고
재부팅 시 사라진다. 자동 삭제하지 않는다. `finally`에서 이번 UUID service만 중지한다.
정상 종료 후 systemd가 이미 회수한 unit은 정리 완료로 구분한다. 아직 살아 있는 unit은 실패다.
SIGKILL/시스템 장애 시 자동 정리가 실행되지 않을 수 있으므로 인쇄된 정확한 unit 이름으로
확인한다. 이름 패턴 전체를 일괄 stop하지 않는다. 기존 서버 guard/Docker를 중지하지 않는다.

## 남은 작업

- 서버에서 승인된 fixture 실행과 증빙 보존(로컬 검증만으로 서버 통과 주장 금지).
- 오프라인 backend/Testcontainers 실제 실행 가능성과 작업별 rootless daemon 구조 검증.
- 미신뢰 job과 supervisor의 권한 분리, 새 job admission 및 악성 코드 우회 시험.
- 실행 도중 rule 소실/감시 실패를 포함한 실제 job 정리·복구 및 재부팅 검증.

[systemd watchdog 동작](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml),
[PrivateNetwork의 범위](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml)를 근거로 한다.
