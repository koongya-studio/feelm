# 222: 격리된 systemd 서비스의 시작 거부 검증

`ci-guard-failure-check.py`는 실제 systemd `ExecStartPre` 실패가 본 작업 시작을 막는지 확인한다.
기본 동작은 `plan`이며 실행에는 Linux root, PID 1 systemd, `run --confirm`이 필요하다.
기존 guard 서비스·`user@1001.service`·Docker·UFW·CI 및 운영 설정을 변경하거나 재시작하지 않는다.
현재 Jira 상태·담당자·열린 MR은 이번 작업에서 실시간 조회하지 못했다.

## 검사 범위

매 실행마다 고유 이름의 임시 system 서비스 3개를 순서대로 만든다.
각 서비스는 PrivateNetwork=yes이며, fixture 생성 **전에** network namespace가 호출자의
호스트 namespace와 다른지 확인한다. 준비 스크립트가 자기 namespace 안에서만 테스트용
`feelm_ci_222` 테이블을 생성/변조한다. 운영 테이블을 지우거나 방화벽을 flush하지 않는다.
주소는 문서용 `192.0.2.1/2`를 사용하며 실제 공인 주소나 서버 설정 파일은 읽지 않는다.

| 경우 | 검사 함수 결과 | 실제 서비스/본 작업 기대 결과 |
| --- | --- | --- |
| 정상 규칙 | verified | 시작 성공, 본 작업 실행 marker 존재 |
| 테이블 없음 | missing | ExecStartPre 종료 1, 서비스 failed, 본 작업 미실행 |
| 규칙 뒤에 accept 추가 | mismatch | ExecStartPre 종료 1, 서비스 failed, 본 작업 미실행 |

검사 코드는 이번 checkout의 `ci-guard-service.py`와 `ci-network-guard.py`를 새 root 전용
`/run/ci222-failcheck-*` 디렉터리에 복사한다. **production reference()/verify()를 모킹 없이**
호출한다. 이미 시작된 본 작업이 나중에 실패한 경우와 구분하기 위해 marker뿐 아니라
ExecMainStartTimestampMonotonic=0, ExecStartPre status=1 및 실제 검사 이유까지 요구한다.
namespace 생성 실패, helper 실행 오류, timeout 등을 성공한 차단 검사로 간주하지 않는다.

이것은 설치된 `start-check` 전체를 그대로 실행하는 검사가 아니다. 테스트 fixture는
운영 config/계정 확인 및 자원 제한 검사 대신 지정된 UID/문서용 주소를 사용한다.
`Requires` 의존성 실패 전파, 실제 user@ 서비스의 `+` 권한 전환, 재부팅 및 실행 중 규칙 소실은
별도 범위다. 운영 user@ 정상 시작은 앞선 서버 로그로 확인했지만, 미검토 MR 격리 승인은 아니다.

## 서버 실행

222 브랜치의 수정 커밋을 확인한 뒤, 서비스 서버 ubuntu 계정에서 실행한다.
설치된 guard 코드를 덮어쓰거나 서비스를 재시작할 필요는 없다.

```bash
cd ~/S15P21E106
git pull --ff-only
git log -1 --oneline
python3 infra/scripts/ci-guard-failure-check.py plan
sudo /usr/bin/python3 -I -B infra/scripts/ci-guard-failure-check.py run --confirm
echo "실패 경로 검사 종료 코드: $?"
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

기대 결과:

```text
VALID: PASS (workload ran)
MISSING: PASS (workload blocked)
MODIFIED: PASS (workload blocked)
DISPOSABLE_UNITS_STOPPED: PASS; evidence retained in /run until reboot
FAILURE_GATE_CHECK: PASS; NOT_RUNNER_ISOLATION_APPROVAL
실패 경로 검사 종료 코드: 0
```

운영 컨테이너 6개도 실행 상태가 유지되어야 한다. 실패하면 재시작/규칙 완화 없이 출력을
확인한다. 인쇄된 FAILCHECK_RUN_DIR에 case별 gate 증빙, 서비스 상태, 실행 결과와 marker가 남는다.
이 폴더는 root만 접근하며 작지만 반복 실행 시 누적된다. 재부팅하면 사라진다.

## 정리와 중단

성공·실패·Ctrl-C/일반 종료 신호 시 이번 실행이 생성한 UUID unit만 stop하고 failed 상태를
정리한다. 해당 프로세스 종료에 따라 임시 network namespace도 해제된다. 규칙 삭제/전체 prune,
운영 서비스 stop, unit enable 및 영구 unit 파일 설치는 하지 않는다.
SIGKILL·시스템 장애로 정리가 실행되지 않거나 `CLEANUP_REQUIRED`가 나오면 인쇄된 정확한
`ci222-failcheck-<UUID>-<case>.service`만 운영자가 상태를 확인한다. 이름 패턴 전체를 일괄 중지하지 않는다.
일부 임시 서비스 정리가 실패하면 전체 검사도 실패로 반환한다.

## 검증 상태

로컬 WSL에서 세 경우 모두 실제 systemd+nft 검사 통과 및 임시 unit 중지를 확인했다.
Python 회귀 테스트는 Windows 87개 통과/실 nft 1개 생략, Linux 88개 전체 통과했다.
운영자가 `d3c90e2` 서버 실행에서 정상/누락/변조 모두 PASS, 임시 unit 중지, 종료 코드 0과
운영 컨테이너 6개 실행 유지를 확인했다. 기존 보호 설정은 그대로 유지한다.

서버 증빙 경로는 `/run/ci222-failcheck-u8ibav_n`이다. 운영자는 이를
`/var/lib/feelm-ci222-evidence/ci222-failcheck-u8ibav_n`으로 복사하고 비교 후 보존 완료를 보고했다.
이 작업에서 서버 파일을 직접 재조회하지는 않았다. 재부팅 검증이나 실행 중 정책 소실 대응
완료를 의미하지 않는다. 다음 [실행 중 종료 실험 및 구조 제안](RUNTIME-GUARD-DESIGN.md)은 별도 단계다.

- [systemd NetworkNamespacePath/PrivateNetwork](https://github.com/systemd/systemd/blob/main/man/systemd.exec.xml)
- [systemd ExecStartPre 실패 처리](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml)
