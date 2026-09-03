# Jira-GitLab 개발 흐름

## 1. 전체 흐름

```text
Jira 업무 항목 확인
  → develop 최신화
  → Jira Key가 포함된 작업 브랜치 생성
  → 구현 및 커밋
  → 테스트와 자체 검토
  → 최신 develop 반영 및 충돌 확인
  → develop 대상 MR 생성
  → 코드 리뷰 및 Approve
  → Squash Merge 및 Source Branch 삭제
  → Jira 업무 항목 완료
```

브랜치, 커밋, MR 이름의 상세 형식은 [Git 컨벤션](./git-convention.md)을 따른다.

## 2. 작업 시작

1. Jira 업무 항목의 목적과 범위를 확인한다.
2. 담당자와 현재 상태를 확인하고 작업을 시작할 때 `진행 중`으로 변경한다.
3. 로컬 저장소에 커밋되지 않은 다른 작업이 있는지 확인한다.
4. `develop`으로 이동해 원격 변경을 Fast-forward로 반영한다.
5. Jira Issue Key가 포함된 작업 브랜치를 생성한다.

```bash
git switch develop
git pull --ff-only origin develop
git switch -c <type>/<JIRA-KEY>-<description>
```

- 다른 사람이나 AI가 만든 로컬 변경을 임의로 삭제하거나 덮어쓰지 않는다.
- 진행 중인 변경이 있으면 먼저 해당 작업을 정리하거나 별도 worktree를 사용한다.
- API 계약, DB Schema, 공통 설정처럼 영향 범위가 큰 사항은 구현 전에 팀과 합의한다.

## 3. 구현과 커밋

- Jira 업무 항목의 목적을 벗어나는 변경을 같은 브랜치에 섞지 않는다.
- 작업 중 별도 목적의 변경이 필요해지면 새 Jira 업무 항목과 브랜치로 분리한다.
- 커밋은 리뷰 가능한 논리 단위로 작성한다.
- 비밀키, 토큰, 실제 개인정보 및 로컬 전용 설정은 커밋하지 않는다.
- 공통 파일을 변경하면 MR 본문에 영향 범위를 명시한다.

## 4. MR 전 확인

1. 변경 파일과 diff를 직접 확인한다.
2. 영역별 테스트, 정적 검사 또는 수동 검증을 수행한다.
3. 최신 `origin/develop`을 rebase로 반영하고 충돌 여부를 확인한다.
4. 불필요한 디버깅 코드와 생성 파일이 포함되지 않았는지 확인한다.
5. MR이 하나의 목적을 가지며 리뷰 가능한 크기인지 확인한다.

```bash
git fetch origin
git rebase origin/develop
```

이미 원격에 Push한 브랜치를 rebase했다면 팀원과 공유 상태를 확인한 뒤
`git push --force-with-lease`를 사용한다. 단순 `--force`는 사용하지 않는다.

테스트를 수행하지 못한 경우 숨기지 않고 MR 본문에 사유와 미검증 범위를 작성한다.

## 5. 작업 MR 생성

- Source: Jira 업무 항목 작업 브랜치
- Target: `develop`
- Template: `Default.md`
- Squash commits: 활성화
- Delete source branch: 활성화

MR 제목과 본문에는 Jira Issue Key 및 `Closes <JIRA-KEY>`를 포함한다.

화면 변경이 있으면 스크린샷을 첨부하고, 큰 MR은 리뷰 순서와 분리하지 못한 이유를 설명한다.

## 6. 리뷰와 병합

- 작성자는 주요 설계 의도와 검증 결과를 MR에 설명한다.
- 리뷰어는 요구사항 충족, 회귀 위험, 테스트, 보안 및 유지보수성을 확인한다.
- 작성자는 본인의 MR을 직접 승인하지 않는다.
- 최소 한 명의 Approve를 받은 후 병합한다.
- 리뷰 반영 중 범위가 크게 늘어나면 후속 Jira 업무 항목으로 분리한다.

병합 후 Jira 연동이 정상이라면 `Closes` 키워드를 통해 업무 항목이 완료 상태로 전환된다.

## 7. develop에서 main으로 통합

1. 팀 합의 일정에 따라 QA와 통합 테스트를 수행한다.
2. 실패한 빌드나 테스트가 있으면 main 병합을 보류한다.
3. `develop`에서 `main`으로 MR을 생성한다.
4. MR 생성 화면에서 `Release.md` 템플릿을 직접 선택한다.
5. 릴리스 범위, 주요 변경, 환경 변수, DB 변경 및 롤백 방법을 확인한다.
6. Squash를 해제하고 Merge commit을 생성해 병합한다.

`develop`에서 `main`으로의 통합 MR은 팀장이 진행하며, 구체적인 주기는 팀 합의로 정한다.

GitLab은 대상 브랜치가 `main`이라는 이유만으로 `Release.md`를 자동 선택하지 않는다.

## 8. 병합 후 로컬 정리

```bash
git switch develop
git pull --ff-only origin develop
git branch -d <merged-branch>
git fetch --prune
```

- 병합된 작업 브랜치에 후속 변경을 계속 추가하지 않는다.
- 추가 작업은 새 Jira 업무 항목과 새 브랜치에서 시작한다.
- 강제 삭제가 필요한 브랜치는 병합 여부와 보존할 변경을 먼저 확인한다.
