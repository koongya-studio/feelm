# Redis 설정 자동 재배포

보호된 main push에서 infra/redis 아래 파일이 변경되면 deploy-redis Job이 실행된다.
Job은 설정을 먼저 검증한 뒤 Redis만 --no-deps --force-recreate로 재생성한다.
redis_data 볼륨은 삭제하지 않는다. 기동 후 인증, 미인증 거부, 읽기 전용 계정의
읽기 허용·쓰기 거부를 검증하고, 실패하면 배포 직전 설정으로 Redis를 다시 생성한다.

서비스 Runner 호스트에는 최초 한 번 다음 디렉터리를 준비한다.

    sudo install -d -o gitlab-runner -g gitlab-runner -m 700 \\
      /var/lib/feelm-deploy/redis-config

준비 결과는 배포 전에 다음처럼 확인한다.

    sudo stat -c '%U %G %a %n' /var/lib/feelm-deploy/redis-config
    sudo -u gitlab-runner test -w /var/lib/feelm-deploy/redis-config

출력의 소유자·그룹·권한은 각각 `gitlab-runner`, `gitlab-runner`, `700`이어야 한다.

## 설정 변경 없이 다시 배포하기

실패한 main 배포 뒤 브랜치가 새 커밋으로 이동하면 이전 Job은
`ci-deploy-current-commit.py`가 거부한다. Redis 설정을 의미 없이 수정하거나 이 보호 절차를
우회하지 말고, 새 Jira 업무와 최신 develop 기반 브랜치에서
`infra/redis/deploy-trigger.txt`의 `token`만 새 Jira Key와 재배포 사유로 갱신한다.

트리거 파일은 `infra/redis/**/*` 변경 규칙에 포함되어 보호된 main push에서
`datastore-config-check`와 `deploy-redis`를 다시 실행한다. `deploy-redis.sh`는
`start.sh`와 `readonly.acl`만 release에 복사하므로 트리거 파일은 Redis 컨테이너 설정이나
Secret에 포함되지 않는다. 토큰 변경 이력은 Git과 Jira에 남으며, 같은 값으로 빈 커밋을
만들거나 main에 직접 Push하지 않는다.

배포가 끝나면 Job 성공과 현재 마운트를 확인한다.

    docker inspect --format \
      '{{range .Mounts}}{{if eq .Destination "/usr/local/etc/redis/start.sh"}}{{.Source}}{{end}}{{end}}' \
      feelm-redis

결과는 `/var/lib/feelm-deploy/redis-config/releases/<main-commit-sha>/start.sh`여야 한다.

설정 사본은 커밋 SHA별
/var/lib/feelm-deploy/redis-config/releases/<revision>에 읽기 전용으로 보관한다.
기존 Redis 컨테이너의 설정 마운트를 자동으로 직전 버전으로 사용하므로 별도 상태 파일이나
Secret 변수는 필요하지 않다. Redis 비밀번호는 기존 Compose 환경 파일을 통해서만 전달하고
배포 스크립트는 값을 출력하지 않는다.

최초 자동 재배포처럼 실행 중인 컨테이너의 bind-mount 원본 경로가 더 이상 존재하지 않으면,
배포 스크립트는 컨테이너에서 `start.sh`와 `readonly.acl`을
`releases/bootstrap-<revision>`으로 복사해 rollback 기준점으로 사용한다. Redis 데이터와
실행 중인 Secret은 이 스냅샷에 포함하지 않는다.

로컬 회귀 테스트:

    python3 -B -m unittest \\
      infra.scripts.test_deploy_redis \\
      infra.scripts.test_ci_backend_develop_paths -v
