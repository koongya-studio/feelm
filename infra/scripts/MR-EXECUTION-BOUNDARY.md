# S15P21E106-222: 일반 MR 실행 경계 설계안

상태: 검토안, 서버 적용/Runner 등록/CI 수정 없음. Jira 실시간 담당자·상태·열린 MR은
이번 작업에서 확인하지 못했다. 새 계정·root filesystem·executor 배치는 아직 합의 대상이다.

후속 기록(2026-09-05 KST): 운영자 승인 하에 [별도 서버 고정 파일럿](SERVER-MANAGER-PILOT.md)의
경계 probe·82개/spotless·취소/timeout fixture를 검증했다. Jira 222는 진행 중, 담당자 E106조은채,
관련 열린 MR 0개를 확인했다. 아래 일반 MR 실행기 전체 합격 기준은 아직 충족하지 않았으며,
Runner 등록·CI 변경·상시 설치는 하지 않았다. 위 상태 문구는 이 설계안 작성 당시 기록이다.

## 확인된 성과와 중단선

운영자 제공 결과: 별도 오프라인 Docker에서 검토 revision 9631afd의 전체 테스트 82개,
실패/오류/skip 0, spotlessCheck 포함 Gradle 0(54초), 잔존컨테이너0, 최종0.
컨테이너별 메모리/CPU/PID 제한, sibling HTTP와 namespace loopback 포트도 확인했다.
이는 호환성 성공이며 악성 MR 실행 승인이 아니다.

현재 feelm-ci-test는 온라인 rootless socket과 사용자 systemd bus에 접근한다.
새 Docker socket을 MR에 주면 Docker API로 daemon이 볼 수 있는 파일을 bind mount할 수 있다.
그러므로 job 컨테이너에서 기존 socket 경로만 숨겨도 충분하지 않다. **Docker daemon 자체가
볼 수 있는 파일시스템과 프로세스가 작업 경계 안에 있어야 한다.**

현재 .gitlab-ci.yml에서 runner-check에도 e106-shell 태그가 있고 MR 제외 rules가 없다.
backend-test만 옮겨도 runner-check 또는 MR이 수정한 다른 job이 운영 Runner를 요청할 수 있다.
따라서 최종 전환은 GitLab의 운영 Runner Protected 제한까지 포함해야 한다.
rules/tags는 스케줄 선택이지 악성 CI 코드에 대한 권한 경계가 아니다.

## 채택을 제안하는 구조

| 실행 주체 | 허용 | 금지 |
| --- | --- | --- |
| 신뢰된 Runner 제어 프로세스 | GitLab job 수신·소스 준비·보고서 전송 | 운영 Docker 접근, MR 스크립트의 호스트 직접 실행 |
| 신뢰된 작업 관리자 | 고정 템플릿으로 작업별 격리 생성/종료 | MR 입력으로 mount/UID/unit/namespace 경로 지정 |
| 작업별 실행 영역 | 복사된 소스·캐시, 작업 내부 Docker·DB·Redis | 호스트 rootfs/proc/sys/run/home, 사용자 bus, 온라인/운영 socket |
| 기존 운영 Runner | 보호 develop의 빌드·푸시·수동 service 배포 | 비보호 MR job 수신 |

작업별 최소 root filesystem 안에 Java, Docker 실행 도구와 테스트 데이터를 넣고,
daemon·containerd·테스트 프로세스를 모두 같은 외부통신 없는 영역에 배치하는 후보를 검토한다.
호스트와 별도 PID/mount/user/net 경계, 전용 proc/dev/run, 최소 capability, 공유 mount 전파
차단이 필요하다. 단순 chroot 또는 호스트 Docker socket mount를 이 구조로 간주하지 않는다.
호스트 사용자 bus를 가져오지 않는다. 자원 제한은 호스트의 신뢰된 관리자가 부여하고 작업은
다른 host cgroup/scope를 만들 수 없어야 한다. 내부 systemd/rootless cgroup 호환성은 미검증이다.

이 후보는 새 rootfs 제작/수명주기 관리가 필요하므로 곧바로 운영 파일로 설치하지 않는다.
동일 커널 위험은 남는다. 고정 공격 fixture로 경계를 검증할 수 없거나 Docker 자원 제한을
유지할 수 없다면 제한을 풀지 말고 중단한다. 그 경우 격리 VM/별도 CI 호스트가 대안이며,
추가 서버가 없다는 현재 조건에서 즉시 가능하다고 가정하지 않는다.

## 준비와 실행을 나누는 규칙

1. 제어 프로세스가 소스를 가져오되 Gradle/프로젝트 hook을 호스트에서 실행하지 않는다.
2. 승인된 이미지·의존성 캐시는 신뢰된 준비 단계에서 만든다. MR의 의존성 변경이 cache miss면
   오프라인 실행을 실패시키고 별도 검토한다. 네트워크를 자동 개방하지 않는다.
3. 소스·캐시는 작업별 복사본, 인증정보는 전달하지 않는다. 작업이 쓴 캐시를 다음 작업이나
   보호 develop 빌드로 승격하지 않는다.
4. 검사·부팅 성공 후에만 MR 실행을 허용한다. 작업 준비 실패 시 기존 shell Runner fallback 금지.
5. 종료 후 작업 전체와 Docker 자원을 제거한다. 실패 시 격리 유지·새 작업 수신 중단.
6. 보고서는 크기/종류/경로를 제한하고 링크를 따르지 않으며 수집 중 파일 바꾸기 공격도 검사한다.

## 구현 순서와 합격 기준

[로컬 rootfs 고정 검증](ROOTFS-FIXTURE.md)에서 파일 경로·proc·권한 축소의 기초 검사를
수행했다. Docker API/작업 수명주기까지 시험한 것은 아니므로 아래 단계 1~5 전체 통과는 아니다.

첫 구현은 **Runner가 아닌 고정 fixture**다. 새 rootfs/계정 배치 방식 합의 후 로컬에서 진행한다.

| 순서 | 확인할 것 | 실패 시 |
| --- | --- | --- |
| 1 | job과 daemon에서 운영/온라인 socket·사용자 bus·운영 파일 경로 접근 불가 | MR 코드 실행 금지 |
| 2 | Docker API의 host bind·host PID·namespace 진입 요청도 호스트 자원 노출 불가 | 경계 재설계, 예외 권한 금지 |
| 3 | 부모 namespace FD·상속 FD·proc root·cgroup/bus 우회·감시 종료 불가 | 경계 재설계 |
| 4 | 내부 Testcontainers 동작, 외부 IPv4/IPv6 불가, 전체82개/spotless 성공 | 호환성 해결 후 재검증 |
| 5 | cancel/timeout/관리자 종료/daemon 실패 후 프로세스·mount·컨테이너·scope 정리 | 재사용 금지 |
| 6 | 새로운 테스트 Runner의 실제 MR 성공, 운영 Runner의 비보호 MR 수신 거부 | 전환 보류 |
| 7 | 보호 develop 빌드·푸시·수동배포 유지 | 사람 승인 하에 복구 |

단계 1~5 통과 후 현재 작업 브랜치에 MR 전용 Job과 고정 정책 어댑터를 추가했다. 서버 설치와
Runner 등록, 운영 Runner Protected 설정은 아직 바꾸지 않았다. 상세 적용 조건은
[GitLab MR 전용 어댑터](GITLAB-MR-ADAPTER.md)를 따른다.
전환 시 새 Runner를 먼저 등록·정지 상태로 점검한 뒤, 첫 MR 실행 전에 기존 Runner를 Protected로
바꾼다. 새 경로가 실패하면 기존 Runner 보호를 풀지 않고 일반 MR을 pending으로 두며,
runner-check도 포함해 모든 MR job을 검토한다. 배포 수행은 별도 운영 승인 하에 한다.

## 대안 중 제외한 빠른 변경

- 기존 온라인 daemon stop: 같은 계정이 다시 실행할 수 있어 경계 완료가 아님.
- socket chmod만 변경: 소유자/사용자 manager 권한 문제가 남음.
- job만 컨테이너화하고 현재 daemon socket 전달: daemon의 host filesystem 접근이 남음.
- rootless 또는 Protected 변수만으로 완료: 각각 호스트 파일/스케줄 권한 전체를 해결하지 않음.
- 전체 네트워크를 막은 Docker executor를 바로 등록: 소스 수신/보고서 전송과 Testcontainers
  API 경계가 해결되지 않았으므로 자동 전환하지 않음.

근거: [GitLab Runner 보안](https://docs.gitlab.com/runner/security/),
[Docker executor 구조](https://docs.gitlab.com/runner/executors/docker/).
공식 설명은 shell executor의 신뢰 코드 사용과 비특권 컨테이너 사용을 권고한다.
위 작업별 rootfs 구조와 합격 기준은 이 프로젝트의 조건을 바탕으로 한 설계 제안이다.
