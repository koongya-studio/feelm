# S15P21E106-222: GitLab MR 전용 어댑터

상태: 코드 및 로컬 검증 단계. Runner 등록, 서버 설치, 토큰 입력은 아직 수행하지 않았다.
기존 `e106-shell` Runner와 develop 배포 설정도 서버에서 변경하지 않았다.

## 목적과 제한

`backend-mr-test` 한 Job만 `e106-mr-isolated` 태그의 별도 Runner에서 받는다. 이 Runner의
Custom executor는 GitLab이 생성한 셸 스크립트를 실행하지 않는다. 다음 조건을 모두 다시
확인한 뒤 현재 커밋의 `backend` 디렉터리만 기존 S15P21E106-222 격리 실행기에 전달한다.

- GitLab 주소가 `https://lab.ssafy.com`이고 프로젝트 경로가
  `s15-bigdata-dist-sub1/S15P21E106`이다.
- 인증된 현재 Job이 `backend-mr-test`, `test`이고 Job 응답의 프로젝트·커밋과 일치한다.
- 같은 프로젝트의 열린 MR 하나가 해당 커밋을 가리키며 대상 브랜치가 `develop`이다.
- CI script가 고정된 두 줄과 일치하고 service, image, cache, dependency, artifact가 없다.
- GitLab archive는 64 MiB 이하의 비압축 tar이며 `backend` 아래 일반 파일·디렉터리만 담는다.

Job token은 Custom executor가 만든 `JOB_RESPONSE_FILE`에서 읽어 root 어댑터의 표준입력으로만
보낸다. GitLab의 현재 Job/MR/archive API 호출 후 폐기하며, 작업 rootfs·Gradle·Docker·로그·
파일명·명령행에는 전달하지 않는다. API가 응답하지 않거나 검증 자료가 모호하면 실행하지 않는다.

GitLab의 Custom executor는 현재 유지보수 모드다. 이 구현은 범용 실행기가 아니라 서버를
추가할 수 없는 현재 프로젝트에서 고정된 백엔드 검사 하나만 연결하는 한정된 브리지다. 다른
Job·프로젝트·테스트 명령을 추가할 때 기존 어댑터를 느슨하게 만들지 말고 별도 검토한다.

## CI 분리

- MR → develop: `backend-mr-test`, `e106-mr-isolated`, 30분, 취소 가능
- develop push: 기존 `backend-test`와 빌드·수동 배포, `e106-shell`
- `runner-check`: develop에서만 실행

MR Job에는 `GIT_STRATEGY: none`을 사용한다. 소스는 Runner checkout 결과가 아니라 인증된
GitLab API의 정확한 커밋 archive를 사용한다. MR이 `.gitlab-ci.yml`을 바꿔 새 태그를 요청해도
어댑터의 고정 Job 정책과 다르면 실패한다.

## 구성 요소

- `ci-gitlab-custom-executor.py`: 비특권 Runner callback. 생성된 셸 파일은 실행하지 않는다.
- `ci-gitlab-job-adapter.py`: root 정책 검증, GitLab 인증·archive 정규화, 안전 실행기 호출.
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
