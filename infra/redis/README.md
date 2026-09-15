# Redis 조회 접속 설정 — S15P21E106-225

## 설정과 영향

- 기존 `REDIS_PASSWORD`로 인증하는 `default` 계정과 백엔드 연결을 유지한다.
- 선택 Secret `REDIS_READONLY_PASSWORD`는 Redis 컨테이너에만 주입한다.
  비어 있으면 조회 계정은 비활성화한다. 기존 비밀번호와 같은 값이면 기동을 거부한다.
- `start.sh`가 시작마다 두 계정의 ACL을 생성한다. 비밀번호는 SHA-256 해시로만
  컨테이너 임시 파일에 기록하며 파일은 Redis 실행 사용자만 읽는다.
- 계정 유지의 원본은 환경변수와 버전 관리된 ACL이다. 수동 `ACL SETUSER` 변경은
  재기동 시 사라진다. AOF·볼륨·메모리 한도·축출 정책은 기존 설정을 유지한다.
- 서버에도 `127.0.0.1:6379`만 게시한다. 기존 로컬 오버레이와 동일한 포트라 병합 시 중복되지 않는다.

현재 초안은 추천 계약의 `vector`, `candidates`, `topn`, `behavior`, `model:current`만
읽는다. Redis Insight가 String 값을 표시할 때 사용하는 `GETRANGE`를 포함해 읽기 명령을
개별 허용하며 쓰기·삭제·관리·스크립트·Pub/Sub 명령은 허용하지 않는다.
로그인 키의 값과 추천 계약 밖의 키는 조회할 수 없다.

Redis Insight 연결에 필요한 서버 상태 조회 `INFO`와 키 목록 조회 `SCAN`은 허용한다. Redis의 `SCAN`은
ACL 키 패턴으로 결과를 걸러주지 않으므로 로그인 키 이름과 키에 포함된 사용자 ID도 목록에
표시될 수 있다. 로그인 키의 값은 읽을 수 없으며 `KEYS`, `RANDOMKEY`는 허용하지 않는다.
Redis Insight 화면의 검색 필터는 보안 경계가 아니다. 운영 전환 시 목록 권한을 다시 검토한다.

## 적용 순서

1. 권한 정책과 로컬 검증을 완료한다. 기존 백엔드 인증 및 데이터에 대한 영향도 확인한다.
2. 승인된 환경 설정 저장소에 별도 조회 비밀번호를 추가한다. 현재 개발 서버는 기존
   `infra/.env` 주입 경로를 사용하며 파일 권한은 소유자만 읽도록 유지한다.
   비밀번호와 해시는 Jira·Notion·Git·명령 출력에 기록하지 않는다.
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
- 확정된 키 목록 노출 정책과 Redis Insight의 실제 동작 일치.
- 재시작·컨테이너 재생성 후 데이터 및 계정 유지.
- 실제 추천 데이터가 아직 없으면 분리된 테스트 Redis에서 더미 데이터로 검증한다.
  서버에서 테스트 데이터 생성·삭제는 별도 합의 없이 하지 않는다.

## 회수·롤백

조회 비밀번호를 비우고 Redis만 재생성하면 조회 계정은 비활성화되고 기존 연결도 끊어진다.
교체 시에는 새 비밀번호로 Redis만 재생성하고 기존 비밀번호의 인증 거부를 확인한다.
설정 롤백은 이전 Compose의 Redis 설정으로 되돌린 뒤 같은 프로젝트·볼륨으로 Redis만
재생성한다. 기존 `REDIS_PASSWORD`와 데이터 볼륨을 유지하고 인증·백엔드 상태를 재확인한다.

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
