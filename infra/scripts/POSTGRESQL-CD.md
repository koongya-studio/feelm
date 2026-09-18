# PostgreSQL 수동 승인형 배포

PostgreSQL은 서비스 데이터를 가진 상태 저장 서비스이므로 보호된 `main` Pipeline에서
수동 승인한 경우에만 재생성한다. 배포는 `postgres` 서비스 하나에만
`up -d --no-deps --force-recreate`를 사용하며 `down`, `down -v`, `docker volume rm` 또는
새 볼륨 생성을 사용하지 않는다.

Merge Request에서는 `infra-config-test`의 PostgreSQL 전용 테스트가 Compose·배포·복구 계약과
스크립트를 격리 Runner에서 검증한다. 병합 후 보호된 `main` push에는 `deploy-postgresql`이 수동 Job으로
표시된다. 팀 승인 후 지정된 Maintainer가 이 Job을 실행하는 행위를 배포 승인으로 간주한다.
`service` 범위의 Protected CI/CD 변수는 보호된 `main` Pipeline에서만 Job에 제공한다.

GitLab 인스턴스가 Protected environments 또는 Deployment approvals를 지원하면 `service`
Environment에 승인 규칙과 배포 권한 제한을 추가할 수 있다. 이 기능은 선택적인 추가 보호 장치이며,
지원하지 않는 인스턴스에서는 수동 Job과 팀 운영 절차만으로 배포한다.

## 서버 사전 준비

배포 Runner가 백업을 보존할 전용 디렉터리를 최초 한 번 준비한다.

    sudo install -d -o gitlab-runner -g gitlab-runner -m 700 \
      /var/lib/feelm-deploy/postgresql/backups
    sudo -u gitlab-runner test -w /var/lib/feelm-deploy/postgresql/backups

기존 `infra/.env`와 `compose_postgres_data` Named Volume은 그대로 사용한다. 배포 Job에
DB 비밀번호나 백업 내용을 Artifact로 올리지 않는다.

## 배포 안전 절차

`deploy-postgresql.sh <main-commit-sha>`는 다음 순서로 동작한다.

1. Compose 설정, 실행 중인 PostgreSQL·Backend 이미지와 데이터 볼륨을 확인한다.
2. 후보 이미지를 pull하고 실행 중 서버와 후보 이미지의 PostgreSQL 메이저 버전이 같은지
   확인한다. 메이저 버전 변경은 별도 migration 승인이 없으면 거부한다.
3. 현재 DB를 custom-format `database.dump`와 `globals.sql`로 백업한다.
4. `CI_COMMIT_BEFORE_SHA`에서 이전 `service.yml`을 추출하고 후보 설정과 함께 보존한다.
5. `pg_restore --list`로 백업을 읽을 수 있는지 확인한다. 실패하면 컨테이너를 건드리지 않는다.
6. PostgreSQL만 재생성하고 기존 Named Volume 이름이 유지됐는지 확인한다.
7. 컨테이너 healthcheck, `SELECT 1`, Backend 컨테이너 healthcheck와 Actuator `UP`을 확인한다.
8. 실패하면 이전 `service.yml`, 직전 이미지와 기존 Named Volume으로 PostgreSQL을 다시 생성해 같은 검증을 수행한다.

백업은 기본적으로 다음 경로에 커밋 SHA별로 남는다.

    /var/lib/feelm-deploy/postgresql/backups/<main-commit-sha>/
      database.dump
      globals.sql
      previous-service.yml
      candidate-service.yml
      manifest.txt

`manifest.txt`에는 이미지와 볼륨 식별자만 기록하며 Secret은 기록하지 않는다.

## 실패 및 수동 복구

자동 rollback까지 실패하면 추가 재생성을 반복하지 않는다. Job 로그에 표시된 백업 경로와
`manifest.txt`를 보존하고 다음을 먼저 확인한다.

    docker inspect feelm-postgres --format '{{json .State.Health}}'
    docker logs --tail 100 feelm-postgres
    docker volume inspect compose_postgres_data
    sudo cat /var/lib/feelm-deploy/postgresql/backups/<revision>/manifest.txt

컨테이너 문제라면 manifest의 `previous_image`와 기존 `compose_postgres_data`를 사용해
PostgreSQL만 다시 생성한다. `down -v`나 볼륨 삭제는 사용하지 않는다. 논리 복원이 필요한
경우에는 새 격리 DB에서 `globals.sql`과 `database.dump` 복원을 먼저 검증한 뒤 운영 DB 적용
여부를 승인받는다. 기존 운영 볼륨에 곧바로 restore하지 않는다.

## 로컬 계약 테스트

    python3 -B -m unittest infra.scripts.test_deploy_postgresql -v
    bash -n infra/scripts/deploy-postgresql.sh
    bash -n infra/scripts/verify-postgresql-deployment.sh
