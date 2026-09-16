# Jira 작업 현황 Mattermost 일일 알림

활성 스프린트의 완료되지 않은 Jira 업무를 담당자별 `진행 중`, `해야 할 일`, `미할당`으로
분류해 Mattermost 채널에 전송한다. Mattermost 서버에서 Incoming Webhook과 Personal Access
Token이 비활성화되어 있으므로, 전용 Bot 계정을 받을 때까지 제한적으로 개인 계정의 로그인
세션을 사용한다.

## 보안 경계

- `MATTERMOST_PASSWORD`는 개인 계정 전체 권한을 가진 Secret이다. 저장소, Jira, MR, 로그와
  메신저에 값을 남기지 않는다.
- 실제 값은 GitLab CI/CD Variables에만 등록하고 `Masked`, `Protected`를 활성화한다.
- 예약 Job은 Mattermost에 로그인해 임시 세션 토큰을 발급받고, 게시 성공 여부와 관계없이
  로그아웃을 시도한다. 세션 토큰과 비밀번호는 출력하지 않는다.
- 예약은 보호된 `develop` 브랜치에서만 실행한다. Maintainer 이상만 CI/CD 변수와 예약을
  관리하도록 프로젝트 권한을 제한한다.
- 관리자가 Incoming Webhook 또는 제한된 Bot 계정을 제공하면 개인 계정 로그인을 교체하고
  `MATTERMOST_PASSWORD`를 삭제한다.

## GitLab CI/CD 변수

GitLab 프로젝트의 `Settings > CI/CD > Variables`에 아래 값을 등록한다.

| 변수 | 설정 | 설명 |
| --- | --- | --- |
| `JIRA_BASE_URL` | Protected | 경로 없는 Jira HTTPS 주소 |
| `JIRA_PROJECT_KEY` | Protected | 조회할 Jira 프로젝트 Key |
| `JIRA_EMAIL` | Masked, Protected | Jira API Token 소유자 이메일 |
| `JIRA_API_TOKEN` | Masked, Protected | Jira 읽기용 API Token |
| `MATTERMOST_BASE_URL` | Protected | `https://meeting.ssafy.com` |
| `MATTERMOST_LOGIN_ID` | Masked, Protected | Mattermost 사용자명 또는 이메일. 내부 User ID가 아님 |
| `MATTERMOST_PASSWORD` | Masked, Protected | Mattermost 로그인 비밀번호 |
| `MATTERMOST_CHANNEL_ID` | Protected | 게시할 채널 ID. 설정하면 아래 두 변수가 필요 없음 |
| `MATTERMOST_TEAM_NAME` | Protected | 채널 URL의 팀 slug |
| `MATTERMOST_CHANNEL_NAME` | Protected | 채널 URL의 채널 slug |

채널 ID를 모르면 대상 채널 URL이
`https://meeting.ssafy.com/<team>/channels/<channel>` 형태인지 확인하고 `<team>`과 `<channel>`을
각각 `MATTERMOST_TEAM_NAME`, `MATTERMOST_CHANNEL_NAME`으로 등록한다. 둘 중 한 방식만 사용한다.

## 로컬 미리보기

루트 `.env`에 Jira 변수만 설정한 뒤 아래 명령을 실행한다. 이 명령은 Mattermost 로그인이나
게시를 하지 않는다.

```bash
python scripts/jira/notify_mattermost.py --dry-run
```

실제 전송은 모든 Mattermost 변수를 설정한 환경에서 `--dry-run` 없이 실행한다. 첫 전송은
개인 로컬 환경에서 대상 채널과 메시지를 확인한 다음 GitLab 예약을 활성화한다.

## GitLab 예약

MR이 `develop`에 병합된 뒤 `Build > Pipeline schedules`에서 다음 예약을 생성한다.

```text
설명: Jira Mattermost morning digest
Cron: 0 9 * * 1-5
Timezone: Asia/Seoul
Target branch: develop
Variable: JIRA_MATTERMOST_DIGEST=true
```

예약 파이프라인에서는 `jira-mattermost-digest` Job만 생성되고, Job은 단위 테스트와 CI 계약
테스트를 통과한 뒤 메시지를 게시한다. 실패한 Job을 재실행하면 이미 게시된 메시지가 중복될
수 있으므로 Mattermost 채널을 먼저 확인한다.

## 분류와 장애 처리

- JQL은 활성 스프린트에서 `statusCategory != Done`인 모든 업무를 페이지네이션해 조회한다.
- Jira 상태 카테고리 `indeterminate`는 `진행 중`, 나머지 미완료 카테고리는 `해야 할 일`로
  표시한다.
- 담당자가 없으면 마지막 `미할당` 그룹에 표시한다.
- 미완료 업무가 없어도 정상 상태임을 알리는 메시지를 한 건 게시한다.
- Jira 조회, Mattermost 로그인, 채널 조회, 게시 또는 로그아웃이 실패하면 Job을 실패시킨다.
- Mattermost 게시 길이를 고려해 15,000자를 넘는 내용은 생략 안내와 함께 제한한다.
