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
JIRA_BOARD_ID=<Jira 보드 URL의 숫자 ID>
```

## 계획 작성

신규 계획은 Git이 추적하지 않는 `.jira/tmp/<name>.yml`에 작성한다. YAML은 Jira 생성
요청을 검토하고 실패 시 재시도하기 위한 임시 입력이며 Jira가 업무 정보의 기준이다.
장기 보존할 필요가 있는 계획만 `docs/jira/plans/<name>.yml`에 문서로 추가한다. 기존
`docs/jira/plans/` 파일은 과거 생성 기록과 하위 호환을 위해 그대로 유지한다.

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `id` | O | 계획 파일 안에서 사용하는 고유 식별자 |
| `issue_type` | X | `Epic` 또는 `Task`, 기본값은 `Task` |
| `summary` | O | Jira 제목 |
| `description` | O | 목적과 배경 |
| `area` | 조건부 | `epic` 생략 시 자동 선택·생성 기준. 제목의 `[영역]`으로 대체 가능 |
| `epic` | X | 상위 Epic을 직접 고정할 때 Jira Key 또는 앞서 선언한 로컬 `id` |
| `priority` | X | Jira 프로젝트에 존재하는 우선순위 이름 |
| `story_points` | Task 필수 | 구현 AI가 복잡도와 위험을 평가해 선택한 값. `1, 2, 3, 5, 8, 13, 21` 중 하나 |
| `sprint` | X | Task 전용. `active`(기본값)는 활성 스프린트에 배정, `backlog`는 배정 없이 백로그에 생성 |
| `done_when` | O | 검증 가능한 완료 조건 목록 |
| `jira_key` | X | 생성 결과이며 값이 있으면 다시 생성하지 않음 |
| `automation_id` | X | 동일 생성 요청의 재시도를 식별하는 값. 미리보기 시 자동 발급되므로 직접 작성하지 않음 |

```yaml
tasks:
  - id: jira-automation
    issue_type: Task
    summary: "[협업] Jira 작업 생성 자동화"
    description: "검토한 YAML 계획으로 Jira 업무를 생성한다."
    area: 협업
    priority: Low
    story_points: 8
    done_when:
      - "실행 전에 생성 예정 목록을 확인할 수 있다."
      - "재실행해도 업무가 중복 생성되지 않는다."
    jira_key:
```

최초 미리보기는 `jira-auto-`로 시작하는 `automation_id`를 발급해 계획 파일에 저장한다.
같은 요청을 재시도할 때는 이 파일과 식별자를 유지해야 한다. 자동화는 Jira 라벨에서
`project + automation_id`를 정확히 검색하며, 하나가 발견되면 새 업무를 만들지 않고
기존 Jira Key를 계획에 복원한다. 둘 이상 발견되면 데이터 이상으로 판단해 생성을
중단한다. 제목과 설명이 같은 새 계획에 새 식별자가 발급되는 것은 별개의 생성 요청이므로
자동으로 같은 업무로 간주하지 않는다.

같은 파일에서 Epic과 Task를 함께 만들면 Epic을 먼저 작성하고 Task의 `epic`에 Epic의
로컬 `id`를 사용한다. 생성할 항목은 `jira_key`를 비워 둔다.

같은 제목의 수동 생성 업무가 검색되면 자동화는 기본적으로 생성을 중단하고 후보 Jira
Key를 보여준다. 기존 업무가 맞는지 Jira에서 확인한 뒤 해당 Key를 `jira_key`에 기록하면
신규 생성 없이 사용할 수 있다. 실제로 별개 업무인 경우에만
`--allow-existing-summary`를 명시한다. 제목 검색은 안전장치일 뿐 의미가 유사한 모든
업무를 판별하지 않으므로, 적용 전 담당자와 열린 업무를 함께 확인한다. 자동화는 기존
수동 업무에 라벨을 임의로 추가하거나 내용을 수정하지 않는다.

모든 Task는 `JIRA_EMAIL`로 인증한 본인에게 할당된다. `sprint`를 생략하거나 `active`로
지정한 Task는 `JIRA_BOARD_ID` 보드의 활성 스프린트에 배정되며, 이때 활성 스프린트는
정확히 하나 있어야 한다. `sprint: backlog`로 지정한 Task는 스프린트에 배정하지 않고
백로그에 생성하므로, 다음 스프린트 계획에서 보드로 당겨온다. `epic`을 생략하면
`area` 또는 제목의 `[영역]`과 제목·설명·완료 조건을 기존 Epic과 비교해 가장 관련된
Epic을 선택한다. 관련 Epic을 하나로 결정할 수 없으면 새 Epic을 먼저 생성하고 계획 파일에
발급된 Epic Key를 기록한다.

스토리 포인트는 AI 에이전트 사용을 기본 개발환경으로 가정하고 코드 작성량보다 설계
복잡도, 불확실성, 영향 범위, 검증 난이도와 인간 개입량을 기준으로 산정한다. 반복적인
CRUD나 보일러플레이트처럼 기존 패턴을 그대로 적용할 수 있는 작업은 코드량이 많더라도
낮게 평가한다. 반대로 코드량이 적어도 정합성, 동시성, 보안 또는 장애 대응 판단이
필요하면 높게 평가한다. 여러 요소가 섞이면 가장 높은 위험 요소를 최종 SP의 기준으로
삼는다.

| SP | 산정 기준 |
| --- | --- |
| `1` | 단순 수정. 기존 패턴을 그대로 적용할 수 있고 설계 판단이 거의 없음 |
| `2` | 소규모 기능. 기존 패턴으로 AI가 대부분 자율 구현 가능 |
| `3` | 일반 기능. 여러 파일·계층 수정과 일부 구현 판단 및 테스트가 필요 |
| `5` | 중간 난이도. 새로운 설계 판단, 예외 처리, 통합 테스트 또는 외부 연동이 필요 |
| `8` | 높은 난이도. 동시성, 트랜잭션, 복잡한 연동 등 중요한 설계 판단과 높은 검증 비용이 필요 |
| `13` | 매우 복잡. 여러 시스템에 영향을 주거나 구조적 변경 및 높은 불확실성이 존재 |
| `21` | 범위가 지나치게 큼. 가능한 경우 여러 Story로 분할 |

구현 중 위험이나 불확실성이 최초 산정보다 커졌다면 위 기준으로 다시 평가해 SP를
조정한다. 완료된 기존 계획은 이전 형식과 호환되지만, 새로 생성할 Task에는
`1, 2, 3, 5, 8, 13, 21` 중 하나가 반드시 있어야 한다.

자동 Epic 선택은 같은 `[영역]`의 Epic이 하나면 해당 Epic을 우선 사용한다. 같은 영역의
Epic이 여러 개면 Task의 제목·설명·완료 조건과 Epic의 제목·설명에서 겹치는 단어가 가장
많은 한 개를 선택한다. 최고 점수가 동률이거나 관련 단어가 없으면 기존 Epic을 임의로
선택하지 않고 Task 제목을 기반으로 새 Epic을 만든다.

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
python scripts/jira/create_issues.py --file .jira/tmp/infra-p0.yml
```

미리보기는 Jira를 변경하지 않지만, 재시도에 사용할 `automation_id`를 임시 계획 파일에
기록한다. 미리보기 이후 실제 생성까지 같은 파일을 사용한다.

목록이 맞을 때만 실제 생성을 요청한다.

```bash
git fetch origin
python scripts/jira/create_issues.py \
  --file .jira/tmp/infra-p0.yml \
  --apply \
  --delete-on-success
```

실행 전 최신 `origin/develop`, Jira의 관련 업무와 열린 MR을 확인해 다른 작업자가 이미
진행 중인지 확인한다. 각 업무가 생성되거나 기존 업무로 확인되는 즉시 Jira Key가 계획
파일에 저장되므로 도중에 실패해도 완료된 항목은 재실행 시 건너뛴다. Jira 생성에는
`automation_id` 라벨이 포함되므로 Jira 생성 직후 응답을 받지 못한 경우에도 같은 임시
파일로 재실행하면 기존 업무를 찾을 수 있다. 자동 생성 Epic도 Task 식별자에서 파생한
별도 라벨로 중복 생성을 방지한다.

`--delete-on-success`는 모든 `jira_key`가 실제 Jira에 존재하는지 다시 확인한 다음 계획
파일을 삭제한다. 삭제 범위는 실수로 추적 문서를 지우지 않도록 `.jira/tmp/` 아래로
제한된다. 미리보기, 일부 실패, 중복 후보 발견 또는 Jira 확인 실패 시에는 파일을 보존한다.
보존된 파일을 수정하지 않고 다시 실행한다. Jira의 Epic, 우선순위 또는 필드 이름이
유효하지 않으면 API 오류를 확인하고 계획을 수정한 뒤 다시 미리보기한다.

같은 제목의 업무가 있어도 별개로 생성하기로 확인한 경우에는 다음과 같이 실행한다.

```bash
python scripts/jira/create_issues.py \
  --file .jira/tmp/infra-p0.yml \
  --apply \
  --allow-existing-summary \
  --delete-on-success
```
