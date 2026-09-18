# 알림 API

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-070](#api-070-인앱-알림-목록) | 인앱 알림 목록 | GET | `/notifications` | 2차 MVP | 회원 |
| [API-071](#api-071-미확인-알림-개수) | 미확인 알림 개수 | GET | `/notifications/unread-count` | 2차 MVP | 회원 |
| [API-072](#api-072-알림-읽음-처리) | 알림 읽음 처리 | PATCH | `/notifications/{notificationId}/read` | 2차 MVP | 회원 |
| [API-073](#api-073-전체-알림-읽음-처리) | 전체 알림 읽음 처리 | PATCH | `/notifications/read-all` | 2차 MVP | 회원 |
| [API-074](#api-074-알림-숨김) | 알림 숨김 | DELETE | `/notifications/{notificationId}` | 2차 MVP | 회원 |

공통 URL·인증·오류·커서 규약은 [API-000](./README.md#공통-규약)을 따른다. 이 문서의 알림 종류는 알림 설정 API-017·018의 `RATING`, `RECOMMENDATION`, `MARKETING`과 별개인 실제 인앱 사건 종류다.

## 공통 알림 항목

```json
{
  "notificationId": 101,
  "type": "PARTY_INVITE",
  "title": "파티 모드 초대",
  "body": "새로운 파티 초대가 도착했어요.",
  "targetType": "PARTY_INVITATION",
  "targetId": 501,
  "readAt": null,
  "notifiedAt": "2026-09-17T09:00:00Z"
}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `notificationId` | Long | 알림 식별자 |
| `type` | `PARTY_INVITE`, `WATCH_CONFIRM` | 인앱 알림 사건 종류 |
| `title`, `body` | string | 생성 시점에 고정한 표시 문구 |
| `targetType` | `PARTY_INVITATION`, `WATCH_RECORD` | 이동 대상 종류 |
| `targetId` | Long | 초대 또는 감상 기록 ID |
| `readAt` | ISO 8601 UTC 또는 null | 최초 읽음 시각 |
| `notifiedAt` | ISO 8601 UTC | 사용자에게 노출되기 시작하는 시각 |

## API-070 인앱 알림 목록

`GET` `/api/v1/notifications`

현재 사용자에게 노출 중이고 업무 완료로 닫히지 않은 알림을 `notifiedAt DESC, notificationId DESC`로 조회한다.

### Query

- `cursor` (선택): 서버가 발급한 불투명 커서
- `size` (선택): 기본 20, 최소 1, 최대 50

### 응답 `200`

```json
{
  "items": [],
  "nextCursor": null,
  "hasNext": false
}
```

### 처리 규칙

- 파티 초대는 생성 즉시 노출하며 최대 90일 보관한다.
- 감상 확인은 OTT 클릭 2일 뒤부터 기존 감상 기록 만료 시각까지 노출한다.
- 초대 수락·거절·파티 종료와 감상 확인이 완료되면 연결 알림을 닫아 목록에서 제외한다.
- 사용자가 숨긴 알림은 목록과 미확인 개수에서 제외하되 원본 사건과 알림 행은 만료 시각까지 유지한다.
- 만료 시각이 지난 알림은 목록에서 제외하고 정리 작업이 삭제한다.
- 깨진 커서나 범위를 벗어난 `size`는 `400 VALIDATION_FAILED`다.

## API-071 미확인 알림 개수

`GET` `/api/v1/notifications/unread-count`

현재 목록에 노출 가능한 알림 중 `readAt`이 없는 개수만 반환한다.

```json
{ "count": 2 }
```

## API-072 알림 읽음 처리

`PATCH` `/api/v1/notifications/{notificationId}/read`

현재 사용자가 소유하고 노출 중인 알림의 최초 읽음 시각을 기록한다. 같은 요청을 반복해도 최초 시각을 유지하고 `204 No Content`를 반환한다. 없거나 다른 사용자의 알림, 닫힘·만료·미노출 알림은 모두 `404 RESOURCE_NOT_FOUND`다.

## API-073 전체 알림 읽음 처리

`PATCH` `/api/v1/notifications/read-all`

요청 시각에 현재 사용자에게 노출 가능한 미확인 알림을 모두 읽음 처리하고 `204 No Content`를 반환한다. 미래 알림, 닫힘·만료 알림과 다른 사용자의 알림은 변경하지 않는다.

## API-074 알림 숨김

`DELETE` `/api/v1/notifications/{notificationId}`

현재 사용자가 소유하고 노출 중인 알림에 `dismissedAt`을 기록해 목록과 미확인 개수에서 제외하고 `204 No Content`를 반환한다. 같은 요청을 반복해도 최초 숨김 시각을 유지한다. 원본 파티 초대·감상 기록과 알림 행은 삭제하지 않으며, 같은 원본 사건이 다시 처리돼도 숨김 상태를 유지한다.

없거나 다른 사용자의 알림, 숨기기 전에 이미 닫힘·만료·미노출 상태가 된 알림은 `404 RESOURCE_NOT_FOUND`다.
