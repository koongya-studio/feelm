# S15P21E106-222 새 local manager의 backend 검증

상태: **로컬 고정 소스 호환성 및 정상/취소/timeout 정리 검사 통과. 서버 적용과 일반 MR 전환은 미완료.**

후속 운영자 승인으로 [서버 파일럿](SERVER-MANAGER-PILOT.md)을 실행했다.
서버 자원 제어와 sysfs 준비 보완 후 별도 새 실행에서 82개/spotlessCheck와
고정 취소/timeout fixture가 통과했다. 일반 MR/Runner 전환은 미완료다. 아래 82개는 로컬 결과다.

## 최종 실제 결과 (2026-09-05 KST)

- 검토 revision: `9631afd9c616e047abbdd0ad7bf8192231f7f35c`
- 소스 SHA256: `b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1`
- 환경: 로컬 WSL Ubuntu 22.04, 추출한 systemd-nspawn 249.11, Docker 29.7.2,
  Temurin JDK/javac 21.0.12.1. 서버 전역 패키지나 서비스는 변경하지 않았다.
- 별도 user/PID/mount/network/cgroup namespace 및 호스트와 다른 UID 매핑.
  승인된 keyctl 예외만 유지했다. 호스트 user bus와 Docker socket은 전달하지 않았다.
- 외부 scope: CPUQuota=200%, MemoryMax=6G, TasksMax=2048. 컨테이너 자원 제한 검사도 선행 통과.
- 입력 전체 해시 검사 후 rootfs에 복사. 이미지 3개 load 후 manifest의 image ID와 비교.
- `clean test spotlessCheck --offline --no-daemon --max-workers=2` 실행, **43초/9 tasks/종료 0**.
- **82 tests, failures=0, errors=0, skipped=0**, 필수 5개 클래스 누락 없음, 보고서 구조 유효.
- 잔존 컨테이너 없음. 정상 종료 후 scope inactive, 합성 호스트 파일 내용 불변.
- 최종 스크립트 단위 테스트 159개 중 158개 통과/1개 환경 조건으로 건너뜀. diff 공백 검사 통과.
- 이전에 통과한 파일/socket bind 차단과 고정 privileged/host namespace 검사도 같은 실행에서 재통과.

최종 로컬 실행 경로: `/tmp/ci222-manager-2a6922t0`.
Windows 보존 증빙:
`C:/Users/SSAFY/AppData/Local/Temp/ci222-backend-evidence-ogfgcpdh/results`

`result.json`은 manager 종료코드와 전체 stdout/stderr, `backend-result.json`은 합격 조건,
`TEST-*.xml`은 이번 실제 실행 보고서다. 과거 서버 XML을 합격 판정에 재사용하지 않는다.
이전 실행 `/tmp/ci222-manager-3nqvw0jq`도 27초/82개 통과했지만 파생 캐시 경고가 있었다.
WSL 임시 파일을 사후 복사하는 시도는 실패했으므로 그 실행의 파일 보존을 주장하지 않는다.
최종 실행은 프로세스 종료 전에 Windows로 증빙을 복사하고 실제 파일 존재를 다시 확인했다.

## 취소·시간 초과

백엔드 전체 실행과 별개인 고정 lifecycle fixture를 사용했다. Docker가 실제 sleep
컨테이너를 실행하고 ready 표식을 만든 뒤 중지했다. 기존 서버 및 다른 작업 scope를 중지하지 않았다.

| 경로 | 결과 |
| --- | --- |
| `/tmp/ci222-manager-fr7dd005`, cancel | PASS: 실행 중인 컨테이너와 scope/cgroup 제거, host canary 불변 |
| `/tmp/ci222-manager-u9t6kquy`, timeout | PASS: 실제 대기 시간 초과 후 scope/cgroup 제거, host canary 불변 |

취소/timeout의 nspawn 종료코드 1과 KILL 메시지는 의도한 강제 종료 결과다.
준비 표식 없이 끝나거나 cgroup이 남으면 성공으로 인정하지 않는다. 전체 Gradle 실행 중
cancel/timeout, 외부 관리자 crash, daemon 단독 crash 및 악의적 탈출의 모든 경우를 검증한 것은 아니다.

## 코드 구성

- `ci-local-backend-stage.py`: 이미 전송한 고정 입력을 native WSL 임시 폴더에 풀어 manager 호출.
  기본 실행은 계획만, `--confirm-local-backend`로 실제 로컬 검증. 다운로드/서버 재전송 fallback 없음.
- `ci-local-backend-runtime.py`: hash 검사, source/JDK/캐시 복사, 고정 backend payload와 결과 판정.
- `ci-local-manager-check.py`: 기존 고정 경계 검사 후 선택적으로 backend 실행.
  `--stop-fixture cancel|timeout`은 backend와 별개로 실행한다.
- 증빙은 새 디렉터리에만 저장하며 링크·범위 밖 경로·과도한 파일 수/크기를 거부한다.

JDK 라이선스의 상대 링크는 같은 배포본 legal 디렉터리 내 일반 파일 내용으로 복사했다.
임의 symlink나 special file을 허용하지 않는다. 입력 캐시 전체는 원본 그대로 감사하고,
rootfs에는 `caches/modules-2`와 `wrapper/dists`만 복사한다. 경로 의존적인 Gradle 파생
캐시는 격리 내부에서 다시 생성한다. 검증 범위/오프라인 조건을 약화하지 않았다.
Windows에서 수천 개 파일을 WSL로 직접 개별 조회하던 지연은 native staging으로 해결했다.

## 남은 경계와 사용자 결정

이 코드는 검토된 고정 fixture이며 임의 MR 입력을 받는 Runner가 아니다.
동일 커널 위험, 병렬 작업의 UID/작업 분리, namespace FD/키링/프로세스 우회,
관리자 장애 경로와 실제 GitLab Runner 권한 전환은 별도 검증이 필요하다.
현재 Jira 실시간 상태/담당자/열린 MR은 이번 로컬 검증에서 확인하지 않았다.

이 로컬 검증 직후에는 서비스 서버에서 기존 Runner를 유지한 제한된 파일럿 승인이 필요했다.
이후 승인된 Ubuntu 24.04 시험과 systemd 차이, 자원 한도 및 중단 결과는 위 서버 문서에 기록했다.
현재 로컬 script는 WSL 전용 제한이 있으므로 서버에서 그대로 실행하지 않는다.
패키지 설치, 서버 새 unit/계정 생성, 실제 MR 연결은 승인 없이 진행하지 않는다.
커밋/푸시도 이번 요청으로 자동 수행하지 않았다.
