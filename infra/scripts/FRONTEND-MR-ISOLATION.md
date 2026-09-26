# 프론트엔드 MR 격리 검증

S15P21E106-330은 GitLab CI 정의를 공통·백엔드·프론트엔드 파일로 나누고,
`develop` 대상 프론트엔드 MR에 `frontend-mr-test`를 추가한다. 기존 `develop` 배포 Job과
백엔드 의존성 캐시 직렬화 계약은 유지한다.

## Job 생성 기준

| MR 변경 | 생성되는 격리 Job |
| --- | --- |
| `frontend/**/*`, `.gitlab/ci/frontend.yml`, 프론트 격리 실행기 | `frontend-mr-test` |
| `backend/**/*`, `.gitlab/ci/backend.yml`, 백엔드 격리 실행기 | `backend-mr-test` |
| 백엔드 Gradle 의존성 파일 | `backend-cache-prepare`, `backend-mr-test` |
| 루트 CI, `.gitlab/ci/common.yml`, 공용 adapter/executor·출력·network guard | 백엔드와 프론트엔드 MR Job 모두 |
| 문서 등 어느 영역에도 속하지 않는 파일 | 두 MR 테스트 모두 생성하지 않음 |

`backend.yml`만 바꾼 MR에는 프론트 Job이, `frontend.yml`만 바꾼 MR에는 백엔드 Job이
생기지 않는다. 루트 `.gitlab-ci.yml`은 stage와 세 include만 관리한다. GitLab이 include를
합친 최종 설정을 검증하므로 MR 파이프라인 생성 자체가 원격 CI 문법 검증이 된다.

`develop` push에서는 기존 `backend-test → backend-image → deploy-backend` 흐름을 유지한다.
프론트 관련 변경은 기존 `frontend-test → frontend-image → deploy-frontend` 흐름을 사용한다.
두 배포 Job은 같은 `service-and-backend-cache` resource group과 현재 commit 검사를 유지한다.

## 실행 경계

`frontend-mr-test`는 `e106-mr-isolated` Custom executor가 받는다. GitLab이 만든 셸을
실행하지 않고 adapter가 다음을 다시 확인한다.

- 인증된 현재 Job과 열린 MR의 프로젝트, HEAD SHA, 대상 `develop`
- `cd frontend`, `npm ci`, lint, format 검사, 전체 Vitest 테스트, build로 고정된 CI script
- 현재 commit의 `frontend` archive만 포함되고 일반 파일·디렉터리만 존재함
- `package.json`의 검토된 script와 v2/v3 lockfile, npm 공식 registry의 SHA-512 무결성
- symlink, 실행 파일, `.env*`, `.git`, `node_modules`, `dist`, 영역 밖 파일이 없음

검증된 소스는 root 소유 읽기 전용 staging에 둔다. 실제 명령은 UID 1001의 rootless Docker와
검토된 Node 22.22.0 이미지 ID로만 실행한다. 컨테이너에는 Docker socket, GitLab Job token,
운영 Secret, 호스트 network를 전달하지 않는다. root filesystem과 소스 mount는 읽기 전용이고
작업·임시 공간은 크기가 제한된 tmpfs다. `/work`는 검증된 lockfile의 native build binding을
로드할 수 있도록 컨테이너 내부에서만 실행을 허용하고 `/tmp`는 실행 금지를 유지한다. CPU 2개,
메모리 2 GiB, PID 512, 실행 15분, 로그 16 MiB 상한을 적용한다.

staging은 transient systemd unit의 `PrivateTmp=yes`에서도 보이는 `/run/ci330-frontend-*`에
원자적으로 생성한다. 임의 이름의 root 소유 디렉터리만 검색 가능하게 열고 내부 source는
읽기 전용으로 설치하며 Job 종료 시 전체 staging을 제거한다. rootless Docker daemon과
systemd unit의 mount namespace가 달라도 빈 bind mount가 생기지 않도록 호스트 경로를 직접
mount하지 않고, 검증된 staging을 Docker 표준입력 tar 스트림으로 컨테이너 tmpfs에 전달한다.
이 스트림은 읽기 전용 staging의 최상위 권한을 `/work`에 적용하지 않으며 컨테이너 내부
파일·디렉터리 권한을 각각 `0644`, `0755`로 정규화한다.

프론트 Job은 공용 adapter 설정의 프로젝트 경계만 확인하며 백엔드 준비 디렉터리를 요구하지
않는다. 따라서 백엔드 캐시·격리 stage 상태와 무관하게 프론트 검증을 시작할 수 있다.

network guard 서비스와 소유 marker가 모두 유효해야 시작한다. 테스트 UID는 기존 guard 정책에
따라 운영·사설망·metadata와 IPv6가 차단되고 DNS 및 공인 80/443만 사용할 수 있다. npm install
script는 `--ignore-scripts`로 실행한다. lint·format·test·build는 `npm run`의 pre/post lifecycle hook을
거치지 않고 이미지의 Node 실행기로 설치된 도구의 고정 JS 진입점을 직접 호출한다. 해당 고정
script의 pre/post hook이 package.json에 있으면 입력 검증에서 거부한다. 성공 후 산출물이나
`node_modules`는 호스트로 내보내지 않는다.

정상·실패·시간 초과에는 workload의 `finally`가 컨테이너를 제거한다. Runner 취소나 강제 종료에도
systemd owner의 `ExecStopPost`가 같은 이름의 컨테이너 부재를 확인한다. 출력 상한 초과 시 owner를
중지한 뒤 같은 정리 경로를 사용한다. 정리 결과를 확인하지 못하면 성공으로 처리하지 않는다.

## 최초 서버 적용 순서

이 MR은 기존 설치 파일을 자동으로 바꾸지 않는다. MR 파이프라인을 만들기 전에 운영자가
격리 Runner 서비스를 정지하고, 검토한 commit의 아래 파일을 root 소유 0644로
`/usr/local/lib/feelm-ci222/gitlab/`에 반영해야 한다.

- `ci-gitlab-custom-executor.py`, `ci-gitlab-job-adapter.py`
- `ci-frontend-mr-boundary.py`, `ci-frontend-mr-runner.py`, `ci-frontend-mr-config.py`
- 기존 import 대상인 `ci-bounded-output.py`, `ci-network-guard.py`

서비스가 정지된 상태에서 먼저 rootless Docker에 검토한 Node 22.22.0 이미지를 내려받는다.
`latest`나 Node major 단독 태그는 사용하지 않는다. 다음 계획 명령은 아무 파일도 바꾸지 않는다.

```bash
sudo /usr/bin/python3 -I -B \
  /usr/local/lib/feelm-ci222/gitlab/ci-frontend-mr-config.py
```

이미지를 준비한 뒤 아래 명령으로 실제 rootless 이미지 ID를 읽어 root:root 0600 설정을 새로 만든다.

```bash
sudo /usr/bin/python3 -I -B \
  /usr/local/lib/feelm-ci222/gitlab/ci-frontend-mr-config.py \
  --confirm-install --node-image node:22.22.0-alpine
```

`/etc/feelm-ci222/frontend-mr.json`의 tag와 `sha256:` ID, 권한을 확인하고 adapter 단위 테스트를
실행한 다음 Runner 서비스를 다시 시작한다. 첫 MR에서는 정상 프론트 변경, lint 실패, 잘못된
lockfile, symlink·영역 밖 파일, 취소, timeout, 대량 출력과 종료 후 컨테이너 부재를 확인한다.
새 adapter 적용 전 MR을 만들면 기존 adapter가 `frontend-mr-test`를 거부하는 것이 정상이다.

## 의존성·이미지 갱신

프론트 패키지 캐시는 영속화하지 않는다. lockfile 변경 MR도 매번 빈 tmpfs에서 `npm ci`를
수행하므로 별도 cache 승격은 없다. 패키지를 바꿀 때는 `package.json`과 `package-lock.json`을
같이 갱신하고, 공식 registry URL과 integrity가 유지되는지 확인한다.

Node 이미지를 바꿀 때는 Runner를 멈추고 새 22.22.0 이미지를 rootless Docker에 미리 준비한 뒤,
동일한 config 도구의 `--confirm-replace --node-image <검토한-tag>`로 ID를 원자적으로 교체한다.
정상·실패 fixture를 다시 통과시킨 후 서비스를 시작한다. tag만 덮어써도 Job은 저장된 이미지 ID로
실행되므로 검토 없이 실행 대상이 바뀌지 않는다.

## 실패 대응

- `MR_JOB_REJECTED`: Job/MR/script/archive/lock 정책 불일치다. 우회하지 말고 MR diff를 확인한다.
- `Network guard service is not active`: Runner를 중지한 채 guard 소유 marker와 정책을 복구한다.
- `image identity differs`: tag가 가리키는 ID가 바뀌었다. 검토 후 위 이미지 갱신 절차를 따른다.
- `log limit exceeded` 또는 timeout: owner와 `ci330-frontend-*` 컨테이너 부재를 확인한다.
- `cleanup could not be verified`: Runner를 재시작하지 말고 rootless Docker daemon과 해당 컨테이너를
  운영자가 확인한다.

서버에서 검증하지 못한 상태라면 격리 Runner를 멈춰 Job을 pending으로 두며 기존
`e106-shell`로 fallback하지 않는다.

## Node 22 및 전체 테스트 정책 적용 (S15P21E106-948)

SSE 테스트 보완(S15P21E106-947)을 먼저 병합한다. 새 CI script는 기존 서버 adapter가
거부하므로 이 MR을 만들기 전에 실행 중인 격리 Job이 끝난 것을 확인하고 Runner를 정지한다.
검토된 이 브랜치의 adapter, frontend boundary, runner, config 네 파일을 위 설치 경로에
반영하고 Node 22.22.0 이미지를 rootless Docker에 준비한다. 기존 설정은
`--confirm-replace --node-image node:22.22.0-alpine`로 교체한 뒤 계약 테스트를 실행한다.
서버 적용을 확인한 후 Runner를 시작하고 MR을 생성한다. 전환 중 이전 script를 사용하는
프론트 MR은 새 adapter에서 거부되므로 최신 develop을 반영한 뒤 재실행한다.

전체 테스트는 고정 Vitest 진입점에 `--maxWorkers=2`를 지정해 CPU 2개 제한에 맞춘다.
테스트 실패 시 build로 진행하지 않는다. 시간·메모리·네트워크·Secret 경계는 유지한다.
서버 적용과 실제 Alpine 컨테이너 검증 전에는 로컬 검증만으로 적용 완료로 판단하지 않는다.
