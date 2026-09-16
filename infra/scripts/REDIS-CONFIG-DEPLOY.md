# Redis 설정 자동 재배포

보호된 main push에서 infra/redis 아래 파일이 변경되면 deploy-redis Job이 실행된다.
Job은 설정을 먼저 검증한 뒤 Redis만 --no-deps --force-recreate로 재생성한다.
redis_data 볼륨은 삭제하지 않는다. 기동 후 인증, 미인증 거부, 읽기 전용 계정의
읽기 허용·쓰기 거부를 검증하고, 실패하면 배포 직전 설정으로 Redis를 다시 생성한다.

서비스 Runner 호스트에는 최초 한 번 다음 디렉터리를 준비한다.

    sudo install -d -o gitlab-runner -g gitlab-runner -m 700 \\
      /var/lib/feelm-deploy/redis-config

설정 사본은 커밋 SHA별
/var/lib/feelm-deploy/redis-config/releases/<revision>에 읽기 전용으로 보관한다.
기존 Redis 컨테이너의 설정 마운트를 자동으로 직전 버전으로 사용하므로 별도 상태 파일이나
Secret 변수는 필요하지 않다. Redis 비밀번호는 기존 Compose 환경 파일을 통해서만 전달하고
배포 스크립트는 값을 출력하지 않는다.

로컬 회귀 테스트:

    python3 -B -m unittest \\
      infra.scripts.test_deploy_redis \\
      infra.scripts.test_ci_backend_develop_paths -v
