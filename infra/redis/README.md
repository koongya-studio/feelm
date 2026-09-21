# Redis 제한 계정 접속 설정 — S15P21E106-225, S15P21E106-642

## 설정과 영향

- 기존 `REDIS_PASSWORD`로 인증하는 `default` 계정과 백엔드 연결을 유지한다.
- 선택 Secret `REDIS_READONLY_PASSWORD`는 Redis 컨테이너에만 주입한다.
  비어 있으면 조회 계정은 비활성화한다. 기존 비밀번호와 같은 값이면 기동을 거부한다.
- 선택 Secret `REDIS_LOCAL_BACKEND_PASSWORD`는 SSH 터널로 접속하는 로컬 Spring Boot의
  `feelm_local_backend` 계정에 사용한다. 비어 있으면 계정은 비활성화하며 다른 Redis
  계정의 비밀번호와 같으면 기동을 거부한다.
- `start.sh`가 시작마다 세 계정의 ACL을 생성한다. 비밀번호는 SHA-256 해시로만
  컨테이너 임시 파일에 기록하며 파일은 Redis 실행 사용자만 읽는다.
- 계정 유지의 원본은 환경변수와 버전 관리된 ACL이다. 수동 `ACL SETUSER` 변경은
  재기동 시 사라진다. AOF·볼륨·메모리 한도·축출 정책은 기존 설정을 유지한다.
- 서버에도 `127.0.0.1:6379`만 게시한다. 기존 로컬 오버레이와 동일한 포트라 병합 시 중복되지 않는다.

현재 초안은 추천 계약의 `vector`, `candidates`, `candidate-version`, `behavior`, `model:current`와
급상승 스냅샷 `feelm:trending:current`·`feelm:trending:last-good`(API-064)만 읽는다.
급상승 집계 버킷(`feelm:trending:bucket:*`)은 회원 ID 집합을 담고 Spring도 읽지 않으므로
열지 않는다(스트리밍 계약 5.1). Redis Insight가 String 값을 표시할 때 사용하는 `GETRANGE`를
포함해 읽기 명령을 개별 허용하며 쓰기·삭제·관리·스크립트·Pub/Sub 명령은 허용하지 않는다.
로그인 키의 값과 위 목록 밖의 키는 조회할 수 없다.

Redis Insight 연결에 필요한 서버 상태 조회 `INFO`와 키 목록 조회 `SCAN`은 허용한다. Redis의 `SCAN`은
ACL 키 패턴으로 결과를 걸러주지 않으므로 로그인 키 이름과 키에 포함된 사용자 ID도 목록에
표시될 수 있다. 로그인 키의 값은 읽을 수 없으며 `KEYS`, `RANDOMKEY`는 허용하지 않는다.
Redis Insight 화면의 검색 필터는 보안 경계가 아니다. 운영 전환 시 목록 권한을 다시 검토한다.

`feelm_local_backend`는 Spring Boot가 사용하는 `auth:*` 키에만 읽기·쓰기를 허용하고,
개인·파티 추천 후보와 vector, 모델 포인터, 급상승 스냅샷 두 키 등 `local-backend.acl`에
열거한 키는 읽기만 허용한다. Refresh Token 회전과 메일 제한에 필요한 String·Set·Hash·TTL·Lua 명령만
개별 허용한다. 임의 키, 추천 키 쓰기, `SCAN`, `INFO`, `KEYS`, Pub/Sub 및 Redis 관리
명령은 허용하지 않는다. 백엔드의 Redis 키 또는 명령 계약이 바뀌면 같은 작업에서 ACL과
회귀 테스트를 함께 갱신해야 한다.

## 적용 순서

1. 권한 정책과 로컬 검증을 완료한다. 기존 백엔드 인증 및 데이터에 대한 영향도 확인한다.
2. 승인된 환경 설정 저장소에 별도 조회 비밀번호를 추가한다. 현재 개발 서버는 기존
   `infra/.env` 주입 경로를 사용하며 파일 권한은 소유자만 읽도록 유지한다.
   비밀번호와 해시는 Jira·Notion·Git·명령 출력에 기록하지 않는다.
   로컬 백엔드 접속도 사용할 경우 `REDIS_LOCAL_BACKEND_PASSWORD`를 서로 다른 값으로
   추가한다. 자동 Redis 배포의 런타임 검증은 이 값을 요구하므로 코드 배포 전에 서버
   환경에 먼저 등록해야 하며, 누락되면 후보 설정을 실패 처리하고 이전 설정으로 롤백한다.
3. 서버에서 확정된 브랜치와 동일 Compose 프로젝트·볼륨을 사용한다.
   `BACKEND_IMAGE`가 환경 파일에 없으면 현재 백엔드 이미지로 설정한다.

```bash
export BACKEND_IMAGE="$(docker inspect feelm-backend --format '{{.Config.Image}}')"
docker compose -p compose --env-file infra/.env -f infra/compose/service.yml config --quiet
docker compose -p compose --env-file infra/.env -f infra/compose/service.yml up -d --no-deps redis
docker port feelm-redis 6379
docker ps --filter name=feelm-redis --format '{{.Names}} {{.Status}}'
```

재생성 중 연결이 잠시 끊긴다. `down -v`는 사용하지 않는다. 백엔드 배포만으로
Redis 설정이 자동 적용되지 않는다. 포트는 `127.0.0.1:6379`, 상태는 healthy인지 확인한다.

## 검증

- 기존 비밀번호로 인증 성공, 비인증 연결 거부, 기존 백엔드 상태 정상.
- 조회 계정으로 추천 String·Hash와 TTL 조회 성공.
- 로그인 키 조회, 추천 키 변경·삭제, `FLUSHALL`, `CONFIG`, `ACL`, `EVAL` 거부.
- 로컬 백엔드 계정으로 `auth:*` 읽기·쓰기와 Lua 실행, 승인된 추천 키 조회 성공.
- 로컬 백엔드 계정으로 추천 키 쓰기, 임의 키 접근, `FLUSHALL`, `CONFIG`, `ACL` 거부.
- 조회·로컬 백엔드 계정으로 급상승 스냅샷 두 키 조회 성공, 같은 키 쓰기와
  집계 버킷 키 접근 거부(`verify-datastore-config.sh`가 확인한다).
- 확정된 키 목록 노출 정책과 Redis Insight의 실제 동작 일치.
- 재시작·컨테이너 재생성 후 데이터 및 계정 유지.
- 실제 추천 데이터가 아직 없으면 분리된 테스트 Redis에서 더미 데이터로 검증한다.
  서버에서 테스트 데이터 생성·삭제는 별도 합의 없이 하지 않는다.

## 회수·롤백

조회 비밀번호를 비우고 Redis만 재생성하면 조회 계정은 비활성화되고 기존 연결도 끊어진다.
교체 시에는 새 비밀번호로 Redis만 재생성하고 기존 비밀번호의 인증 거부를 확인한다.
설정 롤백은 이전 Compose의 Redis 설정으로 되돌린 뒤 같은 프로젝트·볼륨으로 Redis만
재생성한다. 기존 `REDIS_PASSWORD`와 데이터 볼륨을 유지하고 인증·백엔드 상태를 재확인한다.

## 로컬 Spring Boot 접속

Redis는 서버 루프백에만 바인딩하므로 개발 PC에서 SSH 터널을 유지한다.

```bash
ssh -N -L 16379:127.0.0.1:6379 <SSH_USER>@<SERVICE_SERVER>
```

`backend/.env`에는 실제 Secret을 커밋하지 않고 다음 변수만 개인 값으로 설정한다.

```dotenv
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
REDIS_USERNAME=feelm_local_backend
REDIS_PASSWORD=<전달받은 비밀번호>
```

로컬 백엔드는 서버의 실제 `auth:*` 키를 변경한다. 공용 계정의 세션·로그인 제한을
시험하지 말고 합의된 테스트 계정만 사용한다. 추천 데이터는 조회만 가능하다.

## 검증 상태

서버 초기 상태는 사용자 출력으로 `default` 계정만 있고 `aclfile`은 미설정임을 확인했다.
로컬에서는 사용자가 다음 항목을 검증했다.

- Compose 설정 확인과 Redis healthy·루프백 바인딩
- 기존 관리 계정 및 조회 계정 인증
- 전체 키 이름 목록 조회
- 허용된 추천 키 값 조회와 로그인 키 값 조회 거부
- Redis Insight에서 추천 String 값 표시와 로그인 키 값 접근 차단
- 추천 키 수정·삭제 거부
- 컨테이너 재시작 후 조회 계정과 데이터 유지
- 검증용 추천·로그인 키 삭제 및 삭제 결과 확인

사용자가 제공한 명령 결과를 기준으로 기록했으며 AI가 Docker에 직접 접속한 결과는 아니다.
서버에서는 Redis healthy·루프백 바인딩, 조회 계정 생성, Redis Insight SSH 연결과
추천 값 조회, 조회 계정의 쓰기 거부와 기존 백엔드 health `UP`을 검증했다.
