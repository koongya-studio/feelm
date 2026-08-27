# Jira 업무 생성 자동화

AI와 작성한 짧은 YAML 계획을 검토한 뒤 Jira Cloud REST API로 Epic과 Task를 생성한다.
기본 실행은 미리보기이며 `--apply`를 명시한 경우에만 Jira를 변경한다.

## 최초 설정

Python 3.11 이상을 사용한다.

```bash
python -m venv .venv
python -m pip install -r scripts/jira/requirements.txt
```

`.env.example`을 `.env`로 복사한 뒤 개인 Atlassian 계정의 값을 입력한다. 팀 토큰을
공유하지 않으며 `.env`와 API Token은 Git에 추가하지 않는다.

```dotenv
JIRA_BASE_URL=https://ssafy.atlassian.net
JIRA_PROJECT_KEY=S15P21E106
JIRA_EMAIL=<개인 Atlassian 이메일>
JIRA_API_TOKEN=<개인 API Token>
```

## 계획 작성

계획마다 `docs/jira/plans/<name>.yml` 파일을 만들고 다음 필드를 사용한다. 하나의 공용
파일에 모든 계획을 누적하지 않는다.

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `id` | O | 계획 파일 안에서 사용하는 고유 식별자 |
| `issue_type` | X | `Epic` 또는 `Task`, 기본값은 `Task` |
| `summary` | O | Jira 제목 |
| `description` | O | 목적과 배경 |
| `epic` | X | 상위 Epic의 Jira Key 또는 앞서 선언한 로컬 `id` |
| `priority` | X | Jira 프로젝트에 존재하는 우선순위 이름 |
| `done_when` | O | 검증 가능한 완료 조건 목록 |
| `jira_key` | X | 생성 결과이며 값이 있으면 다시 생성하지 않음 |

```yaml
tasks:
  - id: jira-automation
    issue_type: Task
    summary: "[협업] Jira 작업 생성 자동화"
    description: "검토한 YAML 계획으로 Jira 업무를 생성한다."
    epic: S15P21E106-12
    priority: Low
    done_when:
      - "실행 전에 생성 예정 목록을 확인할 수 있다."
      - "재실행해도 업무가 중복 생성되지 않는다."
    jira_key:
```

같은 파일에서 Epic과 Task를 함께 만들면 Epic을 먼저 작성하고 Task의 `epic`에 Epic의
로컬 `id`를 사용한다. 생성할 항목은 `jira_key`를 비워 둔다.

## 실행

개인 인증정보와 프로젝트 접근 권한을 읽기 전용으로 확인한다. 이 명령은 Jira 업무를
생성하거나 수정하지 않는다.

```bash
python scripts/jira/create_issues.py --check
```

연결에 성공하면 프로젝트 Key와 생성 가능한 업무 유형의 이름·ID를 출력한다. YAML에는
`Epic`과 `Task`를 사용하고, 스크립트가 프로젝트의 `에픽`과 `작업`을 포함한 실제 유형
ID를 메타데이터에서 찾아 사용한다.

먼저 생성 예정 목록을 확인한다.

```bash
python scripts/jira/create_issues.py --file docs/jira/plans/infra-p0.yml
```

목록이 맞을 때만 실제 생성을 요청한다.

```bash
git fetch origin
python scripts/jira/create_issues.py --file docs/jira/plans/infra-p0.yml --apply
```

실행 전 최신 `origin/develop`의 같은 계획 파일과 열린 MR을 확인해 다른 작업자가 이미
생성하지 않았는지 확인한다. 각 업무가 생성되는 즉시 Jira Key가 계획 파일에 저장되므로
도중에 실패해도 이미 Key가 기록된 업무는 재실행 시 건너뛴다. Jira의 Epic, 우선순위 또는
필드 이름이 유효하지 않으면 API 오류를 확인하고 계획을 수정한 뒤 다시 미리보기한다.
