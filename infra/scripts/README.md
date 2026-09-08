# Runner 통신 격리 파일럿 (S15P21E106-222)

백엔드 의존성 변경으로 격리 Runner의 Gradle 캐시가 부족할 때는
[S15P21E106-271 캐시 자동화](DEPENDENCY-CACHE-AUTOMATION.md)를 사용한다. MR의 변경을 표시하는
고정 Job과 보호된 `develop`의 준비·검증·전환 Job을 분리하며, 준비와 배포를 직렬화하고 전환
실패 시 이전 `prepared_base`를 복구한다.
Protected Environments가 없는 현재 GitLab에서는 Job Token의 `GET /job` 응답과 설치 시 만든
root 소유 운영자 허용 목록으로 실제 수동 Job 실행자를 검증한다.

MR 61의 신규 메일 의존성을 포함하는 오프라인 캐시 갱신과 Runner 전환 전 독립 검증 절차는
[S15P21E106-251 캐시 갱신 절차](OFFLINE-CACHE-REFRESH.md)를 따른다.

일반 MR 전환 전 남은 접근 경계와 구현 순서는
[MR 실행 경계 설계안](MR-EXECUTION-BOUNDARY.md)을 참고한다. 후속
[GitLab MR 전용 어댑터](GITLAB-MR-ADAPTER.md)는 `backend-mr-test`를 새 태그로 분리하고
인증된 현재 MR의 backend archive만 기존 격리 실행기에 전달한다. 코드는 로컬 검증 단계이며
서버 설치·Runner 등록·토큰 입력은 아직 수행하지 않았다.

[서버 manager 파일럿](SERVER-MANAGER-PILOT.md)은 별도 임시 폴더에서 진행했다.
자원 제어와 readonly/private sysfs 준비를 보완하여 고정 경계 probe,
백엔드 82개/spotlessCheck 및 취소/timeout fixture가 통과했다. 운영 설정은 유지했다.
일반 MR Runner의 실제 성공/실패/취소 검증과 운영 Runner 전환은 아직 미완료이며,
고정 파일럿 성공을 보안 승인으로 간주하지 않는다.

후속 [작업 실행기 핵심 경로](JOB-EXECUTOR-CORE.md)에 입력/보고서 경계와 작업별
수명주기 구현, 새 서버 정상 실행·실제 테스트 중 취소 증빙 및 남은 전환 조건을 기록했다.

축소 범위의 [최소 안전장치](JOB-MINIMUM-SAFETY.md)는 단일 작업의 비정상 종료 정리,
8 GiB 디스크/16 MiB 로그 상한과 파일 전달 거부 검사를 다룬다. 일반 MR 연결은 별도다.

로컬 manager 실험에서 cgroup 관리 프로세스 분리와 승인된 `keyctl` 예외 후
Docker 실행·자원 제한·합성 호스트 파일/socket 및 고정 privileged 요청 검사가 통과했다.
[검증 결과와 남은 범위](ROOTFS-FIXTURE.md)를 참고한다. 서버 적용이나 일반 MR 승인이 아니다.

후속 [로컬 백엔드 입력 준비](LOCAL-BACKEND-INPUTS.md)는 소스 추출과 파일 무결성 검사만
구현했다. 승인된 캐시·이미지 전송과 JDK21 배포본 확보/해시 검사는 완료했고,
후속 [새 manager의 로컬 backend 실행](LOCAL-BACKEND-RUN.md)에서 Java21·82개/spotlessCheck,
정상 종료와 별도 취소/timeout fixture가 통과했다. 이후 서버 고정 파일럿 결과는 위 문서와 구분한다.
상시 운영 적용과 일반 MR 전환은 미완료다.

아직 운영 적용 및 Runner 전환용으로 검증 완료된 스크립트가 아니다.
식별 표시 수정본의 서버 적용과 호스트 계정 검사는 수행했다. rootless 컨테이너 DNS 예외
적용 후 해당 IPv4 통신 검증을 수행했다. 검토된 backend의 수동 테스트 82건은 통과했으며
[실행 증빙과 보고서 판별 수정](ROOTLESS-BACKEND-CHECK.md)에 기록했다. 실제 MR Runner 전환은 미완료다.
기존 develop 배포 방식은 유지한다. 현재 작업 브랜치의 `.gitlab-ci.yml`에는 MR 전용 Job 분리가
추가되었지만 새 Runner가 등록되기 전에는 해당 Job이 pending되는 것이 정상이다.

[전용 부팅 서비스와 시작 검사](GUARD-SERVICE.md)는 서버 설치·정상 시작 검증까지 수행했다.
기존 테이블 전체 비교와 root 소유 사용자 서비스 시작 gate를 제공하며,
실행 중 규칙 소실 감시나 미검토 MR 실행 경계는 아직 제공하지 않는다.
[격리된 서비스 실패 경로 검사](GUARD-FAILURE-CHECK.md)는 서버에서도 통과했고 증빙을 보존했다.
[실행 중 종료 실험과 구조 제안](RUNTIME-GUARD-DESIGN.md)은 오프라인 고정 fixture 단계이며,
기존 서버나 일반 MR에 적용하는 감시 서비스가 아니다.
[오프라인 backend 입력 준비](OFFLINE-BACKEND-PREPARE.md)는 캐시·이미지 사전검사와 파일 준비만
제공한다. 사용자 지시에 따라 Docker 통신 경계 변경과 실제 테스트 실행 전에 중단한다.

후속 사용자 승인으로 [별도 오프라인 Docker 파일럿](OFFLINE-DOCKER.md)의 launcher와 unit을
추가했다. 기존 입력 준비기는 여전히 파일 준비만 하며 서버 설치/기동과 이미지 이전은 미검증이다.

다음 단계의 [rootless 테스트 네트워크 사전 검증](ROOTLESS-TEST-PREFLIGHT.md)은 테스트 전용
bridge와 임시 포트 연결을 검사한다. 실제 백엔드 테스트·Runner 등록 자동화는 아직 포함하지 않는다.

DNS 예외 적용 후 운영자가 rootless 컨테이너의 GitLab DNS 성공/HTTPS 200, 운영망·공인 주소·
메타데이터 연결 실패 및 차단 카운터 증가, 운영 컨테이너 6개 실행 유지를 확인했다.
이는 해당 IPv4 검사 결과이며 IPv6와 Testcontainers 호환성 완료를 의미하지 않는다.

## 2026-09-04 파일럿 결과와 수정

사용자가 서비스 서버의 nftables v1.0.9에서 초기 버전 `230c8e5`를 실행했다.
호스트 테스트 계정에서 내부 서비스 TCP 연결 4건이 성공에서 실패로 바뀌었고,
사설망 차단 카운터 4건 증가, GitLab 443 연결 유지 및 운영 컨테이너 실행 유지를 확인했다.
운영 공인 주소·IPv6·rootless 컨테이너 경로는 아직 검증하지 않았다.

테이블의 JSON 조회에서 식별 comment가 `MISSING`이었다. 초기 코드는 테이블 생성과
comment 선언을 분리했고 적용 후 식별 표시를 확인하지 않았다. 수정본은 최초 `create table`에
comment를 지정하고, 설치 후 JSON으로 이를 확인한 경우에만 적용 성공을 출력한다.
`fe668f8` 수정본은 사용자가 v1.0.9의 별도 네트워크 네임스페이스에서 생성·식별 표시·복구를
검증했다. 실제 서버의 옛 테이블도 운영자 확인 후 교체하여 식별 표시와 내부 연결 4건 차단,
GitLab 연결 성공, 운영 컨테이너 실행 유지를 재확인했다.

**다른 환경의 기존 comment 없는 테이블은 수정본을 받아도 자동 복구되지 않는다.** 해당 테이블을
유지하고 테스트 Runner는 연결하지 않는다. 기존 규칙·UID·적용 이력을 운영자가 확인한 후
승인된 교체 절차를 별도로 수행해야 한다. `rollback`의 소유 검사 완화나 전체 ruleset 삭제로
우회하지 않는다. 적용 후 확인이 실패해도 규칙을 자동 삭제하지 않고 오류를 반환한다.

### rootless 컨테이너 DNS 예외

컨테이너의 `/etc/resolv.conf`는 DNS 서버 `172.26.0.2`를 사용했다. GitLab IP의 TCP 443 연결은
성공했지만 도메인 HTTPS 요청은 `Temporary failure in name resolution`으로 실패했다.
내부·운영 공인 주소·메타데이터 검사에서 연결 실패와 차단 카운터 증가를 확인했으나,
DNS 및 필요한 외부 통신 성공이 확보되지 않아 전체 격리 검증 통과로 간주하지 않는다.

운영자 합의에 따라 테스트 계정 UID의 `restricted` 체인에 목적지 `172.26.0.2`의
UDP/TCP 53만 추가 허용한다. 명시적 운영 IP 차단 뒤, 사설망 차단 앞에 배치한다.
인터넷에서 들어오는 53번 포트를 열거나 사설망 전체를 허용하지 않는다. DNS 서버의
다른 포트, 다른 사설 주소, 운영 공인 IP 및 IPv6 차단은 유지한다.
이 주소는 현재 서비스 서버에서 확인한 resolver이며 다른 환경에 그대로 적용하지 않는다.

DNS 질의를 통한 정보 유출이나 내부 이름 조회까지 방지하는 정책은 아니다. 현재 공인
80/443 허용과 함께 남는 위험으로 기록한다. 이 예외 변경 시 컨테이너 DNS 조회와
GitLab HTTPS 성공, 기존 내부·운영 공인 주소·메타데이터 차단 카운터를 함께 재검증해야 한다.
스크립트를 pull하는 것만으로 기존 서버 규칙이 변경되지는 않는다. `apply`의 기존 테이블
교체 거부 및 `rollback`의 식별 표시 검사를 유지하며, 승인된 교체 절차를 별도로 진행한다.

## 범위와 제한

- `ci-network-guard.py`: 기본 동작은 규칙 출력뿐이다. `check`는 nft의 문법 검사를
  수행하고, `apply --confirm`만 임시 규칙을 설치한다. `status`는 카운터를 조회한다.
- `rollback --confirm`: 소유 표시가 일치하는 `inet feelm_ci_222` 테이블만 삭제한다.
  기존 UFW·Docker 규칙을 flush하거나 서비스를 재시작하지 않는다.
- `ci-network-probe.py`: 테스트 계정에서 TCP 연결만 확인한다. 인증·데이터 조회를 하지 않는다.
  연결 실패는 방화벽 차단의 단독 증거가 아니다. 종료 코드 0도 격리 성공을 뜻하지 않는다.
- UID는 서버의 `feelm-ci-test` 계정에서 조회한다. 다른 사용자의 OUTPUT에는 제한을 걸지 않는다.
  IPv4 DNS `127.0.0.53:53`, `172.26.0.2:53`의 UDP/TCP와 공인 목적지 TCP 80/443만 허용 후보로 남긴다.
  기존 UFW 등 후속 규칙은 계속 적용된다. IPv6는 파일럿에서 전부 차단한다.
- 위 DNS 예외 외의 사설망·루프백·링크 로컬/메타데이터·호스트 로컬 주소 및 명시한 운영 공인 IP를 차단한다.
  운영 공인 IP 목록의 완전성은 스크립트가 판별할 수 없다. 서비스/분산 서버의 모든 공인
  주소와 운영 도메인의 외부 프록시/LB 경로를 먼저 확인해야 한다. 주소 변경 시 재검토한다.
- 공인 80/443 전체 허용은 목적지 allowlist나 정보 유출 방지 기능이 아니다.
  로컬 DNS 경유 질의 역시 허용된다. Unix 소켓/로컬 파일/공유 커널/디스크 사용량은 별도 범위다.
- **Testcontainers가 사용하는 호스트 루프백/임시 포트도 현재 차단된다.** 실행 방식을 확인한 뒤
  필요한 경로만 별도로 설계·검증해야 한다. 편의를 위해 루프백 전체를 허용하지 않는다.
- rootlesskit/slirp4netns의 외부 연결이 해당 UID의 호스트 OUTPUT을 통과하는지
  호스트 프로세스와 rootless 컨테이너 양쪽에서 검증해야 한다. 호스트 계정 검사만으로 완료하지 않는다.
- 재부팅, UFW reload 또는 다른 방화벽 관리 작업 뒤 유지되는 정책으로 간주하지 않는다.
  현재 자원 제한도 `--runtime`이므로 재부팅 시 재확인이 필요하다. 미신뢰 MR은 아직 실행하지 않는다.

## 준비 및 읽기 전용 확인

서비스 서버에서 운영자 계정으로 작업한다. 스크립트는 운영자 소유의 경로에 보관하며
테스트 계정이 수정 가능한 복사본을 sudo로 실행하지 않는다. Secret이나 실제 IP를 저장소에 적지 않는다.

```bash
command -v nft python3
id feelm-ci-test
sudo nft list tables
```

`nft`가 없으면 여기서 멈춘다. 패키지 설치·UFW 재구성은 자동으로 수행하지 않는다.
실행 중인 테스트 작업이 없는지 확인하고 운영 SSH 세션을 유지한다.
실제 IP를 다음 변수에 직접 입력한다. 예시 문자열 그대로 실행하면 주소 검증에 실패한다.

```bash
SERVICE_PUBLIC_IP='실제 서비스 서버 공인 IPv4'
DISTRIBUTED_PUBLIC_IP='실제 분산 서버 공인 IPv4'

python3 infra/scripts/ci-network-guard.py plan \
  --deny-ip "$SERVICE_PUBLIC_IP" --deny-ip "$DISTRIBUTED_PUBLIC_IP"

sudo python3 infra/scripts/ci-network-guard.py check \
  --deny-ip "$SERVICE_PUBLIC_IP" --deny-ip "$DISTRIBUTED_PUBLIC_IP"
```

추가 운영 공인 IP가 있으면 모든 호출에 `--deny-ip`를 반복한다.
동일 이름 테이블이 있으면 자동 교체하지 않고 중단한다. `create table`도 기존 테이블 생성을
거부하므로 사전 확인 이후 생긴 같은 이름의 테이블과 합쳐지지 않는다.

## 운영자 승인 후 임시 적용·검증

먼저 적용 전 연결 기준값을 기록한다. 아래는 호스트 계정 검사 예이며,
프로브 파일을 읽을 수 있는지 먼저 확인한다. 운영 저장소 전체의 권한을 풀어주지 않는다.
필요하면 운영자가 프로브 파일 하나만 테스트 계정의 홈에 복사하여 그 경로로 실행한다.

```bash
sudo -u feelm-ci-test -H python3 /home/feelm-ci-test/ci-network-probe.py 127.0.0.1 8080
sudo -u feelm-ci-test -H python3 /home/feelm-ci-test/ci-network-probe.py 172.26.0.121 9092
sudo -u feelm-ci-test -H python3 /home/feelm-ci-test/ci-network-probe.py 172.26.0.121 8020
sudo -u feelm-ci-test -H python3 /home/feelm-ci-test/ci-network-probe.py 172.26.0.121 7077
sudo -u feelm-ci-test -H python3 /home/feelm-ci-test/ci-network-probe.py lab.ssafy.com 443
```

다음 명령은 **실제로 테스트 계정 통신을 변경한다**. 문법 검사 결과와 공인 IP 목록을 검토한 뒤 실행한다.

```bash
sudo python3 infra/scripts/ci-network-guard.py apply --confirm \
  --deny-ip "$SERVICE_PUBLIC_IP" --deny-ip "$DISTRIBUTED_PUBLIC_IP"
sudo python3 infra/scripts/ci-network-guard.py status
```

프로브를 다시 실행하고 차단 규칙 카운터 증가와 비교한다. 운영 서비스가 계속 실행 중인지
운영자 계정에서 별도로 확인한다. GitLab 외에 실제 Maven/Gradle/이미지 레지스트리 접근,
rootless 컨테이너 내부에서 사설망·운영 공인 주소·메타데이터·IPv6 차단을 추가 검증한다.
프로브는 애플리케이션 요청을 보내지 않으므로 HTTPS 다운로드 성공도 별도로 확인해야 한다.
필요한 테스트 포트 설계와 Testcontainers 테스트 통과 전에는 Runner 등록/태그 전환을 진행하지 않는다.

## 복구

테스트 작업 및 새 Runner가 있다면 먼저 중지/일시 정지한다. 그 후 다음 명령을 실행한다.

```bash
sudo python3 infra/scripts/ci-network-guard.py rollback --confirm
```

복구는 방화벽 파일럿만 제거한다. 계정·rootless Docker·데이터·자원 제한은 삭제하지 않는다.
복구 후 해당 계정의 운영망 접근이 다시 가능해질 수 있으므로 MR을 재개하지 않는다.

## 로컬 테스트

```bash
python3 -B -m unittest discover -s infra/scripts -p 'test_ci_network_guard.py' -v
```

단위 테스트는 규칙 생성 및 호출 흐름 검증이다. 실제 nft 검사나 운영 격리 검증의 대체가 아니다.
