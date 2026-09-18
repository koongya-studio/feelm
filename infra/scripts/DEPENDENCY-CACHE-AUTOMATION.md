# S15P21E106-271·294: 백엔드 의존성 캐시 자동화

## 동작 경계

MR이 백엔드 의존성 선언이나 저장소 설정을 변경하면 `backend-cache-prepare`가 격리 Runner의
고정 어댑터에서 자동 실행됩니다. 어댑터는 GitLab이 생성한 셸을 실행하지 않고 인증된 현재 Job,
같은 프로젝트의 열린 develop 대상 MR, 정확한 HEAD SHA와 허용된 변경 파일을 다시 확인합니다.
MR 상세 응답의 기준 SHA와 HEAD SHA를 인증된 Git fetch로 가져와 경로를 비교하며 Job Token이
허용하지 않는 MR diff API를 호출하지 않습니다. 검증 후 다음 단계를 수행합니다.

1. 정확한 SHA의 `backend`와 백엔드 환경 계약이 참조하는
   `infra/compose/service.yml` archive를 받아 SHA256을 기록한다.
2. 기존 전용 계정과 네트워크 guard에서 전체 테스트 및 `spotlessCheck`를 실행해 필요한
   Gradle 의존성을 별도 캐시에 받는다.
3. 선택된 캐시와 manifest를 새 stage로 복사한다.
4. 새 stage를 외부 네트워크가 차단된 격리 환경에서 다시 실행한다.
5. 전체 테스트, `spotlessCheck`, 컨테이너 정리와 증빙 수집이 성공한 후보만 `VERIFIED`로 기록한다.

후보는 MR HEAD SHA별로 분리됩니다. `backend-mr-test`는 MR diff에 실제 의존성 선언 변경이 있으면
자신의 SHA와 MR IID가 일치하는 `VERIFIED` 후보만 선택하고, `build.gradle`의 테스트 작업 설정만
바뀐 MR과 일반 코드 MR은 현재 기본 캐시를 사용합니다. 경로 규칙 때문에 생성된
`backend-cache-prepare`도 실제 의존성 변경이 없으면 성공으로 종료하고 후보를 만들지 않습니다.
후보 생성은 전역 `prepared_base`를 바꾸지 않습니다.

MR이 develop에 병합되어 의존성 파일이 변경되면 보호된 push pipeline의
`backend-cache-promote`가 병합 commit과 정확히 연결된 MR을 다시 찾습니다. 검증된 후보가 하나일
때만 Runner를 중지하고 기본 `prepared_base`를 원자적으로 교체합니다. 재시작 실패 시 이전 설정을
즉시 복구합니다. `backend-cache-rollback`은 운영자가 현재 stage와 승격 기록을 확인한 경우에만
이전 stage로 되돌립니다. 후보, 이전 stage와 증빙은 자동 삭제하지 않습니다.
경로 규칙으로 task-only 변경의 승격 Job이 생성된 경우에도 후보를 요구하지 않고 성공으로
종료하며 현재 기본 캐시를 유지합니다.

자동 준비, MR 테스트, 자동 승격, rollback과 백엔드 배포는
`service-and-backend-cache` resource group을 공유합니다. GitLab 직렬화와 호스트 lock을 함께
사용해 복수 MR의 후보 생성·테스트, 전환과 배포가 서로 덮어쓰이지 않게 합니다. 같은 SHA의 성공
후보를 재시도하면 기존 검증 결과를 재사용하고 다른 MR이나 SHA의 후보는 선택하지 않습니다.

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
sudo python3 <SERVER_STAGE>/scripts/ci-dependency-cache-install.py \
  --confirm-update --operator-username <GitLab 사용자명> \
  --prepared-base <SERVER_STAGE>
```

`<SERVER_STAGE>`는 먼저 `ci-server-manager-pilot.py prepare`와 독립 backend 검증을 통과해야
한다. 설치기는 stage의 backend 런타임 계약과 `prepared_base/scripts`가 정확히 같지 않으면
Runner를 중지하기 전에 거부한다. 검증 성공 시 설치 디렉터리, sudoers와 adapter config의
`prepared_base`를 한 번의 중지 구간에서 함께 교체하고 `PREVIOUS_INSTALL`,
`PREVIOUS_SUDOERS`, `PREVIOUS_CONFIG`를 출력한다.

현재 GitLab 인스턴스에는 Protected Environments 설정이 없습니다. 설치기는 rollback 권한을 가진
GitLab 사용자명만 root 소유 0600 `cache-operators.json`에 기록합니다. 자동 준비는 설치된 Custom
executor가 인증된 MR Job context로만 호출하고, 자동 승격은 보호된 develop push Job과 merge
commit을 다시 확인합니다. 운영자 허용 목록은 자동 준비·승격 권한으로 사용하지 않습니다.
GitLab 버전에 따라 제공되지 않는 Job `source` 속성은 서버 권한 판정에 사용하지 않는다.
pipeline variable의 `GITLAB_USER_*` 값은
권한 판정에 사용하지 않는다. sudoers는 서버에서 확인한 `e106-shell` Runner Job 계정인
`gitlab-runner`에 cache manager 한 명령만 허용한다. 계정이 다르면 임의로 권한을 넓히지 말고
실제 Runner 사용자와 설치 내용을 다시 검토한다.

## 자동 준비와 전환

`backend-cache-prepare`는 `ci-variable-contract-check` 성공을 기다리되 `needs.artifacts: false`와
`dependencies: []`로 선행 Job artifact 전달을 차단합니다. 격리 어댑터는 비어 있지 않은
Job `dependencies`를 계속 거부합니다. 이 CI 설정 수정에는 서버 어댑터 재설치가 필요하지
않습니다. 수정된 YAML을 기능 브랜치에 반영한 뒤 새 MR pipeline을 생성해야 하며, 기존 Job의
Retry만으로는 pipeline 생성 당시의 YAML이 갱신되지 않습니다.

허용된 의존성 파일을 변경한 develop 대상 MR pipeline에서 `backend-cache-prepare`가 자동으로
생성됩니다. 성공 로그에서 다음 값을 확인할 수 있습니다.

- `CACHE_DEPENDENCY_CHANGES`
- `CACHE_CANDIDATE_VERIFIED`
- `CACHE_MANIFEST_SHA256`
- `CANDIDATE_STAGE`

같은 pipeline의 `backend-mr-test`는 준비가 끝난 뒤 해당 SHA 후보를 자동 선택합니다. MR HEAD가
바뀌거나 의존성 파일 변경이 없거나 온라인·오프라인 검사 중 하나라도 실패하면 후보를 선택하지
않습니다. 준비 Job 중단 시 완성되지 않은 후보는 활성 Runner에 연결되지 않습니다.

병합 후 develop push pipeline의 `backend-cache-promote`가 자동 실행됩니다. 성공 로그의
`CACHE_PROMOTED`, `CACHE_MERGED_REVISION`, `PREVIOUS_STAGE`, `CURRENT_STAGE`를 기록합니다. 후보가
없거나 병합 commit과 MR 연결이 모호하거나 오래된 pipeline이면 기존 기본 캐시를 유지하고
실패합니다.

## 복구

전환 후 문제가 확인되면 같은 MR 번호와 전환했던 SHA로 `CACHE_ACTION=rollback` web pipeline을
만들고 `backend-cache-rollback`을 실행한다. 현재 stage가 이미 다른 전환으로 바뀌었으면 오래된
rollback 요청을 거부한다. 자동 재시작 복구까지 실패한 경우 `/etc/feelm-ci222/gitlab-adapter.json`
및 출력된 `PREVIOUS_INSTALL`, `PREVIOUS_SUDOERS`, `PREVIOUS_STAGE`를 기준으로 운영자가 Runner를
중지한 상태에서 복구하고, 설정의 root 소유권·0600 권한과 서비스 active 상태를 확인한다.

## prepare Job 404 복구와 어댑터 갱신

`backend-cache-prepare`가 `MR_ADAPTER_HTTP_ERROR: stage=dependency_diff status=404`로 실패하면
캐시 stage가 아니라 서버에 설치된 root 어댑터가 MR diff API를 호출하는 이전 버전인지 먼저
확인한다. 캐시 `prepare`·`promote`는 Gradle cache와 `prepared_base`만 전환하며
`/usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py`를 갱신하지 않는다.

S15P21E106-314 수정본을 병합한 최신 `develop`에서 새 server stage를 만들고, **최초 적용**의
`ci-dependency-cache-install.py --confirm-update --prepared-base <SERVER_STAGE>` 절차로 설치
스크립트와 검증된 prepared stage를 원자적으로 교체한다. 설치기는 실행 중인 MR test가 있으면
거부하고, MR Runner를 중지한 뒤 기존 설치, sudoers와 config를 `PREVIOUS_INSTALL`,
`PREVIOUS_SUDOERS`, `PREVIOUS_CONFIG`로 보존하며 재시작 실패 시 자동 복구한다.
갱신 후 같은 MR HEAD의 `backend-cache-prepare`를 다시 실행합니다. Git 조회가 실패하면 로그의
`MR_ADAPTER_GIT_ERROR: stage=dependency_git_init|dependency_fetch|dependency_diff`로 실패 단계를
구분하되 원격 URL, 명령 출력과 Job Token은 노출하지 않는다.

commit에 연결된 MR 목록 응답은 GitLab 버전에 따라 `diff_refs`를 생략합니다. 목록 응답의
`iid`·상태·프로젝트·대상 브랜치·HEAD로 MR 연결을 먼저 확인하고, cache prepare Job에서
`merge_requests/:iid` 상세 응답을 추가 조회한다. 상세 응답의 동일 필드와 `diff_refs`의
base·start·HEAD를 다시 검증한 뒤 Git 비교를 시작한다.

## MR !98 검증

MR !98의 현재 HEAD와 `backend/build.gradle` 변경을 다시 확인해 prepare 대상으로 사용한다.
전환 후 같은 HEAD의 `backend-mr-test`가 캐시 누락 없이 시작되고 전체 테스트와
`spotlessCheck`가 성공해야 S15P21E106-271의 실제 검증이 완료된다. 이후 나타나는 코드나 테스트
실패는 캐시 자동화 성공과 구분해 MR !98에서 처리한다.

## 리뷰 순서와 변경 크기

1. `.gitlab-ci.yml`의 자동 준비·승격 Job과 테스트·배포 직렬화
2. `ci-dependency-cache-context.py`의 보호 브랜치·입력 검증
3. `ci-dependency-cache.py`의 MR 재검증, 후보 생성, 오프라인 검증과 원자적 전환
4. 기존 offline input과 server manager에 추가한 동적 revision·SHA 전달
5. 설치 갱신·복구 코드와 테스트·운영 문서

이 변경은 수동 구현량이 큰 편이지만 pipeline만 먼저 병합하면 설치되지 않은 root manager를
호출하고, manager만 먼저 병합하면 승인·직렬화 경계 없이 특권 진입점이 생긴다. 동적 manifest
지원도 후보 stage 검증과 같은 배포 단위여야 하므로 S15P21E106-271의 단일 MR로 함께 리뷰한다.
