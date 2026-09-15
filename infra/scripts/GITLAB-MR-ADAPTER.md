# S15P21E106-222: GitLab MR 전용 어댑터

상태: 코드 및 로컬 검증 단계. Runner 등록, 서버 설치, 토큰 입력은 아직 수행하지 않았다.
기존 `e106-shell` Runner와 develop 배포 설정도 서버에서 변경하지 않았다.

## 목적과 제한

`backend-mr-test`, `frontend-mr-test`와 의존성 변경 시 생성되는 `backend-cache-prepare`만
`e106-mr-isolated` 태그의 별도 Runner에서 받습니다. 이 Runner의
Custom executor는 GitLab이 생성한 셸 스크립트를 실행하지 않는다. 다음 조건을 모두 다시
확인한 뒤 현재 커밋에서 Job에 대응하는 `backend` 또는 `frontend` 디렉터리를 검증된 격리
실행기에 전달한다. 백엔드는 환경변수 계약 검사를 위해 `infra/compose/service.yml` 한 파일을
추가로 전달하며 다른 `infra` 파일은 거부한다. 프론트 실행 정책과 서버 적용 순서는
[프론트엔드 MR 격리 검증](FRONTEND-MR-ISOLATION.md)을 따른다.

- GitLab 주소가 `https://lab.ssafy.com`이고 프로젝트 경로가
  `s15-bigdata-dist-sub1/S15P21E106`이다.
- 인증된 현재 Job이 고정된 `backend-mr-test`, `frontend-mr-test` 또는
  `backend-cache-prepare` 정책과 일치한다.
- 같은 프로젝트의 열린 MR 하나가 해당 커밋을 가리키며 대상 브랜치가 `develop`이다.
- CI script가 각 Job의 고정 명령과 일치하고 service, image, cache, dependency, artifact가 없다.
- GitLab archive는 64 MiB 이하의 비압축 tar이며 선택한 영역 아래 일반 파일·디렉터리만 담는다.

Job token은 Custom executor가 만든 `JOB_RESPONSE_FILE`에서 읽어 root 어댑터의 표준입력으로만
보낸다. GitLab의 현재 Job/MR/archive API 호출 후 폐기하며, 작업 rootfs·Gradle·Docker·로그·
파일명·명령행에는 전달하지 않는다. API가 응답하지 않거나 검증 자료가 모호하면 실행하지 않는다.

GitLab의 Custom executor는 현재 유지보수 모드다. 이 구현은 범용 실행기가 아니라 서버를
추가할 수 없는 현재 프로젝트에서 고정된 백엔드 검사 하나만 연결하는 한정된 브리지다. 다른
Job·프로젝트·테스트 명령을 추가할 때 기존 어댑터를 느슨하게 만들지 말고 별도 검토한다.

## CI 분리

- MR → develop: 백엔드 관련 경로 변경 시 `backend-mr-test`, 의존성 파일 변경 시
  `backend-cache-prepare`, `e106-mr-isolated`, 취소 가능
- MR → develop: 프론트엔드 관련 경로 변경 시 `frontend-mr-test`, `e106-mr-isolated`, 취소 가능
- develop push: 기존 `backend-test`와 빌드·수동 배포, `e106-shell`
- `runner-check`: 보호된 develop push에서 공통 CI 연결 설정이나 Runner 설치·서비스·가드
  파일이 바뀌면 자동 실행하고, 그 밖의 변경에는 비차단 수동 Job으로 대기

MR Job에는 `GIT_STRATEGY: none`을 사용한다. 소스는 Runner checkout 결과가 아니라 인증된
GitLab API의 정확한 커밋 archive를 사용한다. MR이 `.gitlab-ci.yml`을 바꿔 새 태그를 요청해도
어댑터의 고정 Job 정책과 다르면 실패한다.

### MR 테스트 실행 경로 (S15P21E106-291)

`.gitlab/ci/backend.yml`의 `backend-mr-test.rules`는 develop 대상 MR 조건과 `changes`를
동시에 만족해야 한다. 경로는 다음 이유로 포함한다.

| 경로 | 포함 이유 |
| --- | --- |
| `backend/**/*` | 소스, 테스트, Gradle 설정·Wrapper 및 테스트 자원 |
| `.gitlab-ci.yml`, `.gitlab/ci/common.yml` | 공통 stage, include 및 Runner 연결 설정 |
| `.gitlab/ci/backend.yml` | 백엔드 Job 명령과 실행 조건 |
| `.gitattributes` | 백엔드 파일의 체크아웃 개행·속성 |
| `infra/compose/service.yml` | 백엔드 환경변수 계약의 실제 Compose 주입 구조 |
| `infra/scripts/ci-*.py` | 격리 실행기, 캐시, 입력 준비 및 GitLab 어댑터 구현 |
| `infra/scripts/rootless-backend/**/*` | Gradle 초기화 및 Testcontainers 실행 보조 코드 |
| `infra/scripts/systemd/feelm-ci*.service` | 테스트 Runner·가드·오프라인 Docker 서비스 정의 |

프론트엔드, 파이프라인 코드, `docs/`, 인프라 Markdown 문서만 변경하면 이 Job은
생성하지 않는다. `backend/README.md`처럼 backend 내부 문서도 보수적으로 포함한다.
삭제·이름 변경도 변경 경로에 해당한다. MR 전체 변경을 대상 브랜치와 비교하므로,
백엔드를 수정한 MR에 문서 커밋만 추가해도 백엔드 테스트는 계속 실행된다.
별도 `compare_to`나 마지막 커밋 비교로 이 기준을 바꾸지 않는다.

공통 CI·Runner 파일 변경 시 Job이 생성되는 것과 해당 파일이 서버에 적용되는 것은
별개다. 어댑터와 입력 경계 변경 자체는 해당 단위 테스트와 기존 서버 적용 절차로 별도
검증해야 한다.

`backend-cache-prepare`의 의존성 경로는 모두 `backend/**/*`에 포함됩니다. 자동 준비,
MR SHA별 후보 선택, develop 병합 후 자동 승격과 Runner 정책을 함께 검증합니다.
변경 경로를 새로 추가할 때는 캐시 관련 변경에서 테스트가 빠지지 않는지 확인한다.

캐시 준비 Job은 현재 Job과 commit에 연결된 열린 MR을 GitLab API로 검증합니다. 이 목록 응답은
GitLab 버전에 따라 `diff_refs`를 생략하므로, 검증한 IID의 MR 상세 응답에서 상태·프로젝트·대상
브랜치·SHA와 `diff_refs`를 다시 확인한다. 그 기준 SHA와 정확한 HEAD SHA를 고정 프로젝트에서
인증된 Git fetch로 가져와 변경 경로를 비교합니다. `backend-mr-test`도 같은 상세 diff를 확인해
의존성 변경 MR이면 정확한 SHA의 검증 후보만 선택합니다.
GitLab Job Token이 허용하지 않는 `merge_requests/:iid/diffs` API에는 의존하지 않는다.
Git 명령은 고정 remote, 비대화형 인증, redirect 차단 환경에서 실행하고 출력은 폐기하거나
크기를 제한한다. 초기화·fetch·diff 실패는 단계 이름만 출력하며 Job Token과 원격 응답은
로그 또는 작업 rootfs에 전달하지 않는다.

실행할 MR Job이 없는 경우의 병합 기준과 검증 결과는
[291 검증 기록](../verification/S15P21E106-291.md)을 참고한다.

### 백엔드 테스트 실행시간 계측 (S15P21E106-412)

백엔드 Gradle 빌드는 `backend/gradle/test-timing.gradle`에서 JVM 시작 이후 전체 시간과
실행된 Gradle task 중 오래 걸린 20개를 기록한다. 테스트 클래스 시간은 Gradle이 생성한
JUnit XML의 `testcase.time`을 합산해 상위 10개만 `BACKEND_TEST_TIMING=` JSON 한 줄로
출력한다. 이 값은 성능 진단용이며 테스트 성공이나 격리 증거로 사용하지 않는다.

develop의 `backend-test`는 Gradle 로그에서 이 줄을 직접 확인한다. 격리된
`backend-mr-test`는 작업 출력 전체를 계속 숨기고, cgroup·컨테이너·임시 파일시스템 정리가
검증된 뒤 root 수집기가 JUnit 건수와 시간 형식, 클래스명, 크기 제한을 재검증한 요약만
외부 Job 로그에 출력한다. 비정상·과대·중복 값은 거부하며 Secret, 테스트 표준 출력 및
테스트 메서드 이름은 공개하지 않는다.

측정할 때는 동일 커밋 또는 같은 테스트 집합에서 각 Job을 3회 실행하고, Runner 대기시간은
제외한 Job 실행시간의 중앙값과 범위를 기록한다. `gradle_seconds`, `slowest_tasks`,
`slowest_classes`를 함께 비교해 컴파일·테스트·Spotless·개별 통합 테스트 중 후속 최적화
대상을 선정한다.

### 테스트 lifecycle 상세 계측 (S15P21E106-420)

격리 Runner 수집기는 Gradle의 클래스별 JUnit XML `testsuite.time`과 각
`testcase.time`을 함께 검증한다. 공개되는 schema version 2 요약은 기존 필드를 유지하면서
다음 값을 추가한다.

- `test_task_seconds`: Gradle `:test` task 전체 시간
- `test_suite_seconds`: 클래스별 JUnit suite 시간 합계
- `test_case_seconds`: 기존 테스트 메서드 시간 합계
- `test_class_lifecycle_seconds`: suite 시간에서 testcase 시간을 뺀 합계. 클래스 fixture,
  Spring Context 및 해당 클래스에 귀속되는 컨테이너·DB 준비/정리 후보 구간이다.
- `test_task_unattributed_seconds`: `:test`에서 suite 합계를 뺀 시간. Test worker 시작·종료,
  discovery, 클래스 사이 대기 및 task 수준 준비/정리 후보 구간이다.
- `slowest_lifecycle_classes`: lifecycle 시간이 긴 클래스 상위 10개

JUnit 시간은 반올림 오차를 제외하고 `suite = testcase + lifecycle` 관계를 만족해야 하며,
task 미귀속 시간도 수집기가 다시 계산해 불일치 값을 거부한다. 구버전 schema version 1은
배포 전환과 기존 증빙 확인을 위해 계속 읽을 수 있다. 모든 값은 성능 진단용이고 테스트 성공,
격리 또는 보안 판정을 완화하지 않는다.

## 구성 요소

- `ci-gitlab-custom-executor.py`: 비특권 Runner callback. 생성된 셸 파일은 실행하지 않는다.
- `ci-gitlab-job-adapter.py`: root 정책 검증, GitLab 인증·archive 정규화, 안전 실행기 호출.
- backend Job은 설치본과 활성 `prepared_base/scripts`의 런타임 계약 fingerprint가 일치할
  때만 GitLab 조회와 격리 작업을 시작하며, 불일치는 장시간 테스트 전에 거부한다.
- `ci-gitlab-server-install.py`: 새 전용 경로만 만드는 token-free 설치기. 기본은 계획 출력이며
  기존 Runner 설정을 수정하거나 Runner를 등록·시작·활성화하지 않는다.
- `ci-job-safety.py`: 동적 archive의 해시와 파일 정책을 root 소유 lease에 묶는다.
- `ci-server-manager-pilot.py`: 동적 MR에서는 테스트 수 증가를 허용하되 핵심 컨테이너 테스트
  5종, 실패/오류/skip 0, Gradle 성공, 컨테이너 정리를 계속 요구한다.
- `systemd/feelm-ci222-gitlab-runner.service`: `feelm-ci-test` 전용 config를 사용하는 별도
  Runner 서비스. 기존 Runner config와 서비스를 공유하지 않고 가드 서비스 뒤에 시작한다.

## 수동 적용 전 확인점

구현 검증이 끝나면 GitLab 프로젝트 Runner를 새로 만든다. 설정은 다음과 같다.

- 설명: `J15E106 MR Isolated Runner`
- 태그: `e106-mr-isolated`
- Run untagged jobs: 끔
- Lock to current project: 켬
- Protected: 끔(MR은 비보호 ref이므로 새 Runner만 끈다)
- Maximum job timeout: 1800초

등록 토큰은 저장소·문서·채팅에 남기지 않고 서비스 서버의 등록 명령에 직접 입력한다. 새
Runner는 `feelm-ci-test` 계정의 별도 config와 서비스로 실행하며 기존 Runner config에 섞지
않는다. root 권한은 아래 **한 명령만** 비밀번호 없이 호출할 수 있게 제한한다.

```text
/usr/bin/python3 -I -B /usr/local/lib/feelm-ci222/gitlab/ci-gitlab-job-adapter.py run
```

root 어댑터 설정 파일 `/etc/feelm-ci222/gitlab-adapter.json`은 root:root 0600으로 만들고
필드를 정확히 세 개만 둔다. `<검증된-stage>`는 서버에서 업로드·준비 검증을 통과한 실제
root 소유 경로로 교체한다.

```json
{
  "gitlab_url": "https://lab.ssafy.com",
  "project_path": "s15-bigdata-dist-sub1/S15P21E106",
  "prepared_base": "/var/tmp/<검증된-stage>"
}
```

Custom executor 설정은 Python callback의 `config`, `prepare`, `run`, `cleanup` action을 각각
연결한다. `run`에만 GitLab Runner가 생성한 script 경로와 stage 이름이 뒤에 붙는다. 실제
설치 명령과 config.toml 변경은 등록 토큰을 받은 다음 서버의 GitLab Runner 버전으로 구문을
검증하고 적용한다.

## 전환 합격 기준

1. 새 Runner를 등록하되 첫 MR을 시작하기 전 기존 `e106-shell` Runner를 Protected로 전환한다.
   새 Runner가 준비되지 않았다면 기존 Runner 보호를 풀지 말고 MR 테스트가 pending인 상태로 둔다.
2. 정상 MR에서 GitLab Job이 Passed이고 핵심 5종을 포함한 전체 테스트가 실패/오류/skip 0이다.
3. 테스트 실패 MR은 Failed이며 기존 운영 Runner로 fallback하지 않는다.
4. 실행 중 취소 시 작업 owner/build/execution unit, mount, loop, 컨테이너가 정리된다.
5. 변조된 CI script·다른 대상 브랜치·추가 service/image/cache/artifact가 실행 전에 거부된다.
6. develop push의 기존 backend-test, 이미지 빌드, 수동 배포 경로가 유지된다.

근거: [GitLab Custom executor](https://docs.gitlab.com/runner/executors/custom/),
[CI job token](https://docs.gitlab.com/ci/jobs/ci_job_token/),
[Jobs API](https://docs.gitlab.com/api/jobs/),
[Repositories API](https://docs.gitlab.com/api/repositories/).
