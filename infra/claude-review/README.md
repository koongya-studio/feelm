# Claude MR 리뷰 Runner — S15P21E106-276

## 현재 단계

Claude MR 리뷰 전용 Runner의 계정, 네트워크, systemd sandbox와 수동 구독 인증 검사를
준비한 단계다. 실제 MR 변경 수집, 한국어 리뷰 생성, GitLab 댓글 등록, 중복·stale 방지와
자동 실행은 아직 구현하지 않았다.

서버에서 확인한 환경은 다음과 같다.

- systemd 255, nftables 1.0.9
- Python과 GitLab Runner 설치 완료
- `/home/feelm-ci-review/.local/bin/claude`에 Claude Code 2.1.236 설치 완료
- 전용 계정 `feelm-ci-review`: UID 997, GID 986, 추가 그룹 없음
- 확인 당시 가용 메모리 11 GiB, 디스크 여유 공간 268 GiB
- DNS: `127.0.0.53`, `172.26.0.2`

UID와 서버 자원 수치는 다른 환경의 기본값으로 사용하지 않는다. 기존 MR Runner인
`feelm-ci-test`와 이 Runner의 계정·설정·서비스를 분리한다.

## 신뢰 경계

- Runner 태그는 `e106-claude-review`이며 protected, 프로젝트 잠금, untagged Job
  비활성화를 적용한다.
- 최초 실행은 보호된 `develop` 파이프라인에서만 한다. MR 소스 브랜치의 CI, 빌드
  스크립트, hook, plugin 또는 에이전트 설정을 리뷰 토큰과 함께 실행하지 않는다.
- `CLAUDE_CODE_OAUTH_TOKEN`은 protected, masked/hidden, 변수 확장 비활성화, 환경 범위
  `claude-review`로 등록한다. 실제 값은 저장소와 로그에 남기지 않는다.
- 환경 범위 외에도 보호 브랜치 권한, 파이프라인 변수 덮어쓰기 권한과 입력 처리를 제한한다.
- 사람의 승인과 병합 절차는 그대로 유지한다.

## 네트워크 보호

`network-guard.py`는 리뷰 계정 UID에만 적용되는 별도 `inet feelm_claude_276`
테이블을 관리한다. 기존 MR Runner와 UFW 규칙은 변경하지 않는다.

정책은 지정한 DNS의 UDP/TCP 53과 공개 IPv4의 TCP 443만 허용한다. 운영 서버의 공개 IP를
먼저 차단하고 사설망, loopback, link-local, metadata, 로컬 주소와 IPv6를 거부한다.
공개 HTTPS와 DNS를 통한 유출 가능성은 남으므로 MR 소스는 실행하지 않고 Claude 도구도
허용하지 않는다.

설정 파일 `/etc/feelm-ci-review/network.json`은 root 소유 0600으로 만들고 다음 필드만
둔다.

- `uid`: 리뷰 계정 UID 정수
- `deny_ips`: 운영·분산 서버의 모든 공개 IPv4 주소
- `dns_ips`: 확인된 DNS IPv4 주소

스크립트와 상위 디렉터리는 root 소유이며 그룹·기타 사용자 쓰기 권한이 없어야 한다.
매 실행 시 설정 파일 권한과 리뷰 계정의 UID, 홈, 그룹을 다시 검사한다.

지원 명령은 다음과 같다.

- `plan`: 적용할 nftables 규칙 출력
- `check`: 임시 네트워크 namespace에서 구문과 기준 규칙 생성 확인
- `ensure`: 테이블이 없으면 원자적으로 생성한 뒤 전체 규칙 검증
- `verify`: 설치된 전체 규칙을 기준 규칙과 비교

`ensure`는 기존 테이블이 기준과 다르면 덮어쓰거나 삭제하지 않고 실패한다. 검증 중 문제가
생겨도 새로 설치된 차단 규칙을 자동 제거하지 않는다.

서버 검증 결과는 다음과 같다.

- `ISOLATED_NFT_CHECK: PASS (host firewall unchanged)`
- `GUARD_VERIFIED: 9db60a54786cd9a5cf518e14a5d609362f50bc37007411bb761272cf2d818c6f`
- `lab.ssafy.com`, `api.anthropic.com` TLS 연결 성공
- 운영 서버 `3.36.113.123`, `54.180.237.51` 접근 차단 확인
- loopback SSH와 metadata 주소 접근 차단 확인

상위 DNS, TCP DNS와 IPv6는 실제 패킷 검증이 아직 없다. 공개 TCP 443 전체를 허용하므로
도메인 단위 allowlist는 아니다.

## systemd 서비스

`feelm-claude-review-guard.service`는 부팅 시 보호 규칙을 확인하거나 복원한다. 현재
서버에서 enabled, active/exited, `Result=success`와 위 규칙 digest를 확인했다. 실제
재부팅 복원은 검증하지 않았다.

`feelm-claude-review-runner.service`는 Runner와 모든 자식 프로세스에 다음 경계를
적용한다.

- CPU 1개 상당, 메모리 high 2 GiB, 최대 3 GiB, swap 0, 프로세스 128개
- 권한 상승과 Linux capability 차단
- 다른 홈 디렉터리 숨김, 시스템 파일시스템 읽기 전용
- 리뷰 계정 홈과 전용 임시 디렉터리만 쓰기 허용
- Docker, containerd, D-Bus, systemd 제어 소켓과 운영 경로 차단

읽기 전용 mount는 다른 world-readable 호스트 파일의 읽기까지 막지 않는다. 디스크
quota도 아직 없다. root가 서비스 시작 후 방화벽을 변경하면 격리가 달라질 수 있다.

`/etc/feelm-ci-review/enable-runner` 파일이 없으면 Runner가 시작되지 않는다. 보호된 CI
설정과 실제 인증 검사를 진행하기 전까지 이 파일을 만들거나 서비스를 활성화하지 않는다.
Runner 시작 전에는 root 소유의 고정 `ExecStartPre`가 전체 네트워크 규칙을 다시 검증한다.
이 실행은 리뷰 계정에 sudo 권한을 주지 않는다.

서비스 파일은 기존 동일 이름 파일이 없는지 확인한 뒤 root 소유 0644로
`/etc/systemd/system/`에 설치한다. `systemd-analyze verify`를 통과한 다음
`systemctl daemon-reload`를 실행한다.

## 일회성 sandbox 검사

`feelm-claude-review-probe.service`는 Runner와 동일한 계정, sandbox, cgroup 제한을
적용하지만 GitLab Runner나 인증된 Claude 요청은 실행하지 않는다.

서버 검사에서 전용 계정, 권한 상승·capability 차단, 자원 제한, 홈·파일시스템 격리,
제어 소켓 차단, Claude Code 2.1.236 실행과 인증 없는 GitLab·Anthropic TLS 연결이 모두
통과했다. 결과는 `Result=success`, `ExecMainStatus=0`, `SANDBOX_PROBE: PASS`였다.
소켓 검사는 소켓이 없거나 sandbox가 거부한 경우를 구분하지 않으며 모든 호스트 파일과 IPC
경로의 격리를 증명하지는 않는다.

## 수동 구독 인증 검사

루트 CI 파일의 `claude-auth-check` Job은 보호된 `develop`의 push 또는 web
파이프라인에서만 수동으로 표시된다. Runner 태그와 환경 이름은 `e106-claude-review`,
`claude-review`이며 동시 실행을 막는 `resource_group`, 5분 제한, artifact 미생성을
적용한다. 실패는 표시되지만 기존 파이프라인을 막지는 않는다.

`auth-check.py`는 소스 코드가 아닌 고정된 `OK` 요청만 보낸다. 도구, skill, hook,
MCP와 세션 저장을 끄고 빈 작업·홈·설정 디렉터리에서 실행한다. 자식 프로세스에는 Claude
OAuth 토큰과 고정 실행 환경만 전달하며 GitLab 토큰, Anthropic API key, proxy와 프로젝트
환경은 전달하지 않는다. CLI 원문 stdout/stderr도 출력하지 않는다.

보호된 `develop`에 병합하고 Runner를 명시적으로 활성화한 다음 신뢰된 파이프라인에서
수동 Job을 한 번 실행한다. 예상 성공 출력은 `CLAUDE_AUTH_CHECK: PASS`다. 실제 추론은
개인 구독 사용량을 일부 소비한다. 실패 분류는 진단용 휴리스틱이므로 필요하면 비밀값을
제외한 별도 점검을 수행한다.

## 남은 작업

- `claude-mr-review`를 실제 Claude 응답으로 검증
- 생성된 한국어 리뷰의 GitLab 댓글 등록
- 커밋 SHA 확인, 중복 실행 및 stale 리뷰 방지
- 실패 유형별 운영 처리와 자동 실행 전환

보호된 `develop` 파이프라인의 `claude-auth-check` 성공은 2026-09-07 운영자 확인으로
검증했다. 이 기록은 토큰 원문이나 실행 응답을 저장소에 남기지 않는다.
같은 날 `claude-mr-collect`도 실제 열린 MR의 IID와 HEAD SHA로 실행해 성공했음을 운영자가
확인했다.

## MR 입력 수집기

`collect-mr.py`는 MR 브랜치를 checkout하거나 MR의 코드·CI·hook을 실행하지 않고 GitLab
API의 MR 메타데이터와 diff만 `/tmp/feelm-claude-review/input.json`에 0600 권한으로
기록한다. 출력에는 `untrusted_data: true`를 넣어 이후 Claude 입력 조립 단계에서도 신뢰할
수 없는 데이터임을 명시한다.

수집기는 보호된 `develop`의 push 또는 web 파이프라인과 `claude-review` 환경에서만
동작한다. 동일 프로젝트의 열린 MR, `develop` 대상 브랜치와 호출자가 전달한 현재 HEAD
SHA가 모두 일치해야 한다. 외부 Fork, 변경 파일 100개 초과, 파일별 diff 128 KiB 초과,
전체 diff 512 KiB 초과, GitLab이 생략한 collapsed/too-large diff는 검토하지 않고 실패한다.
MR 제목은 1 KiB, 설명은 16 KiB로 제한한다.

필요한 입력은 `CI_API_V4_URL`, `CI_PROJECT_ID`, `REVIEW_MR_IID`, `REVIEW_MR_SHA`와
`GITLAB_REVIEW_TOKEN`이다. 토큰과 diff는 표준 출력에 기록하지 않으며 성공 시
`MR_COLLECTION: PASS`, 실패 시 분류된 오류 코드만 출력한다. 전용 토큰은 최소 API 읽기와
향후 MR 댓글 작성 권한만 부여하고 Protected, Masked/hidden, 환경 범위
`claude-review`, 변수 확장 비활성화로 관리한다.

루트 CI의 `claude-mr-review` Job은 보호된 `develop`의 web 파이프라인에서만 수동으로
표시된다. 파이프라인을 만들 때 `REVIEW_MR_IID`에 MR 번호, `REVIEW_MR_SHA`에 그 MR의 현재
40자리 HEAD SHA를 입력한 뒤 Job을 실행한다. Job은 MR 브랜치가 아니라 보호된 `develop`을
얕게 checkout해 저장소에 고정된 리뷰 스크립트만 실행하며 submodule, cache와 artifact를 사용하지
않는다. 같은 Job에서 수집기와 생성기를 순서대로 실행하므로 MR 데이터와 리뷰 결과를
artifact로 전달하지 않는다. 이전 실행의 입력 파일은 새 API 요청 전에 삭제되어 실패한
실행이 오래된 입력을 남기지 않는다.

## 한국어 리뷰 생성기

`generate-review.py`는 수집기가 만든 비공개 `input.json`만 읽어 Claude Code CLI에
전달하고, 검증된 JSON 리뷰를 `/tmp/feelm-claude-review/review.json`에 0600 권한으로
기록한다. Claude 자식 프로세스에는 OAuth 토큰과 고정된 최소 환경만 전달하고 GitLab 토큰,
API key, proxy와 프로젝트 환경변수는 전달하지 않는다.

시스템 프롬프트는 MR 제목·설명·브랜치·경로·diff 전체를 신뢰할 수 없는 데이터로 선언한다.
도구, slash command, hook, MCP와 세션 저장을 비활성화하고 빈 홈·작업 디렉터리에서 한 번의
응답만 생성한다. 저장소 규칙은 보호된 `develop` 체크아웃의 고정 allowlist에서만 읽는다.
기본 `AGENTS.md`와 `CLAUDE.md`를 사용하고, 변경 경로에 따라 백엔드 규칙, API 계약, DB
문서 또는 파이프라인 계약을 추가한다. MR 경로를 로컬 파일 경로로 직접 사용하지 않는다.

결과는 한국어 요약과 최대 50개의 finding으로 제한한다. 각 finding은 심각도, 실제 변경
파일, 줄 번호 또는 null, 발생 조건, 근거와 수정 제안을 포함해야 한다. 허용되지 않은
심각도, 변경되지 않은 파일, 잘못된 줄 번호, 과도한 결과는 게시 단계로 넘기지 않는다.
성공 로그는 `REVIEW_GENERATION: PASS`뿐이며 Claude 원문과 리뷰 내용은 CI 로그에 출력하지
않는다. 인증 실패, 구독 사용량 초과, 시간 초과와 잘못된 응답은 구분된 코드로 실패한다.

참고 문서: [systemd 255 실행 설정](https://manpages.ubuntu.com/manpages/noble/man5/systemd.exec.5.html),
[GitLab protected Runner](https://docs.gitlab.com/ci/runners/configure_runners/)
