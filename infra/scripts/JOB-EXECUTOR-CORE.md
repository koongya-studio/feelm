# S15P21E106-222 작업 실행기 핵심 경로

상태: **입력/보고서 경계와 수명주기 핵심 구현, 고정 소스 서버 검증 단계.**
GitLab job을 받는 실행기 등록이나 `.gitlab-ci.yml` 전환은 하지 않았다.
이 문서는 [고정 서버 파일럿](SERVER-MANAGER-PILOT.md) 이후의 증빙이며,
일반 MR 실행 승인 또는 Jira 완료를 의미하지 않는다.

후속 합의로 범용 확장은 하지 않는다. 단일 작업의 종료·용량·파일 경계 보완은
[최소 안전장치](JOB-MINIMUM-SAFETY.md)에 별도 기록하며 아래 미완료 목록은 이 문서 작성 시점이다.

## 구현

- `ci-job-io.py`: 전체 검증을 끝낸 backend archive만 새 private 디렉터리에 설치한다.
  경로 이탈, symlink/hardlink, 특수 파일, 중복/충돌, 권한 비트와 크기 초과를 거부한다.
  `.git`, `.gradle`, 실제 `.env*`는 거부하며 추적된 정확한 `.env.example`만 허용한다.
  archive 소유권·xattr·capability를 복원하지 않는다. 비밀 탐지기를 대체하지 않는다.
- 보고서는 전체 작업 종료 확인 뒤 고정 경로의 제한된 XML만 읽는다. Linux dirfd와
  `O_NOFOLLOW`, 파일 inode/크기/시간 재검사로 링크 및 읽는 도중 교체를 검사한다.
  소스 최대 64 MiB, 파일당 16 MiB/10,000개, XML당 4 MiB/합계 16 MiB/
  디렉터리 항목 256개로 제한한다. XML DTD/entity와 요약/실제 testcase 불일치를 거부한다.
- 보고서의 표시 이름 대신 testcase classname을 사용한다. 프로세스 종료 코드는
  작업이 쓴 파일이 아닌 신뢰된 실행 제어부에서 받는다. 보고서만 성공이어도 실패한
  프로세스를 성공으로 바꾸지 않는다. 결과 manifest는 복사가 끝난 뒤 마지막에 기록한다.
- `ci-job-lifecycle.py`: 준비 → 시작 → 실행 → 중지 → 중지 확인 → 보고서 수집 순서를
  강제한다. 준비/시작 실패도 정리하며, 정리 확인 실패는 `quarantined`로 남기고
  보고서를 수집하지 않는다. 취소/시간 초과는 성공으로 판정하지 않는다.
- `BackendJobDriver`: 위 핵심을 기존 서버 파일럿에 연결했다. 입력은 여전히 검토된
  고정 revision뿐이며 정상 실행은 정확히 82개/필수 5개 클래스/skip 0을 요구한다.
  임시 서비스 이름을 검증하고 정리 후 cgroup 부재를 확인한다. 기존 파일럿 cgroup이
  남으면 새 작업을 시작하지 않는다. 준비 서비스 정리가 실패해도 실행 서비스 정리를 시도한다.

라이브러리는 자체 sandbox나 GitLab adapter가 아니다. 일반 MR은 `Driver`, 종료 코드,
정리 증명, 취소 권한을 공급할 수 없어야 한다. XML은 테스트 출력이지 격리 증명이 아니다.

## 검증 기록 (2026-09-05 KST)

Linux WSL 회귀 검사: `test_ci_*.py` **229개 중 228개 통과, 환경 조건 1개 skip**.
경로/링크/교체/FIFO/용량/손상 XML과 정상·실패·취소·시간 초과·정리 실패를 검사했다.
단위 테스트의 모의 Driver 검증과 아래 실제 서버 실행은 구분한다.

공통 입력은 `/var/tmp/ci222-server-6gqql4ju`를 재사용했다. source revision은
`9631afd9c616e047abbdd0ad7bf8192231f7f35c`, archive SHA256은
`b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1`이다.

1. 최초 새 입력 검사에서 추적된 `backend/.env.example`까지 거부하여 준비 실패했다.
   `/var/tmp/ci222-server-e3g2rzn6/run-u7xa5a90`에 보존했다. 실패를 성공으로 바꾸지 않고
   종료/정리했다. 정확한 예시 파일만 허용하고 실제 고정 git archive 회귀 검사를 추가했다.
2. 새 수명주기 정상 실행: `/var/tmp/ci222-server-uop1g6td/run-gzp7x0qf`.
   실제 프로세스 종료 0, `outcome=success`, 중지 확인 후 XML 수집 및 manifest 성공.
   백엔드 82개, 실패/오류/skip 0, 필수 클래스 누락 없음, spotlessCheck 성공.
3. 실제 테스트 중 취소: 같은 stage의 `run-w1nrj5th`.
   Gradle `:test`와 실제 pgvector 테스트 DB 실행을 확인한 뒤 취소했다.
   `outcome=cancelled`, `passed=false`, `fixture_passed=true`; 준비/실행 cgroup 부재 확인.
   중단된 테스트의 XML을 성공 증빙으로 내보내지 않았다.
4. 실제 테스트 중 시간 초과: `/var/tmp/ci222-server-ltvg966y/run-qmvox35_`.
   Gradle `:test`와 테스트 DB가 실행된 뒤 제어부 timeout을 주입했다.
   `outcome=timeout`, `passed=false`, `fixture_passed=true`; 준비/실행 cgroup 부재 확인.
   실행 서비스는 `ci222-server-1cd903ba901f.service`이며 timeout을 테스트 성공으로
   판정하거나 미완성 보고서를 성공 증빙으로 수집하지 않았다.

위 정상/취소 stage 전송 SHA256:
`aa563e50a8d4172072fdffe2de1a7e4c8b3b1ced68a9357b0a1c58604d13d8dd`.
후속 정리 실패 처리와 canary 실패 판정을 보완한 stage는
`/var/tmp/ci222-server-ltvg966y`, SHA256
`99f8713f952d050891380ba56aac58dcca74dd373ad42a4850247fed2ba55691`이다.
앞선 정상/취소 결과를 이 후속 코드의 재실행 결과로 간주하지 않는다.
이 후속 코드는 실제 서버 timeout 경로에서 실행했고, 추가한 정리 실패 분기는
로컬 회귀 검사로 확인했다. Windows에서는 229개 중 213개 통과/16개 환경 skip이며,
Linux 전용 파일 시스템 검사 15개는 위 WSL 실행에서 모두 통과했다.

각 run의 `lifecycle.json`, `preparation.log`, `execution.log`을 보존한다.
정상 실행은 `evidence/manifest.json`과 XML, 취소 fixture는 rootfs의
`work/gradle.log`, `work/lifecycle-ready`를 추가 증빙으로 남긴다.
준비 실패와 중단 결과도 삭제하지 않는다.

최종 운영 확인: 파일럿 `ci222-server-*` cgroup 잔존 없음, 운영 컨테이너 6개 실행,
기존 Runner active, 랜딩 HTTPS 200. root 디스크는 29 GiB 사용/281 GiB 여유(10%)였다.
준비 입력과 실패/정상/중단 증빙은 보존하며 자동 삭제하지 않았다.
최신 `origin/develop`(`fe17791`)의 분기 이후 변경 중 `infra/` 중복 변경은 없었다.

## 실행 의미와 남은 작업

`backend-cancel`/`backend-timeout`은 고정 소스의 실제 테스트 DB가 시작된 뒤 각각
제어부 취소/시간 초과를 발생시키는 fixture다. readiness 파일은 신뢰된 고정 시험용이며
임의 MR의 준비 완료나 정리 증명으로 사용할 수 없다. timeout은 준비 완료 후 1초를
기준으로 주입하며 실제 GitLab timeout/40분 RuntimeMax 만료 검증과 다르다.

아직 필요한 작업:

- 제어 프로세스 강제 종료 및 Docker 장애 때의 외부 감시·복구, 영속 작업 기록과
  재시작 시 고아 작업 정리. 현재 메모리 상태/최종 JSON과 서비스별 40분 상한만으로는 부족하다.
- 임의 MR의 디스크/로그 사용 상한, 동시 작업·UID 재사용 및 FD/마운트 전달 경계 검증.
  지금의 입력/보고서 크기 제한과 실행 전 여유 공간 확인은 디스크 quota가 아니다.
- GitLab의 실제 소스 준비·job script·취소·timeout·보고서 업로드 연결과 인증정보 분리.
  실행기 종류 확정, Runner 등록/태그/보호 설정 전환은 별도 적용 단계다.
- same-kernel 및 readonly full sysfs의 잔여 위험 검토. 고정 파일럿 성공으로 제거되지 않는다.

따라서 현재는 일반 MR 소스나 임의 명령을 받지 않는다. 운영 Docker, 기존 Runner,
랜딩 파일, CI, 방화벽 설정을 변경하지 않는다. 서버 파일럿 전송은 새 전용 임시 폴더에
한정되며 Git 저장소 push와 구분한다.
