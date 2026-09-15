# MR 격리 Runner 오프라인 캐시 갱신

## 대상

- Jira: `S15P21E106-251`
- 원인: MR 61이 추가한 `org.springframework.boot:spring-boot-starter-mail:4.1.1`과
  전이 의존성이 기존 오프라인 Gradle 캐시에 없어 `compileJava`가 실패했다.
- 검토 revision: `0e2bab04ef2fa7601747f4718d239eaabdc79df8`
- backend archive SHA256:
  `3e151aef092a1db51f1295f6f9c27e7a68141a7ced838503e67b1eaf21e82ceb`

서버 실행 전 MR 61의 HEAD가 위 revision과 같은지 다시 확인한다. 달라졌으면 실행하지 않고
revision과 archive SHA를 새 커밋 기준으로 코드 리뷰부터 다시 한다.

## 서버 실행 전 확인

1. 실행 중인 `backend-mr-test`가 없어야 한다.
2. 운영 컨테이너 수와 상태를 기록한다.
3. `/home/feelm-ci-test/backend-checks`와 `/home/feelm-ci-test/offline-inputs`의 여유 공간을 확인한다.
4. Runner의 기존 `prepared_base`와 기존 설정 파일을 읽기 전용으로 기록한다.
5. 외부 Secret을 테스트 환경에 전달하지 않는다.

## 1. 검토 소스 archive 생성

서버의 배포 체크아웃에서 대상 Git 객체가 존재하는지 확인한 뒤 실행한다.

```bash
REVIEWED_REVISION=0e2bab04ef2fa7601747f4718d239eaabdc79df8
EXPECTED_SOURCE_SHA=3e151aef092a1db51f1295f6f9c27e7a68141a7ced838503e67b1eaf21e82ceb
SOURCE_ARCHIVE="/home/feelm-ci-test/pilot-222/backend-$REVIEWED_REVISION.tar"
TEMP_ARCHIVE=$(mktemp /tmp/ci251-backend-XXXXXXXX.tar)

git cat-file -e "$REVIEWED_REVISION^{commit}"
git archive --format=tar --output="$TEMP_ARCHIVE" "$REVIEWED_REVISION" backend
test "$(sha256sum "$TEMP_ARCHIVE" | cut -d ' ' -f1)" = "$EXPECTED_SOURCE_SHA"
sudo install -o feelm-ci-test -g feelm-ci-test -m 0600 "$TEMP_ARCHIVE" "$SOURCE_ARCHIVE"
rm "$TEMP_ARCHIVE"
```

## 2. 네트워크 허용 준비 실행

기존 rootless 전용 계정과 guard가 정상임을 먼저 확인한다. 이 단계만 신규 Gradle 의존성을
다운로드한다. 성공 로그의 `BACKEND_RUN_DIR`를 기록한다.

```bash
sudo -u feelm-ci-test -H \
  env XDG_RUNTIME_DIR=/run/user/1001 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1001/bus \
  systemd-run --user --scope --unit="ci251-cache-$(date -u +%Y%m%d%H%M%S)" \
  python3 /home/feelm-ci-test/pilot-222/ci-rootless-backend-check.py run \
  --archive "$SOURCE_ARCHIVE" \
  --revision "$REVIEWED_REVISION" --sha256 "$EXPECTED_SOURCE_SHA" \
  --confirm-reviewed-code --confirm-guard-active
```

Gradle 성공, 실패·생략 없는 전체 테스트, `spotlessCheck`, private DNS 정리, 잔존 테스트
컨테이너 없음이 모두 확인되어야 한다.

## 3. 새 오프라인 입력 생성

`BACKEND_RUN_DIR`는 바로 앞의 성공한 실행 경로만 사용한다. 다른 실행 경로나 기존 캐시를
자동 선택하지 않는다. `REPO_ROOT`는 S15P21E106-251 변경이 반영된 검토 완료 checkout이며,
커밋되지 않은 파일이나 운영 checkout의 임의 수정본을 사용하지 않는다.

```bash
BACKEND_RUN_DIR=/home/feelm-ci-test/backend-checks/<성공한 실행 ID>
REPO_ROOT=/home/ubuntu/S15P21E106-251

sudo -u feelm-ci-test -H python3 \
  "$REPO_ROOT/infra/scripts/ci-backend-offline-prepare.py" inspect \
  --source-run "$BACKEND_RUN_DIR" --archive "$SOURCE_ARCHIVE"

sudo -u feelm-ci-test -H python3 \
  "$REPO_ROOT/infra/scripts/ci-backend-offline-prepare.py" prepare \
  --source-run "$BACKEND_RUN_DIR" --archive "$SOURCE_ARCHIVE" --confirm-cache-idle
```

출력된 `PREPARED_DIR`의 `manifest.json`을 검토하고 SHA256을 기록한다.

```bash
PREPARED_DIR=/home/feelm-ci-test/offline-inputs/<새 입력 ID>
MANIFEST_SHA=$(sha256sum "$PREPARED_DIR/manifest.json" | cut -d ' ' -f1)
printf 'PREPARED_DIR=%s\nMANIFEST_SHA=%s\n' "$PREPARED_DIR" "$MANIFEST_SHA"
```

## 4. 새 서버 stage 준비

로컬 S15P21E106-251 worktree에서 업로드 스크립트를 실행해 새 `SERVER_STAGE`를 만든다.
이 동작은 기존 Runner 설정을 바꾸지 않는다.

```text
python infra/scripts/ci-server-pilot-stage.py --confirm-server-stage
```

서버에서 새 stage의 입력 준비만 실행한다.

```bash
SERVER_STAGE=/var/tmp/ci222-server-<새 stage ID>
sudo python3 "$SERVER_STAGE/scripts/ci-server-manager-pilot.py" prepare \
  --cache-manifest "$PREPARED_DIR/manifest.json" \
  --cache-root "$BACKEND_RUN_DIR/home/.gradle" \
  --cache-manifest-sha256 "$MANIFEST_SHA" \
  --confirm-server-pilot
```

`SERVER_PREPARATION: PASS`와 `prepared.json`의 revision·manifest SHA 일치를 확인한다.

## 5. 전환 전 오프라인 검증

새 stage를 기존 Runner에 연결하기 전에 독립 실행으로 검증한다.

```bash
sudo python3 "$SERVER_STAGE/scripts/ci-server-manager-pilot.py" backend \
  --prepared-base "$SERVER_STAGE" --confirm-server-pilot
```

성공 기준은 `GRADLE_EXIT=0`, 전체 테스트·`spotlessCheck` 성공, 잔존 컨테이너 없음,
`SERVER_PILOT_BACKEND: PASS`다. 이 단계까지는 기존 Runner 설정을 변경하지 않는다.

## 6. 설치본과 prepared stage 원자 전환

독립 검증을 통과한 동일 `SERVER_STAGE`를 설치 입력과 `prepared_base`로 함께 지정한다.

```bash
sudo python3 "$SERVER_STAGE/scripts/ci-dependency-cache-install.py" \
  --confirm-update \
  --operator-username <GitLab 사용자명> \
  --prepared-base "$SERVER_STAGE"
```

설치기는 backend 런타임 계약이 다르거나 실행 중인 MR test가 있으면 변경 전에 거부한다.
성공 로그의 `PREVIOUS_INSTALL`, `PREVIOUS_SUDOERS`, `PREVIOUS_CONFIG`와 새 `prepared_base`를
기록하고 Runner가 active인지 확인한다. 실패 시 기존 prepared input을 수정하거나 삭제하지
않고 새 stage와 실행 증빙을 보존한다.
