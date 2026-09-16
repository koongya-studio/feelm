# S15P21E106-222: GitLab MR 전용 어댑터

상태: 코드 및 로컬 검증 단계. Runner 등록, 서버 설치, 토큰 입력은 아직 수행하지 않았다.
기존 `e106-shell` Runner와 develop 배포 설정도 서버에서 변경하지 않았다.

## 목적과 제한

`backend-mr-test`, `frontend-mr-test`, `docs-mr-check`, Pipeline shard 4종,
`consumer-mr-test`와 의존성 변경 시 생성되는 `backend-cache-prepare`만
`e106-mr-isolated` 태그의 별도 Runner에서 받습니다. 이 Runner의
Custom executor는 GitLab이 생성한 셸 스크립트를 실행하지 않는다. 다음 조건을 모두 다시
확인한 뒤 현재 커밋에서 Job에 대응하는 `backend`, `frontend`, 전체 Markdown 또는
`pipeline/spark`, `pipeline/consumer` 디렉터리를
검증된 격리 실행기에 전달한다. 백엔드는 환경변수 계약 검사를 위해
`infra/compose/service.yml` 한 파일을 추가로 전달하며 다른 `infra` 파일은 거부한다.
프론트 실행 정책과 서버 적용 순서는
[프론트엔드 MR 격리 검증](FRONTEND-MR-ISOLATION.md)을 따른다.

- GitLab 주소가 `https://lab.ssafy.com`이고 프로젝트 경로가
  `s15-bigdata-dist-sub1/S15P21E106`이다.
- 인증된 현재 Job이 고정된 `backend-mr-test`, `frontend-mr-test`, `pipeline-unit-test`,
  `pipeline-spark-als-a-d-test`, `pipeline-spark-als-e-z-test`,
  `pipeline-spark-other-test`, `consumer-mr-test`, `docs-mr-check` 또는
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
- MR → develop: 모든 변경에서 `docs-mr-check`, `e106-mr-isolated`, 취소 가능. 문서와 전체
  추적 경로 manifest만 전달해 내부 링크와 링크 대상 삭제·이동을 검사하고 최소 Pipeline
  Job을 보장한다. 상세 경계는 [문서 MR 검증](DOCS-MR-CHECK.md)을 따른다.
- MR → develop: `pipeline/spark` 실행 코드·테스트·lock 변경 시 `pipeline-unit-test`는
  자동 실행하고 세 `pipeline-spark-*-test`는 필수 수동 Job으로 생성한다. 세 수동 Job을
  모두 실행해 성공하기 전에는 파이프라인이 성공하지 않는다. 모든 Job은
  `e106-mr-isolated`에서 취소 가능하며, 각 shard는 고유 resource group과 15분 timeout을
  사용한다. 단일 실행 슬롯에서는 Runner가 비는 시점에 담당자가 세 Spark Job을 시작한다.
  서로 다른 shard의 실제 병렬 실행은 둘 이상의 Runner 실행 슬롯이 준비된 경우에만 가능하다.
- MR → develop: `pipeline/consumer` 변경 시 `consumer-mr-test`를 자동 실행한다. Spark Job은
  생성하지 않으며 고정 Python 3.12/uv 이미지에서 Ruff와 pytest만 실행한다.
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

### Pipeline MR 실행 경계 (S15P21E106-352)

`.gitlab/ci/pipeline.yml`은 Spark Python 코드·테스트·`pyproject.toml`·`uv.lock`·
`.python-version` 및 Pipeline CI 실행기 변경에만 반응한다. `pipeline/docs`와
`pipeline/consumer`만 변경한 MR에는 생성되지 않는다. 표시되는 CI script는 개발자가 로컬에서
재현할 명령이며, Custom executor는 이를 직접 실행하지 않고 정확히 일치하는지 확인한 뒤 서버에
설치된 고정 정책을 실행한다. 빠른 `pipeline-unit-test`는 자동 실행하고, 세 Spark shard는
`when: manual`, `allow_failure: false`인 필수 수동 Job이다. MR 담당자는 공용 Runner가 비는
시점에 세 Job을 모두 실행해야 하며, 미실행 또는 실패 상태에서는 파이프라인 성공과 병합이
차단된다. Job 이름과 script가 그대로이므로 이 실행 정책 변경에는 서버 어댑터 재배포가 필요 없다.

어댑터는 인증된 커밋에서 `pipeline/spark`만 다시 archive하고, 실행 파일·링크·`.env*`·가상환경과
범위 밖 경로를 거부한다. Python 3.10.12, PySpark 4.1.3, pytest 8.4.2, ruff 0.12.12와
검토된 psycopg 3.2.10, PyTorch 2.6.0 CPU, Transformers 4.48.3만 허용한다. uv lock은
PyPI와 `https://download.pytorch.org/whl/cpu` registry, `files.pythonhosted.org/packages/`와
`download-r2.pytorch.org/whl/cpu/`의 해시가 있는 패키지만 허용한다. 실행기는 digest로 고정한 전용
Python/Java 이미지와 root 소유 설정에 고정된 의존성 캐시를 사용한다. 컨테이너는
`--network=none`, read-only rootfs, capability 제거, CPU·메모리·PID·시간 제한으로 실행한다.
컨테이너 내부 UID 0은 `/work`와 `/tmp` tmpfs에만 쓰며 Rootless Docker 사용자 네임스페이스의
`feelm-ci-test`로 매핑된다. 호스트 root 권한, host socket 또는 host 경로 쓰기는 제공하지 않는다.
고정 uv 캐시는 소유권·모드·시간을 보존하지 않고 4 GiB `/tmp`에 복사한 뒤 사용자 쓰기 권한만
부여한다. 현재 2.5 GiB 캐시를 수용하되 캐시 증가가 상한을 넘으면 실행 전에 실패한다.
캐시 생성·이미지 적재는 MR Job에서 수행하지 않는다.

운영 적용 전 `/var/lib/feelm-ci222/pipeline-cache/<version>`에 offline uv 캐시를 준비하고,
동일 디렉터리의 `.feelm-cache-sha256`에 검토한 64자리 digest를 기록한다. 그 다음
`ci-pipeline-mr-config.py`의 기본 plan 출력을 확인하고 `--confirm-install` 또는
`--confirm-replace`와 캐시 경로·digest를 명시해 root:root 0600 설정을 만든다. 저장소 변경만으로
서버 설정이나 Runner가 바뀌지는 않는다. 이미지/캐시 준비와 실제 정상·실패·취소 MR 검증은
운영자 검토가 필요한 후속 적용 단계다.

서비스 서버에 Pipeline 전용 이미지·캐시·설정이 아직 없을 때는 설치된 스크립트의 계획을 먼저
확인한 뒤, 서버의 검토된 저장소 경로를 명시해 준비한다. 이 작업은 운영 Spark 컨테이너를
재생성하지 않는다. SHA-256으로 고정한 uv와 NumPy 입력만 내려받고, 이미 로컬에 존재하는 digest
고정 Apache Spark 베이스에서 별도 CI 이미지를 network-none 빌드한다. 같은 이름의 이미지·캐시나
기존 설정은 덮어쓰지 않는다.
SSH 중단 등으로 이미지 전달 뒤 준비가 종료된 경우 운영 Docker와 Rootless Docker의 이미지 ID가
정확히 같을 때만 해당 이미지를 재사용해 캐시 단계부터 재개한다. 한쪽에만 있거나 ID가 다르면
자동 삭제·교체하지 않고 운영자 검토를 요구한다.

```bash
sudo python3 /usr/local/lib/feelm-ci222/gitlab/ci-pipeline-mr-prepare.py

sudo python3 /usr/local/lib/feelm-ci222/gitlab/ci-pipeline-mr-prepare.py \
  --source-root /path/to/reviewed/S15P21E106 \
  --confirm-prepare
```

준비 중 의존성 다운로드는 Rootless Docker의 격리 계정에서 한 번만 허용하고, 같은 캐시로
`--network=none --offline` 재검증에 성공해야 최종 root 소유 read-only 캐시와 설정을 설치한다.
uv가 중복 제거용으로 만든 캐시 내부 링크는 cache root 안의 일반 파일·디렉터리만 가리키는지
검증한 뒤 실제 항목으로 복제하며, 최종 캐시에는 링크를 남기지 않는다.
성공 출력의 이미지 ID·캐시 경로·digest를 보존하고 실패한 Pipeline shard Job을 재시도한다.

### Consumer MR 실행 경계 (S15P21E106-486)

`.gitlab/ci/consumer.yml`은 Consumer 코드·테스트·lock·Dockerfile과 검토된 공통 adapter 변경에
반응한다. 어댑터는 인증된 커밋에서 `pipeline/consumer`만 archive하고, 고정 Python 3.12/uv
이미지에서 `uv sync --frozen`, Ruff, pytest를 실행한다. 네트워크·host Docker socket·Secret·
임의 CI 명령은 전달하지 않는다. 세부 정책과 서버 적용 순서는 [Consumer CI policy](CONSUMER-CI.md)를
따른다. 보호된 `main` push에서는 Dockerfile `test` target이 먼저 성공해야 immutable SHA 태그
이미지를 게시하며 배포는 수행하지 않는다.

공통 CI·Runner 파일 변경 시 Job이 생성되는 것과 해당 파일이 서버에 적용되는 것은
별개다. 어댑터는 Job별 archive만 전달하므로, 실행기 변경 자체는 해당 단위 테스트와
기존 서버 적용 절차로 별도 검증해야 한다.

기존 backend `prepared_base`와 cache를 바꾸지 않고 검토된 adapter script만 갱신할 때는
`ci-server-pilot-stage.py`가 만든 새 stage를 사용한다. 업데이터는 업로드 manifest와 root 소유
불변 파일을 다시 검증하고, 실행 중인 MR unit이 없는 경우에만 Runner를 정지한다. 기존
`gitlab-adapter.json`, `cache-operators.json`, sudoers와 `prepared_base`는 보존하며 설치 디렉터리만
원자 교체한다. cache prepare/promote/rollback과 같은 host lock을 갱신 전체에서 유지하고, 교체
전·후 adapter import smoke를 통과해야 한다. Runner stop, adapter smoke, 재시작 또는 active 확인
실패 시 Runner를 inactive로 확인한 뒤 이전 설치를 복구하고, 원래 active 상태까지 검증한다.
Runner 격리나 복구 재시작을 확인하지 못하면 성공으로 처리하지 않는다.

```bash
sudo python3 <SERVER_STAGE>/scripts/ci-gitlab-adapter-update.py
sudo python3 <SERVER_STAGE>/scripts/ci-gitlab-adapter-update.py \
  --stage <SERVER_STAGE> --confirm-update
```

성공 출력의 `PREVIOUS_INSTALL`은 실환경 검증이 끝날 때까지 삭제하지 않는다. 이 경로는 Pipeline
이미지나 offline cache를 만들지 않으며 `/etc/feelm-ci222/pipeline-mr.json`도 변경하지 않는다.

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

과거 실행할 MR Job이 없던 상태의 확인 결과는 [291 검증 기록](../verification/S15P21E106-291.md)을
참고한다. `S15P21E106-296`부터 모든 develop 대상 MR에 `docs-mr-check`가 생성된다.

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
- `check-markdown.py`: 네트워크와 외부 패키지 없이 전체 추적 Markdown의 내부 링크·충돌 흔적 검사.
- `ci-docs-mr-boundary.py`: Markdown 전체와 추적 경로 manifest만 허용하는 입력 경계.
- `ci-docs-mr-runner.py`: 설치된 고정 검사기를 비특권·private-network systemd unit에서 실행.
- backend Job은 설치본과 활성 `prepared_base/scripts`의 런타임 계약 fingerprint가 일치할
  때만 GitLab 조회와 격리 작업을 시작하며, 불일치는 장시간 테스트 전에 거부한다.
- `ci-pipeline-mr-boundary.py`: `pipeline/spark` 입력과 Python/uv lock 정책 검증.
- `ci-pipeline-mr-runner.py`: Job 이름으로 검증된 단일 테스트 group의 고정 offline uv 검사와
  Spark 테스트 격리 실행·정리.
- `ci-pipeline-mr-config.py`: 검토된 이미지 digest와 read-only 의존성 캐시 identity 고정.
- `ci-pipeline-mr-prepare.py`: 고정 CI 이미지, offline uv 캐시와 최초 설정을 준비·재검증.
- `ci-consumer-mr-boundary.py`: `pipeline/consumer` 입력과 Python/uv lock 및 Dockerfile identity 검증.
- `ci-consumer-mr-runner.py`: 고정 Consumer 테스트 명령을 제한된 Rootless Docker 컨테이너에서 실행·정리.
- `ci-consumer-mr-config.py`: 검토된 Consumer CI 이미지 ID를 root 소유 설정에 고정.
- `ci-consumer-mr-prepare.py`: digest 고정 입력으로 Consumer CI 이미지를 network-none 빌드·전달·검증.
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
