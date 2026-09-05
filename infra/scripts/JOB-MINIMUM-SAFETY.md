# S15P21E106-222 최소 안전장치

범위는 단일 작업의 비정상 종료 정리, 디스크/로그 상한, 입력/보고서 전달 경계다.
자동 재시도, 다중 동시 실행, 관리 화면 및 범용 실행기는 추가하지 않는다.
현재는 검토된 고정 소스 파일럿이며 일반 MR 수신이나 Runner 전환은 하지 않는다.

## 구현과 상한

- `ci-job-safety.py`: root 소유 전용 attempt에 작업 식별 기록(`lease.json`)을 먼저 저장한다.
  별도 transient owner 서비스가 작업을 소유하며, 준비/실행 서비스는 `BindsTo`/`After`로
  owner에 연결된다. owner의 `ExecStopPost`가 정확한 두 서비스의 종료/cgroup 부재를
  확인하고 파일 시스템을 해제한다. 제어 Python의 `finally`에만 의존하지 않는다.
- 정리 확인이 끝난 뒤에만 `cleanup.json`을 원자적으로 저장한다. 누락/실패/잔존 cgroup/
  잔존 mount가 있으면 새 작업을 거부한다. 임의 unit 이름이나 운영 서비스는 정리 대상이 아니다.
  재부팅 중 중단도 증명이 없으면 차단하며 자동 복구/재시도하지 않는다.
- 작업 root 전체를 **새 8 GiB regular image의 ext4**에 넣는다. source/cache/JDK/Docker
  데이터 및 작업 파일은 이 공간 안에 둔다. image는 `O_EXCL`로 만들고 미리 용량을 예약한다.
  실제 서버 디스크 장치를 포맷하거나 기존 볼륨을 재사용하지 않는다. mount는 private/nodev/nosuid다.
  unmount는 강제/lazy 옵션 없이 수행하고, 정확한 backing image와 loop 연결도 확인한다.
- owner 제한은 CPU 1개/메모리 512 MiB/PID 128/35분이며, 실제 준비/실행 작업의
  기존 CPU 2개/메모리 6 GiB/PID 2048/40분 상한을 유지한다. 실행 제어부는 1900초 제한이다.
  새로운 상시 unit이나 `/etc` 설정, 호스트 Docker/Runner/방화벽 변경은 없다.
- `ci-bounded-output.py`: 작업 stdout/stderr를 pipe로 받으며 준비/실행 로그를 각각
  **16 MiB**까지만 저장한다. 초과하면 결과를 실패 처리하고 해당 작업을 정리한다.
  파일 크기 모니터링으로 사후 대응하는 방식이 아니라, 호스트 파일에 쓰는 바이트 자체를 제한한다.
  `close_fds=True`, `--link-journal=no`로 불필요한 FD 및 호스트 journal 연결을 배제한다.
- 보고서 수집은 기존 `ci-job-io.py`의 종료 후 dirfd/O_NOFOLLOW/용량 제한을 사용한다.
  작업이 쓴 요약 파일을 실행 종료 코드나 정리 증명으로 믿지 않는다.

정리 증명 후 작업 image는 삭제하고, root 바깥의 제한된 로그·보고서·상태 증빙만 보존한다.
삭제된 임시 작업 파일 시스템은 복구할 수 없다. 정리 전 image가 합계 **48 GiB**에 도달하면
신규 작업을 막으며, 새 8 GiB 예약 후에도 호스트 여유 공간 20 GiB 이상을 요구한다.
48 GiB 한도를 조용히 늘리거나 기존 사용자 파일을 자동 삭제하지 않는다.

systemd 동작 근거는 [v255 서비스 문서의 ExecStopPost](https://github.com/systemd/systemd/blob/v255/man/systemd.service.xml),
[unit 문서의 BindsTo/After](https://github.com/systemd/systemd/blob/v255/man/systemd.unit.xml)이다.
종료 훅까지 실패한 경우의 안전 조건은 자동 복구가 아니라 **새 작업 거부**다.

## 서버 검증 기록 (2026-09-05 KST)

입력은 기존 `/var/tmp/ci222-server-6gqql4ju`를 재사용한다. 실제 backend source는
revision `9631afd9c616e047abbdd0ad7bf8192231f7f35c`이며 일반 MR 코드는 아직 받지 않는다.

| 검사 | 결과/증빙 |
| --- | --- |
| 첫 probe | 정리 확인 메서드에 fixture 결과 수집 코드가 잘못 들어가 `cleanup_failure`. 외부 owner 종료 훅은 실제 cgroup/mount를 정리. 실패 기록 유지 후 코드 분리 및 회귀 검사 추가 |
| 8 GiB 용량 상한 | root 내부 `/data`를 채우자 ENOSPC. 호스트 다른 경로로 쓰기를 우회하지 않음. 작업/마운트/loop 정리 성공 |
| 로그 상한 | 실제 무한 출력 fixture를 중단. `execution.log` **16,777,216 bytes**, 작업 결과는 `infrastructure_failure`/`passed=false`. fixture 검증은 통과 |
| 보고서 symlink | 작업이 만든 호스트 합성 canary 대상 symlink를 수집기가 **OSError errno 40(ELOOP)**으로 거부. manifest 생성 없음, canary 변경 없음, 정리 성공 |
| 제어 프로세스 SIGKILL | 합성 Docker 컨테이너 READY 확인 뒤 owner MainPID만 강제 종료. 호출 결과는 signal/KILL 실패이며 성공으로 바꾸지 않음. 종료 훅이 두 작업 cgroup, mount, loop 연결을 정리하고 증명 저장 |
| 다음 작업 수락 | SIGKILL 정리 확인 후 새로운 backend 작업 시작 성공. Gradle 82개/spotless는 성공했으나 SSH 출력 연결 단절이 owner의 BrokenPipe를 발생시켜 전체 결과는 `report_failure`로 보존. owner 출력을 journal로 분리하도록 후속 보완 |
| 최종 backend | 최종 stage에서 SSH 호출을 의도적으로 끊은 뒤 owner가 독립 실행. Gradle 0, 82개, 실패/오류/skip 0, 필수 5개 클래스 및 spotlessCheck 성공. 종료/cgroup/mount/loop 정리와 임시 image 삭제 확인 |
| 운영 영향 | 운영 컨테이너 6개 실행, 기존 Runner active, 랜딩 HTTPS 200. root 29 GiB 사용/281 GiB 여유(10%) |

코드/실행 위치:

- 최초 실패: `/var/tmp/ci222-server-q2sr_e_q/run-xvqm9f5g`.
  upload SHA256 `08eea54bcc1515f4e39a49c8df2adc15fb060564f74028068d7c56bc12f1e82e`.
- disk-full: `/var/tmp/ci222-server-gb2th35s/run-y21k8e1p`.
  upload SHA256 `beafbad3cc8e8907df5ff1ebde02f5416711eeee4001042253bbbd5466dc2a19`.
- 후속 stage: `/var/tmp/ci222-server-let8721n`.
  upload SHA256 `438012548d3e2d1d15eac7f34cf040922a86fa823ff109e0efe6373dd40213b1`.
  - 로그 초과: `run-yxrvcnvs`.
  - 링크 보고서: `run-70ktogwb` (`report-rejection.json` 포함).
  - SIGKILL: `run-q1i_zv6_`, owner `ci222-owner-beb8ba9f66e7.service`.
  - 제한 내 전체 backend: `run-xspfduzq` (아래 최종 결과 기록으로 판정).
- 최종 stage: `/var/tmp/ci222-server-p9joy9gz`, upload SHA256
  `a47a2ab8edf4dd54623fcdeb5e63167950e9addadfa1ba94912fc46f5becb8d8`.
  - SSH와 분리된 제한 내 backend: `run-e22rzv1c`, owner
    `ci222-owner-0655e144d54f.service`.
  - `lifecycle.json`은 `finished/success/process_exit=0/passed=true`.
    `evidence/manifest.json`은 tests 82, failures/errors/skipped 0,
    필수 클래스 누락 0, `passed=true`다.
  - `cleanup.json`을 확인했고 `disk.img`, `ci222-*` cgroup, mount 및 loop 장치 잔존은 없다.

각 attempt의 root 소유 `lease.json`, `cleanup.json`, 제한된 로그와 결과/보고서를 보존한다.
SIGKILL은 제어부의 최종 `lifecycle.json`이 없을 수 있으며, systemd 결과와 별도 종료 훅의
`cleanup.json`이 근거다. SIGKILL의 실패 종료를 CI 테스트 성공으로 해석하지 않는다.
후속 보완본은 마운트 해제와 loop 부재 확인 후 전용 `disk.img`를 삭제한다.

## 로컬 검사 및 사용 경계

Linux 전체 `test_ci_*.py`: **242개 중 241개 통과, 환경 조건 1개 skip**.
입력 경로/링크/FIFO/파일 교체, 상속 가능한 합성 FD의 자식 미전달, 로그 상한,
정리 증명 누락/실패 시 admission 거부, 임의 unit 거부를 검사했다.
모의 실패 검사와 실제 서버 SIGKILL/ENOSPC/로그/링크 검사는 구분한다.

```bash
python3 infra/scripts/ci-job-safety.py plan
```

서버에서는 검증된 새 stage의 `ci-job-safety.py run --prepared-base <검증 입력 stage>
--mode backend --confirm-server-pilot` 경로만 사용한다. 이전 직접 backend 파일럿은
과거 재현용으로 남아 있지만 이 최소 안전장치를 우회하므로 일반 MR에 연결하지 않는다.

정리 실패 시 lease/증빙을 지워 차단을 해제하지 않는다. 정확한 unit/cgroup/mount/loop
소유 관계를 확인하고 복구해야 한다. 운영 디렉터리나 기존 이미지 전체 prune는 하지 않는다.
일반 MR 인증정보·소스 수신·job script·결과 업로드와 GitLab 취소 연동은 다음 작업이다.
동일 커널/full readonly sysfs의 잔여 위험은 그대로이며 VM 수준 격리를 주장하지 않는다.
