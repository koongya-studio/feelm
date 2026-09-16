# 문서 MR 검증

`S15P21E106-296`은 develop 대상 MR마다 `docs-mr-check`를 생성한다. 서비스별 변경 경로
필터가 모든 Job을 제외하더라도 최소 하나의 실제 검증이 실행되므로 `Pipelines must
succeed`와 함께 사용할 수 있다.

## 검사 범위

검사기는 현재 MR commit의 모든 추적 Markdown과 전체 추적 파일 경로 목록을 입력으로 받는다.

- UTF-8로 읽을 수 없는 Markdown
- 코드 블록 밖의 병합 충돌 흔적
- 닫히지 않은 인라인 링크와 fenced code block
- 존재하지 않는 상대·저장소 루트 기준 내부 파일 및 이미지 링크
- 저장소 밖으로 벗어나는 링크와 알 수 없는 URI scheme

외부 URL은 네트워크로 조회하지 않는다. `http`, `https`, `mailto` 등은 형식상 외부 링크로만
분류한다. 링크의 `#fragment`는 파일 존재 검사에서 제거하며, heading anchor 자체는 검사하지
않는다. GitLab의 anchor 생성은 Unicode, 기호, 중복 heading 처리에 따라 달라질 수 있으므로
정확한 anchor 확인은 GitLab 렌더링에서 수동으로 수행한다. HTML의 `href`와 `src`, Markdown
인라인 링크 및 reference definition의 대상은 검사하지만 전체 CommonMark/GitLab Flavored
Markdown 문법 검사기는 아니다.

파일명에 한글이나 공백이 있으면 공백이 포함된 대상을 angle bracket으로 감싼다.

```markdown
[사용 설명](<docs/사용 설명.md>)
![포스터](<assets/포스터 1.png>)
```

검사기는 모든 MR에서 실행되므로 문서를 수정하지 않고 링크 대상 파일만 삭제하거나 이동해도
기존 문서의 역방향 링크 오류를 찾는다. 현재 저장소 전체가 통과하는 상태를 기준으로 하며,
기존 오류를 허용하는 baseline이나 광범위한 예외 목록은 두지 않는다.

## 로컬 재현

Python 3.11 이상과 Git만 필요하며 외부 패키지를 설치하지 않는다.

```bash
python3 -B infra/scripts/check-markdown.py
python3 -B -m unittest infra.scripts.test_check_markdown infra.scripts.test_ci_docs_mr \
  infra.scripts.test_ci_docs_paths -v
```

오류는 `파일:줄:열: 종류 설명` 형식으로 출력한다. 종료 코드는 검증 성공 `0`, 문서 오류 `1`,
입력·실행 오류 `2`다.

## 격리 실행 경계

GitLab Job은 `e106-mr-isolated` Custom executor를 사용하고 Git checkout, cache, artifact,
service를 요청하지 않는다. 어댑터는 현재 열린 develop 대상 MR의 commit을 다시 인증한 뒤 다음
입력만 전달한다.

- 모든 추적 `*.md` 내용
- 전체 추적 경로의 정렬·중복 제거된 JSON manifest

MR의 검사기 코드는 실행하지 않는다. root 소유 설치본 `check-markdown.py`만 UID/GID 1001의
`feelm-ci-test` 계정으로 실행한다. transient systemd unit에는 private network/device/tmp,
read-only system 보호, home 차단, no-new-privileges, CPU·메모리·task·시간·출력 상한을 적용한다.
Docker socket, 배포 변수와 GitLab Job Token은 검사 프로세스에 전달하지 않는다.

검사기와 어댑터 변경은 기존 원자적 adapter update 절차로 서버에 먼저 설치하고, 실제 MR에서
정상 문서 성공과 깨진 링크·누락 이미지·대상 삭제 실패를 확인한다. 저장소 변경만으로 운영
Runner 설치본이 자동 갱신되지는 않는다.
