# 222: rootless 테스트 네트워크 사전 검증

`ci-rootless-network-check.py`는 실제 백엔드 테스트를 실행하기 **전** 연결 경로를 검증한다.
Java 21/Git 설치, rootless Docker 29.7.2, 브리지 대역 `172.17.0.0/16`은 운영자가 확인했다.
Docker 29.5 이후 `--network host`는 실제 호스트 네트워크를 공유할 수 있으므로 사용하지 않는다.

## 검증 목적

현재 운영 호스트 루프백 접근은 차단되어 있다. Testcontainers는 Docker API 접근 외에도
임시 컨테이너에 공개한 포트에 연결해야 한다. 같은 네트워크에서 이름으로 직접 연결할 수
있다는 사실만으로 기존 PostgreSQL/Redis Testcontainers 테스트가 실행된다고 판단하지 않는다.

이 스크립트는 실제 DB 대신 내용이 고정된 HTTP 컨테이너로 다음 세 경로를 분리한다.

1. 새 테스트 전용 bridge 안에서 `probe-server:8000` 연결.
2. 같은 bridge 안에서 서버의 실제 컨테이너 IPv4로 직접 연결.
3. 같은 bridge의 gateway를 통해 임시 공개 포트 연결.

**3번은 현재 루프백 전용 공개 설정에서 실패할 수 있으며, 이 경우 종료 코드 2가 올바른 결과다.**
성공을 위해 `0.0.0.0` 포트 공개, 호스트 네트워크, 사설망 전체 허용으로 자동 변경하지 않는다.
실패 원인을 바탕으로 Testcontainers 실행 경로를 추가 설계해야 한다. 실제 테스트 실행
컨테이너·rootless 소켓 연결은 이 단계 이후의 작업이며 이 스크립트에는 아직 포함하지 않는다.

### fda41bb 실행 결과와 진단 보완

운영자 실행에서 `SIBLING_HTTP: PASS`, `GITLAB_DNS_HTTPS: PASS`,
`MAPPED_PORT_HTTP: FAIL URLError`, 종료 코드 2가 나왔다. 운영 대상 연결은 실패했으며
별도 라벨 조회로 임시 컨테이너·네트워크가 남지 않았음을 확인했다.

루프백 바인딩 주소와 gateway 접속 대상은 서로 다르다. 초기 출력의 `URLError`만으로는
거부·타임아웃·DNS 오류를 구분할 수 없으므로 이를 방화벽 결함이나 Testcontainers 전체의
지원 불가로 단정하지 않는다. 보완본은 다음을 출력한다.

- `ROUTE_DETAILS`: Docker inspect에서 읽은 실제 공개 주소/포트, 서버 컨테이너 주소,
  gateway 접속 주소 및 주소 일치 여부. 전체 inspect나 환경변수는 출력하지 않는다.
- `CONNECT_TARGET`: 각 테스트 HTTP 경로의 대상 URL. 운영 Secret이나 검사 토큰을 포함하지 않는다.
- `SIBLING_IP_HTTP`: 이름 조회를 거치지 않은 직접 연결을 추가하여 이름 기반 경로와 비교한다.
- 실패 시 JSON `error_type`, `reason_type`, `errno`, `http_status`: 내부 예외까지 구분하되
  임의의 예외 메시지/인증정보는 출력하지 않는다.

**이 보완은 진단 개선이며 임시 포트 연결 해결책은 아니다.** 같은 설정에서 재실행하면 여전히
종료 코드 2가 나올 수 있다. 통과를 강제하려고 검사 경로를 삭제하거나 예외를 무시하지 않는다.
서버에서 보완본의 진단 결과를 검증한 뒤 공개 범위·신뢰 경계 변경이 필요한 대안은 별도 합의한다.

### namespace 모드: 운영자 검토 코드만 실행

`cbf44d2`의 서버 결과는 이름·컨테이너 IP 연결과 GitLab HTTPS가 PASS였지만,
`127.0.0.1:32769` 공개 포트를 bridge gateway `172.18.0.1:32769`로 접속하면
`ConnectionRefusedError`, errno 111이 발생했다. 공인 주소 차단 카운터는 각각 +2,
사설망 차단 카운터는 +10이었다. 이는 검사 경로의 실패이며 방화벽 완화 근거가 아니다.

합의된 다음 후보는 `run --client-mode namespace --confirm`이다. 기본 sibling 모드는
유지하고 별도 모드에서 rootless dockerd의 **user/net/mount namespace**에 들어간 고정
Python 검사만 실행한다. Docker 공식 문서의 nsenter 진단 패턴에 기반하며 Java/Gradle,
임의 명령, 미신뢰 MR 코드를 실행하는 범용 진입 도구는 아니다.

서버에서 `a96367e`는 dockerd의 net namespace가 호스트와 같아서 진입 전에 중단됐다.
추가 읽기 전용 검사에서 호스트 관점의 `dockerd-rootless/netns`는 일반 파일
(`NS_GET_NSTYPE` errno 25)이지만 Docker mount 관점의 파일은 실제 network namespace였으며
호스트와 다른 식별값임을 확인했다. 따라서 net 대상은
`/proc/<dockerd-pid>/root/run/user/<uid>/dockerd-rootless/netns`로 수정한다.
user/mount는 기존 dockerd의 namespace를 사용한다. 네트워크 분리 검사를 제거하지 않으며,
세 객체의 실제 namespace 종류 및 detached network의 소유 user namespace도 ioctl로 검증한다.
경로가 없거나 일반 파일이면 호스트 네트워크로 대체하지 않고 중단한다.
이 경로 수정본의 실제 진입·연결 결과는 아직 서버 미검증이다.

#### 81522a2 서버 결과와 검사 전용 DNS 보완

운영자가 `NAMESPACE_IDENTITY`, 컨테이너 IP 및 내부 loopback 공개 포트 HTTP의 PASS를
확인했다. GitLab은 `gaierror -3`으로 DNS 단계에서 실패했다. 운영 대상 연결은 실패했고,
공인 주소 차단 카운터 각각 +2, 사설망 +10, 운영 컨테이너 6개 실행 유지를 확인했다.
Docker mount 관점의 `/etc/resolv.conf`에는 `nameserver 127.0.0.53`이 남아 있었다.
이는 분리된 네트워크의 loopback에서 호스트 DNS 서비스를 찾게 되는 잘못된 조합이다.

보완본은 namespace 고정 검사 프로세스에서만 다음을 수행한다.

1. 기존 namespace/UID 검사 후 `unshare(CLONE_NEWNS)`로 **추가 전용 mount namespace**를 만든다.
2. mount namespace 식별값이 바뀌었는지 확인하고 `/`를 재귀적으로 private propagation으로
   설정한다. 이 두 단계가 실패하면 DNS 파일 생성·bind mount·통신 검사를 수행하지 않는다.
3. `/tmp/ci222-dns-*`의 고유 임시 디렉터리에 권한 0600인 resolver 파일을 만들고,
   `nameserver 172.26.0.2` 및 제한된 재시도 설정만 기록한다. 이미 허용한 DNS 주소를 사용한다.
4. `/etc/resolv.conf`의 실제 대상에 **해당 프로세스의 private mount에서만** bind mount한다.
   기존 resolver 파일을 덮어쓰거나 삭제하지 않는다. 적용 내용을 읽어 확인하고 `PRIVATE_DNS`를 출력한다.
5. 동일한 임시 포트/GitLab/운영망 검사를 수행한 후 bind mount 해제와 임시 디렉터리 정리를 한다.
   `PRIVATE_DNS_CLEANUP: PASS` 및 원래 검사 종료 코드를 출력한다. 정리 실패도 성공으로 처리하지 않는다.

호스트 또는 dockerd의 기존 mount namespace, DNS 설정, 방화벽에는 변경을 적용하지 않는다.
추가 패키지, Docker 재시작, 공개 DNS 허용, GitLab IP 고정 또는 TLS 검증 생략도 없다.
권한 오류 시 root로 재실행하거나 격리 검사를 제거하지 않는다.
강제 종료 시 프로세스 전용 mount namespace는 마지막 참조 종료 시 소멸하지만,
작은 임시 resolver 파일이 `/tmp/ci222-dns-*`에 남을 수 있으므로 해당 실행의 파일만 확인해 정리한다.
자동 광역 삭제는 하지 않는다.

`02dbfd2`에서 운영자가 namespace 진입, 컨테이너 IP/내부 loopback 포트, GitLab DNS/HTTPS,
PRIVATE_DNS_CLEANUP의 PASS 및 종료 코드 0을 확인했다. 공인 주소 차단 카운터 각각 +2,
사설망 +10, VPC DNS +6 증가와 운영 컨테이너 6개 실행 유지를 확인했다.
이는 고정 검사 코드의 IPv4 경로 사전 검증이며 Runner 격리 승인이나 실제 backend 테스트 성공이 아니다.
다음 단계는 [검토된 backend 수동 실행](ROOTLESS-BACKEND-CHECK.md)이다.

- 호출자는 계속 `feelm-ci-test`이며 root 또는 sudo 권한으로 진입하지 않는다.
- 고정 경로 `/run/user/<uid>/docker.pid`의 소유자, 대상 프로세스 이름 `dockerd`,
  UID 매핑 `0 -> 테스트 계정 UID`, 현재 호스트와 다른 user/net/mount 영역을 검사한다.
  namespace 파일을 열어 고정한 FD로 진입하고 내부 검사에서도 namespace inode를 확인한다.
- 내부 UID 0은 **rootless user namespace 안의 UID 0**이다. 호스트 root 권한은 아니지만
  테스트용 데몬 영역을 제어할 수 있는 신뢰 경계이므로 미신뢰 MR 실행 승인으로 해석하지 않는다.
  파일시스템이 컨테이너처럼 새로 격리되는 것도 아니다. 실제 MR 실행 방식에는 추가 설계가 필요하다.
- mount namespace 진입 후 위 검사 전용 DNS 절차로 추가 private mount를 만든다.
  호스트/Docker의 원래 resolver 파일이나 nft 설정을 수정하지 않는다.
  환경변수는 PATH/LANG 및 고정 검사에 필요한 값만 전달한다.
- HTTP 서버 컨테이너 1개와 bridge 1개만 생성한다. 서버의 기존 제한과 loopback 공개는
  유지한다. 검사 프로세스는 메모리 주소 공간 512MiB, CPU 시간 30초, 실행 대기 90초로 제한한다.
  이는 Runner 전체의 영구 자원 제한을 대체하지 않는다.
- 검사 대상은 `NAMESPACE_CONTAINER_IP_HTTP`와 **내부 loopback 공개 포트**
  `NAMESPACE_LOOPBACK_MAPPED_HTTP`이다. 기존 sibling의 gateway 검사를 통과했다고 표시하지 않는다.
  GitLab DNS/HTTPS와 운영 private/public/metadata 연결 검사도 그대로 수행한다.
- 자원 정리는 생성한 ID만 대상으로 유지한다. 실패해도 host network, 공개 바인딩,
  운영 loopback 허용 등으로 자동 재시도하지 않는다.

로컬 모의 테스트만 수행한 후보이며 실제 namespace 진입·임시 포트·DNS는 **서버 미검증**이다.
성공하더라도 Testcontainers, 실제 MR, IPv6, 영구 방화벽 및 자원 제한 검증이 남는다.

보완본이 서버에 반영된 뒤 기존 설치 경로에 스크립트를 갱신하고 아래를 실행한다.
먼저 기존 DNS 예외 포함 guard의 marker/UID/차단 대상이 맞는지 운영자가 확인한다.

```bash
sudo python3 infra/scripts/ci-network-guard.py status

sudo install -o root -g root -m 0644 \
  infra/scripts/ci-rootless-network-check.py \
  /home/feelm-ci-test/ci-rootless-network-check.py

sudo -u feelm-ci-test -H env XDG_RUNTIME_DIR=/run/user/1001 \
  python3 /home/feelm-ci-test/ci-rootless-network-check.py run --confirm \
  --client-mode namespace \
  --service-public-ip "${SERVICE_PUBLIC_IP:?공인 IP 입력 필요}" \
  --distributed-public-ip "${DISTRIBUTED_PUBLIC_IP:?공인 IP 입력 필요}"
echo "검사 종료 코드: $?"

sudo python3 infra/scripts/ci-network-guard.py status
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

다음 단계는 두 namespace HTTP 검사 및 GitLab PASS, 운영 대상 연결 실패와 차단 카운터 증가,
임시 리소스 정리를 확인하는 것이다. 진입 오류나 연결 실패 시 해당 상세 오류를 검토하며
sudo로 재실행하지 않는다. 실제 backend 테스트와 Runner 등록은 별도 후속 단계다.

## 변경 범위 및 안전장치

- 기본 `plan`은 아무 명령도 실행하지 않는다. `run --confirm`만 Docker 리소스를 생성한다.
- `feelm-ci-test` 단독 그룹, 본인 소유 rootless 소켓, rootless 보안 옵션·데이터 경로를 검사한다.
  운영 Docker 소켓에 읽기/쓰기 권한이 있으면 중단한다. 모든 Docker 명령은 전용 소켓을 명시한다.
- 사용자 확인한 공인 IPv4 두 개가 필수다. 도메인·사설 IP·IPv6로 대체하면 거부한다.
- 기존에 받은 digest 고정 Python 이미지만 사용하며 자동 pull하지 않는다.
- 새 bridge 1개, HTTP 서버와 검사 컨테이너 2개만 생성한다. 볼륨·운영 파일·Docker 소켓을
  컨테이너에 마운트하지 않는다. privileged/host network 및 운영 환경변수 전달도 없다.
- 서버의 임시 포트는 호스트 `127.0.0.1`에만 공개하고 실제 바인딩도 조회하여 검사한다.
  테스트 계정의 rootless Docker에만 생성하나 호스트 로컬 프로세스가 이 테스트 HTTP 포트에
  접근할 수 있다는 점은 남는다. HTTP 응답에는 무작위 검사 토큰만 포함된다.
- 컨테이너별 256MiB/1CPU/64PIDs, 읽기 전용 파일시스템, capability 제거, non-root,
  no-new-privileges를 적용한다. 컨테이너 로그 파일은 만들지 않고 검사 출력만 전달한다.
- 검사 종료/일반 예외 시 생성된 ID만 역순 정리한다. prune, 전체 리소스 삭제, 기존 리소스
  이름 재사용은 하지 않는다. 정리 실패는 종료 코드 3과 ID를 출력한다.
- 강제 종료·서버 장애·생성 명령 응답 유실은 리소스를 남길 수 있다. 이름 `ci222-probe-*`와
  라벨 `com.feelm.ci222.probe`로 운영자가 해당 실행 리소스를 확인한 후 개별 정리한다.
- IPv6, rootless 소켓을 부여한 실제 MR 코드, 잔존 Secret, 자원 제한 영구 적용은 미검증이다.
  공개 Docker API 소켓을 제공하지 않고 Runner도 아직 등록하지 않는다.

## 준비 및 실행

먼저 운영자가 현재 DNS 예외 포함 방화벽이 활성 상태임을 확인한다. 스크립트는 일반
테스트 계정으로 실행하므로 호스트 nft 규칙을 직접 확인하거나 수정하지 않는다.
다른 테스트 작업 없이 운영자가 검토한 스크립트만 실행한다.

운영 저장소의 디렉터리 권한을 완화하지 않는다. 스크립트 한 파일만 테스트 홈에 복사한다.
기존 동일 파일이 있으면 보존 여부를 확인한 뒤 갱신한다.

```bash
python3 infra/scripts/ci-rootless-network-check.py plan

sudo install -o root -g root -m 0644 \
  infra/scripts/ci-rootless-network-check.py \
  /home/feelm-ci-test/ci-rootless-network-check.py

sudo -u feelm-ci-test -H env XDG_RUNTIME_DIR=/run/user/1001 \
  python3 /home/feelm-ci-test/ci-rootless-network-check.py run --confirm \
  --service-public-ip "${SERVICE_PUBLIC_IP:?공인 IP 입력 필요}" \
  --distributed-public-ip "${DISTRIBUTED_PUBLIC_IP:?공인 IP 입력 필요}"
echo "검사 종료 코드: $?"

sudo python3 infra/scripts/ci-network-guard.py status
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

`SIBLING_HTTP`, `SIBLING_IP_HTTP`, `MAPPED_PORT_HTTP`, `GITLAB_DNS_HTTPS`가 모두 PASS여야 연결 경로 사전 검증을
통과한다. 운영 주소 연결 실패는 카운터와 함께 검토한다. 종료 코드 0도 IPv6·완전한 격리나
백엔드 테스트 성공을 뜻하지 않는다. 실패 시 방화벽을 해제하지 않고 결과를 검토한다.

## 로컬 테스트와 근거

```bash
python3 -B -m unittest discover -s infra/scripts -p 'test_ci_*.py' -v
```

로컬 단위 테스트는 Docker 호출을 모의한다. Windows 개발 환경에서 실제 Linux 네트워크
연결은 검증하지 않았고 서버에서 운영자 실행 결과가 필요하다.

- [Testcontainers sibling container 방식](https://java.testcontainers.org/supported_docker_environment/continuous_integration/dind_patterns/)
- [Docker rootless 네트워크 제한](https://docs.docker.com/engine/security/rootless/troubleshoot/)
- [Linux unshare](https://man7.org/linux/man-pages/man2/unshare.2.html)
- [Linux mount와 propagation](https://man7.org/linux/man-pages/man2/mount.2.html)
