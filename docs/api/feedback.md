# 사용자 의견 API

## API-084 사용자 의견 제출

- `POST /api/v1/feedback`, 회원 Access Token 필수.
- 입력: `category` (`GENERAL` 또는 `RECOMMENDATION`), `satisfaction` (`GOOD`, `MIXED`, `BAD` 또는 null), `comment` (최대 1000자, 선택), `screenPath` (앱 내부 `/app/` 절대 경로, 최대 300자), `movieId` (양수, 선택), `recommendationType` (대문자·숫자·밑줄 1~30자, 선택).
- 만족도 또는 공백이 아닌 의견 중 하나는 필수다. 개인 식별자와 제출 시각은 서버가 정한다.
- 성공: `201 {"id": 1, "createdAt": "2026-09-25T00:00:00Z"}`. 저장 이후 별도 작업이 최대 30초 간격으로 미전송 의견을 Mattermost에 전달·재시도하며, 전송 실패에도 성공 응답을 유지한다. 전송 직후 서버가 종료되면 동일 ID 알림이 중복될 수 있다.
- 오류: `400 VALIDATION_FAILED`, `401 UNAUTHORIZED`/`TOKEN_EXPIRED`.
- 의견은 `user_feedback` 테이블에 보관하며 회원 탈퇴 시 함께 삭제한다. 팀 채널 메시지에는 회원 이름·이메일을 포함하지 않는다.
