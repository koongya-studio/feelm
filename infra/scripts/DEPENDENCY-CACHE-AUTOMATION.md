# S15P21E106-271: 백엔드 의존성 캐시 자동화

## 동작 경계

MR이 백엔드 의존성 선언이나 저장소 설정을 변경하면 `backend-cache-status`가 격리 Runner의
고정 어댑터에서 MR 번호, 정확한 commit SHA와 변경 파일을 출력한다. 이 Job은 MR의 셸이나
Gradle 코드를 실행하지 않는다. 기존 `backend-mr-test`는 캐시가 충분하면 그대로 실행되고,
부족하면 안전하게 실패한다.

캐시 갱신은 보호된 `develop`의 web pipeline에서만 실행한다. `backend-cache-prepare`는 입력한
MR과 SHA가 현재 열린 MR HEAD인지 GitLab API로 다시 확인하고, 다음 단계를 순서대로 수행한다.

1. 정확한 SHA의 `backend` archive를 받아 SHA256을 기록한다.
2. 기존 전용 계정과 네트워크 guard에서 전체 테스트 및 `spotlessCheck`를 실행해 필요한
   Gradle 의존성을 별도 캐시에 받는다.
3. 선택된 캐시와 manifest를 새 stage로 복사한다.
4. 새 stage를 외부 네트워크가 차단된 격리 환경에서 다시 실행한다.
5. 전체 테스트, `spotlessCheck`, 컨테이너 정리와 증빙 수집이 성공한 후보만 `VERIFIED`로 기록한다.

`backend-cache-promote`는 같은 MR과 SHA를 다시 확인한다. 실행 중인 격리 작업이 없을 때 Runner를
중지하고 `/etc/feelm-ci222/gitlab-adapter.json`을 원자적으로 교체한 뒤 Runner의 active 상태를
확인한다. 재시작 실패 시 이전 설정을 즉시 복구한다. `backend-cache-rollback`은 현재 stage가
해당 전환 기록과 일치할 때만 기록된 이전 stage로 되돌린다. 후보, 이전 stage와 증빙은 자동
삭제하지 않는다.

세 작업과 백엔드 배포는 `service-and-backend-cache` resource group을 공유한다. 이 직렬화와
호스트 lock을 함께 사용해 갱신·전환·배포 및 중복 갱신이 겹치지 않게 한다.

## 최초 적용

자동화 MR을 병합한 뒤 최신 `develop`에서 서버 stage를 생성한다. 이 단계는 새 stage만 만들며
현재 Runner 설정을 바꾸지 않는다.

```text
python infra/scripts/ci-server-pilot-stage.py --confirm-server-stage
```

출력된 `SERVER_STAGE`에서 업데이트 계획을 확인한 뒤 서버 운영자가 적용한다. 업데이트는 기존
설치와 sudoers를 별도 `previous-*` 경로에 보존하고 MR Runner만 재시작한다.

```text
sudo python3 <SERVER_STAGE>/scripts/ci-dependency-cache-install.py
sudo python3 <SERVER_STAGE>/scripts/ci-dependency-cache-install.py --confirm-update
```

GitLab의 `dependency-cache` environment를 Protected로 설정하고 인프라 담당자만 deploy 권한을
갖게 한다. 이 프로젝트 설정 전에는 수동 캐시 Job을 실행하지 않는다. sudoers는 현재 서버의
`e106-shell` Runner 계정인 `ubuntu`에 cache manager 한 명령만 허용한다. 계정이 다르면 임의로
권한을 넓히지 말고 실제 Runner 사용자와 설치 내용을 다시 검토한다.

## 준비와 전환

GitLab의 **Build > Pipelines > New pipeline**에서 branch를 `develop`으로 선택하고 다음 변수를
입력한다. 짧은 SHA나 이전 pipeline SHA를 사용하지 않는다.

```text
CACHE_ACTION=prepare
CACHE_MR_IID=<MR 번호>
CACHE_COMMIT_SHA=<현재 MR HEAD 40자리 SHA>
```

생성된 pipeline에서 `backend-cache-prepare`를 인프라 담당자가 실행한다. 성공 로그에서 다음 값을
검토한다.

- `CACHE_DEPENDENCY_CHANGES`
- `CACHE_CANDIDATE_VERIFIED`
- `CACHE_MANIFEST_SHA256`
- `CANDIDATE_STAGE`

준비가 성공한 뒤 새 web pipeline을 같은 MR과 SHA로 만들고 `CACHE_ACTION=promote`를 지정한다.
`backend-cache-promote` 성공 로그의 `PREVIOUS_STAGE`와 `CURRENT_STAGE`를 기록한 뒤 대상 MR의
`backend-mr-test`를 재실행한다.

MR HEAD가 바뀌거나, 의존성 파일 변경이 없거나, 온라인·오프라인 검사 중 하나라도 실패하거나,
실행 중인 MR 테스트가 있으면 전환하지 않는다. 준비 Job 중단 시 완성되지 않은 후보는 활성
Runner에 연결되지 않는다.

## 복구

전환 후 문제가 확인되면 같은 MR 번호와 전환했던 SHA로 `CACHE_ACTION=rollback` web pipeline을
만들고 `backend-cache-rollback`을 실행한다. 현재 stage가 이미 다른 전환으로 바뀌었으면 오래된
rollback 요청을 거부한다. 자동 재시작 복구까지 실패한 경우 `/etc/feelm-ci222/gitlab-adapter.json`
및 출력된 `PREVIOUS_INSTALL`, `PREVIOUS_SUDOERS`, `PREVIOUS_STAGE`를 기준으로 운영자가 Runner를
중지한 상태에서 복구하고, 설정의 root 소유권·0600 권한과 서비스 active 상태를 확인한다.

## MR !98 검증

MR !98의 현재 HEAD와 `backend/build.gradle` 변경을 다시 확인해 prepare 대상으로 사용한다.
전환 후 같은 HEAD의 `backend-mr-test`가 캐시 누락 없이 시작되고 전체 테스트와
`spotlessCheck`가 성공해야 S15P21E106-271의 실제 검증이 완료된다. 이후 나타나는 코드나 테스트
실패는 캐시 자동화 성공과 구분해 MR !98에서 처리한다.

## 리뷰 순서와 변경 크기

1. `.gitlab-ci.yml`의 감지 Job, 보호된 수동 Job과 배포 직렬화
2. `ci-dependency-cache-context.py`의 보호 브랜치·입력 검증
3. `ci-dependency-cache.py`의 MR 재검증, 후보 생성, 오프라인 검증과 원자적 전환
4. 기존 offline input과 server manager에 추가한 동적 revision·SHA 전달
5. 설치 갱신·복구 코드와 테스트·운영 문서

이 변경은 수동 구현량이 큰 편이지만 pipeline만 먼저 병합하면 설치되지 않은 root manager를
호출하고, manager만 먼저 병합하면 승인·직렬화 경계 없이 특권 진입점이 생긴다. 동적 manifest
지원도 후보 stage 검증과 같은 배포 단위여야 하므로 S15P21E106-271의 단일 MR로 함께 리뷰한다.
