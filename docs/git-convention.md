# Git 컨벤션

## 1. 운영 원칙

- `main`은 배포 및 시연 가능한 안정 버전을 유지한다.
- `develop`은 팀 개발 내용을 지속적으로 통합하는 기본 브랜치다.
- 실제 개발은 Jira 업무 항목 단위의 짧은 작업 브랜치에서 진행한다.
- `main`과 `develop`에는 직접 Push하지 않고 Merge Request를 통해서만 병합한다.
- FE, BE, 데이터 등 영역별 장기 develop 브랜치는 만들지 않는다.
- Merge method는 Fast-forward merge를 사용한다.

```text
main
  ↑  팀 합의 일정에 따라 QA 및 통합 테스트 후 병합
develop
  ↑        ↑        ↑
feat/*    fix/*    refactor/*
```

## 2. 브랜치 전략

### 브랜치 역할

| 브랜치 | 역할 |
| --- | --- |
| `main` | 배포 및 시연 가능한 안정 버전 |
| `develop` | 팀 개발 결과가 합쳐지는 통합 브랜치이자 기본 브랜치 |
| 작업 브랜치 | 하나의 Jira 업무 항목을 구현하는 단기 브랜치 |

### 브랜치 이름

```text
<type>/<JIRA-KEY>-<description>
```

| Type | 용도 |
| --- | --- |
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 기능 변경 없는 리팩터링 |
| `test` | 테스트 코드 |
| `docs` | 문서 수정 |
| `chore` | 기타 설정 및 작업 |
| `ci` | CI/CD 설정 변경 |

예시:

```text
feat/S15P21E106-52-movie-recommend
fix/S15P21E106-61-login-error
docs/S15P21E106-28-git-workflow
ci/S15P21E106-80-gitlab-pipeline
```

- Jira-GitLab 작업 추적을 위해 작업 브랜치에는 Jira Issue Key를 반드시 포함한다.
- 한 브랜치에는 하나의 Jira 업무 항목만 포함한다.
- 작업 브랜치는 최신 `develop`에서 생성하고 가능한 1~2일 안에 병합한다.

## 3. 커밋 컨벤션

Conventional Commits 형식을 사용한다.

```text
<type>(<scope>): <내용>
```

### Scope

| Scope | 변경 영역 |
| --- | --- |
| `fe` | `frontend/` |
| `be` | `backend/` |
| `reco` | `recommendation/` |
| `data` | `data/` |
| `infra` | `infra/` 및 인프라 설정 |
| `docs` | `docs/` 및 문서 |

- Scope는 담당자가 아니라 실제 변경 영역을 기준으로 작성한다.
- 여러 영역을 포괄하거나 Scope가 불필요하면 생략할 수 있다.
- 커밋 메시지에는 Jira Issue Key를 필수로 넣지 않는다.
- 제목은 변경 결과가 드러나는 명령형 표현으로 간결하게 작성한다.

예시:

```text
feat(fe): 영화 상세 화면 구현
feat(be): 영화 상세 조회 API 구현
fix(be): 외부 API 응답 예외 처리
chore(infra): Docker Compose 설정 추가
docs: Git 협업 흐름 문서화
```

## 4. Merge Request 규칙

### 작업 브랜치에서 develop으로 병합

MR 제목 형식:

```text
[<JIRA-KEY>] <type>(<scope>): <내용>
```

예시:

```text
[S15P21E106-52] feat(be): 개인 추천 조회 API 구현
[S15P21E106-28] docs: Git 및 Jira-GitLab 개발 흐름 문서화
```

MR 본문에는 다음 내용을 포함한다.

- `Closes <JIRA-KEY>` 형식의 관련 이슈
- 주요 작업 내용
- 테스트 및 확인 결과
- 리뷰 시 주의할 사항

작업 MR은 다음 원칙을 따른다.

- 하나의 Jira 업무 항목 또는 하나의 명확한 변경 목적만 포함한다.
- 본인이 만든 MR을 본인이 승인하지 않는다.
- 최소 한 명의 리뷰와 Approve를 받은 후 병합한다.
- Squash를 활성화하고 병합 후 Source Branch를 삭제한다.
- CI가 구성된 이후에는 Pipeline이 성공한 상태에서만 병합한다.

### MR 크기

MR은 리뷰어가 약 30분 안에 주요 변경을 이해할 수 있는 크기를 지향한다.

- 권장: 변경 코드 400줄 이하, 변경 파일 15개 이하
- 400~800줄: MR 본문에 분리하지 못한 이유와 리뷰 순서를 작성한다.
- 800줄 초과 또는 변경 파일 25개 초과: 원칙적으로 Jira 업무 항목과 MR을 분리한다.
- 자동 생성 파일, lock 파일, 단순 포맷팅 결과는 변경량 판단에서 제외할 수 있다.
- 기능 구현과 무관한 대규모 리팩터링을 같은 MR에 섞지 않는다.

숫자는 절대적인 병합 조건이 아니다. 변경 목적, 결합도, 리뷰 가능성을 함께 판단한다.

### develop에서 main으로 병합

- 팀장이 팀 합의 일정에 따라 통합 MR을 진행한다.
- QA와 통합 테스트를 통과한 `develop`만 `main`으로 병합한다.
- 예정된 통합일이라도 빌드·테스트가 실패하거나 시연 가능한 상태가 아니면 병합을 보류한다.
- 특정 Jira 업무 항목이 아닌 통합 MR이므로 Jira Issue Key를 제목에 강제하지 않는다.
- MR 생성 시 `Release.md` 템플릿을 직접 선택한다.
- Squash를 사용하지 않는다.
- 별도 Merge Commit 없이 `main`을 `develop`의 최신 커밋 위치로 이동한다.

예시:

```text
[Sprint 3] develop → main
[Release] Sprint 3 통합
```

## 5. 모노레포 운영

```text
frontend/
backend/
recommendation/
data/
infra/
docs/
```

- 디렉터리 구조는 초기 기준이며 서비스 경계에 따라 팀 합의로 변경할 수 있다.
- 독립적으로 개발할 수 있는 FE와 BE 작업은 Jira 업무 항목과 브랜치를 분리한다.
- 공통 설정, API 계약, DB Schema 등 여러 영역에 영향을 주는 변경은 구현 전에 공유한다.

충돌 가능성이 높은 공통 변경의 예시는 다음과 같다.

```text
docker-compose.yml
.gitlab-ci.yml
.env.example
의존성 및 lock 파일
DB migration 및 schema
공통 API 계약
공용 컴포넌트와 타입
```

## 6. 규칙 변경

- 이 문서를 Git 협업 규칙의 기준 문서로 사용한다.
- Notion 등 외부 문서는 핵심 내용을 요약하고 이 문서로 연결한다.
- 규칙 변경도 Jira 업무 항목과 MR을 통해 팀 리뷰를 받은 뒤 반영한다.
