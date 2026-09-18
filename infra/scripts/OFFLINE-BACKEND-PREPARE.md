# 222: 오프라인 backend 입력 준비 — Docker 통신 경계 작업 전 중단

사용자 지시: 스크립트를 먼저 작성하고 **Docker 통신 경계를 작업하기 전에 알린다.**
이번 변경은 사전검사/파일 준비까지다. 실제 오프라인 실행기는 아직 제공하지 않는다.
Docker network 생성/삭제, daemon 시작/중지, 방화벽 변경, 이미지 pull/export, Gradle 실행,
기존 CI/Runner 변경을 하지 않는다. Jira 실시간 상태·담당자·MR은 이번 작업에서 조회하지 못했다.

## 서버에서 제공받은 입력 증빙

- 기록된 실행 폴더의 Gradle 캐시: 413M.
- `apache/kafka:4.3.1`, `redis:8-alpine`, `pgvector/pgvector:0.8.6-pg17-bookworm`,
  `testcontainers/ryuk:0.14.0`
  이미지와 RepoDigest를 사용자 출력에서 확인했다. digest는 준비 스크립트에 정확히 기록했다.
- 테스트 Docker 컨테이너 목록은 비어 있었다. 이미지 태그가 바뀌면 새 것을 pull하거나
  암묵적으로 수락하지 않고 digest 검사에서 중단한다. 이미지 ID와 OS/architecture도 기록한다.
- Kafka 이미지는 backend MR의 실제 Kafka offset 통합 테스트가 요구한다. 저장소에 고정한
  index digest와 rootless Docker의 `RepoDigests`가 일치해야 하며, 이미지가 없을 때 외부 pull로
  우회하거나 Testcontainers 대기 시간을 늘리지 않는다.
- 고정 재현 대상은 이전 수동 테스트의 revision `9631afd9c616e047abbdd0ad7bf8192231f7f35c`,
  archive SHA256 `b045a76f85d8373dec543a58ec1a72e8aacebe288d0856bbfe678db8d5c4d3b1`이다.
  최신 backend 호환성을 자동 주장하지 않는다. 재현 대상을 바꾸려면 별도 검토가 필요하다.

## 스크립트 동작

`ci-backend-offline-prepare.py`는 다음 세 동작만 제공한다.

| 동작 | 수행 내용 | 하지 않는 것 |
| --- | --- | --- |
| plan (기본) | 계획 출력 | 파일/Docker 조회도 없음 |
| inspect | archive 해시·캐시 파일 해시·rootless endpoint·빈 컨테이너 목록·이미지 digest 조회 | 파일 생성/다운로드 없음 |
| prepare --confirm-cache-idle | 위 검사 후 새 UUID 폴더에 입력 복사·manifest 작성 | 테스트/네트워크 작업 없음 |

`inspect`/`prepare`는 기존 helper의 계정·보조 그룹·운영 소켓 접근 거부·rootless socket 소유권
검사를 사용한다. root가 아니라 feelm-ci-test로 수행한다. helper와 새 파일은 기존 pilot 설치
방식대로 운영자가 읽을 수 있게 준비해야 한다. 현재 서버에는 이 새 파일을 설치하지 않았다.

원본 backend 실행 폴더를 복사하지 않고 해시가 확인된 Git archive만 풀어 build 출력 등을
배제한다. 캐시는 `caches/`와 `wrapper/dists/`만 선택한다. 사용자 gradle.properties/init.d,
daemon 로그, Docker 로그인 파일은 복사하지 않는다. lock 파일·심볼릭 링크·특수 파일은
복사하지 않으며 링크/특수 파일이 보이면 중단한다. 최대 2GiB/100,000파일로 제한한다.
Gradle 9.7.1 배포본과 modules-2 자료 존재를 확인하되, 모든 의존성의 완전성까지 증명하지 않는다.

`prepare`는 전체 선택 캐시 복사만큼 디스크가 추가로 필요하다. 복사 전 그 용량과 별도로 5GiB
여유를 요구한다. 파일별 SHA256/크기를 비교하고 원본 inventory도 다시 확인한다. 복사 중
Gradle/test가 실행되면 안 된다. 캐시의 해시는 복사 동일성 확인이지 공급망 안전성 검증이 아니다.
검토된 전용 계정의 기존 캐시만 재사용하며 미검토 MR이 오염시킨 캐시를 신뢰하지 않는다.

결과는 `/home/feelm-ci-test/offline-inputs/<UUID>/`이며 backend, 선택 캐시, 기존 Java/Gradle
보조 파일 및 manifest가 남는다. 실패 시 부분 출력은 삭제하지 않고 완성 manifest도 쓰지 않는다.
반복 실행하면 누적되므로 무제한 반복하지 않는다. 원본 캐시·archive·이미지를 수정/삭제하지 않는다.

manifest의 planned_gradle_args는 `clean test spotlessCheck --offline` 전체를 유지하지만
**명령을 실행하지 않는다.** state는 항상 `WAITING_DOCKER_BOUNDARY_REVIEW`, tests_executed=false,
docker_images_exported=false다. 이미지 목록만 기록한 것이지 새 daemon에 이미지가 준비된 것은 아니다.
준비 종료 코드 0은 오프라인 backend 실행 성공이 아니다. `run` 동작과 확인 우회 플래그는 없다.

## 로컬 검증

단위 테스트로 기본 plan/확인 없는 prepare의 무변경, 실행 동작 없음, 캐시 선택·크기 제한,
이미지 digest/잔존 컨테이너 거부, 복사 해시 동일성 및 전체 offline task 보존을 확인한다.
실제 413M 서버 캐시나 rootless 이미지를 이번 작업에서 직접 읽거나 복사하지 않았다.
테스트용 작은 임시 파일로 준비 동작을 검증했다. Docker 통신 경계 시험도 수행하지 않았다.

## 여기서 중단하고 먼저 설명할 사항

다음 작업 전에 사용자에게 daemon/네트워크 구성 후보, 필요 디스크, 기존 rootless Docker의
중단 여부, 이미지 이전 방식, 적용·복구 절차를 설명하고 확인받는다. 현재 인터넷 연결 가능한
daemon 소켓을 오프라인 프로세스에 연결하는 것만으로 Docker 통신이 차단되지는 않는다.
별도 daemon을 만들지 기존 daemon을 일시 제한할지 아직 확정하지 않았으며 이번 코드에는
어느 쪽을 적용하는 명령도 넣지 않았다.

Gradle `--offline`은 의존성 해석의 캐시 사용 옵션이며 wrapper 다운로드, 임의 테스트/플러그인의
네트워크 동작, Testcontainers/Docker pull 자체의 방화벽이 아니다.
[Gradle 공식 캐시 설명](https://docs.gradle.org/current/userguide/dependency_caching.html)

실제 실행 어댑터·환경/namespace/daemon 검증과 캐시 부족 시 안전한 실패 처리는 Docker 경계
설계 이후에 구현한다. 현재 rootless Docker를 재시작하거나 운영 방화벽 규칙을 지우지 않는다.
