# 알림 API

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-070](#api-070-인앱-알림-목록) | 인앱 알림 목록 | GET | `/notifications` | 2차 MVP | 회원 |
| [API-071](#api-071-미확인-알림-개수) | 미확인 알림 개수 | GET | `/notifications/unread-count` | 2차 MVP | 회원 |
| [API-072](#api-072-알림-읽음-처리) | 알림 읽음 처리 | PATCH | `/notifications/{notificationId}/read` | 2차 MVP | 회원 |
| [API-073](#api-073-전체-알림-읽음-처리) | 전체 알림 읽음 처리 | PATCH | `/notifications/read-all` | 2차 MVP | 회원 |
| [API-074](#api-074-알림-숨김) | 알림 숨김 | DELETE | `/notifications/{notificationId}` | 2차 MVP | 회원 |
| [API-075](#api-075-web-push-공개-설정-조회) | Web Push 공개 설정 조회 | GET | `/notifications/push/config` | 2차 MVP | 회원 |
| [API-076](#api-076-web-push-구독-등록갱신) | Web Push 구독 등록·갱신 | PUT | `/notifications/push/subscriptions` | 2차 MVP | 회원 |
| [API-077](#api-077-web-push-구독-해제) | Web Push 구독 해제 | DELETE | `/notifications/push/subscriptions/{subscriptionId}` | 2차 MVP | 회원 |

공통 URL·인증·오류·커서 규약은 [API-000](./README.md#공통-규약)을 따른다. 이 문서의 알림 종류는 알림 설정 API-017·018의 수신 설정 키와 별개인 실제 인앱 사건 종류다. 수신 설정 `PARTY_INVITE`·`REPORT_ISSUED`가 꺼진 사용자에게는 같은 이름의 알림을 만들지 않는다.

## 공통 알림 항목

```json
{
  "notificationId": 101,
  "type": "PARTY_INVITE",
  "title": "파티팝콘 초대",
  "body": "새로운 파티팝콘 초대가 도착했어요.",
  "targetType": "PARTY_INVITATION",
  "targetId": 501,
  "readAt": null,
  "notifiedAt": "2026-09-17T09:00:00Z"
}
```

| 필드 | 형식 | 설명 |
|---|---|---|
| `notificationId` | Long | 알림 식별자 |
| `type` | `PARTY_INVITE`, `PARTY_INVITE_ACCEPTED`, `WATCH_CONFIRM`, `REPORT_ISSUED` | 인앱 알림 사건 종류 |
| `title`, `body` | string | 생성 시점에 고정한 표시 문구 |
| `targetType` | `PARTY_INVITATION`, `PARTY`, `WATCH_RECORD`, `TASTE_REPORT` | 이동 대상 종류 |
| `targetId` | Long | 초대·파티·감상 기록 또는 리포트(`reportId`) ID |
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

- 파티 초대는 생성 즉시 노출하며 최대 90일 보관한다. 초대받은 사람의 수신 설정 `PARTY_INVITE`(API-017)가 꺼져 있으면 알림을 만들지 않는다.
- 초대 수락은 수락 즉시 현재 파티장에게 노출하며 최대 90일 보관한다.
- 리포트 발급은 반기 취향 리포트가 공개(`READY`)되는 순간 리포트 주인에게 노출하며 최대 90일, 단 첫 발급(API-080)을 기록할 수 있는 다음 반기 공개 시각까지만 보관한다. 그 시각이 이미 지난 늦은 공개에는 만들지 않는다. 대상은 `TASTE_REPORT`(`targetId`=`reportId`)다. 이미 공개된 리포트에 소급해 만들지 않고, 활성 계정이 아니거나 수신 설정 `REPORT_ISSUED`가 꺼져 있으면 만들지 않는다. 자동으로 닫지 않는다.
- 감상 확인은 OTT 클릭 24시간 뒤 앱에서 알림 목록을 다시 조회할 때부터 기존 감상 기록 만료 시각까지 노출한다.
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

## API-075 Web Push 공개 설정 조회

`GET` `/api/v1/notifications/push/config`

서비스 워커가 구독 생성에 사용할 공개 VAPID 키를 활성 회원 로그인 후 조회한다. 비활성화 상태에서는 공개키를 노출하지 않는다.

```json
{ "enabled": true, "publicKey": "<base64url VAPID public key>" }
```

## API-076 Web Push 구독 등록·갱신

`PUT` `/api/v1/notifications/push/subscriptions`

브라우저 `PushSubscription`을 현재 사용자에게 멱등 등록한다. 같은 endpoint는 한 사용자에게만 귀속되며 다른 계정에서 다시 등록하면 새 계정으로 안전하게 이전한다.

```json
{
  "endpoint": "https://fcm.googleapis.com/fcm/send/...",
  "expirationTime": null,
  "keys": { "p256dh": "<base64url>", "auth": "<base64url>" }
}
```

응답 `200`: `{ "subscriptionId": 42 }`

- endpoint는 HTTPS이며 서버 허용 목록의 브라우저 Push Service 호스트여야 한다. fragment는 허용하지 않고 scheme·host 대소문자와 기본 `:443`을 canonicalize한 값으로 동일 구독을 판정한다.
- `p256dh`는 secp256r1 곡선 위의 uncompressed P-256 공개키 65바이트, `auth`는 16바이트의 base64url 값이다.
- `expirationTime`은 없거나 현재보다 미래인 Unix epoch milliseconds다.
- 비활성화된 환경은 `503 PUSH_UNAVAILABLE`, 잘못된 endpoint·키는 `400 VALIDATION_FAILED`다.

## API-077 Web Push 구독 해제

`DELETE` `/api/v1/notifications/push/subscriptions/{subscriptionId}`

현재 사용자의 구독을 비활성화하고 endpoint와 암호화 키 원문을 즉시 지운 뒤 대기 발송을 취소한다. 성공은 `204 No Content`, 없거나 다른 사용자의 구독은 `404 RESOURCE_NOT_FOUND`다. 같은 구독을 다시 해제하는 요청도 `404`다.

## Web Push 발송 규칙

- `PARTY_INVITE`와 `PARTY_INVITE_ACCEPTED`는 모든 활성 구독에 생성 즉시 발송한다.
- `WATCH_CONFIRM`과 `REPORT_ISSUED`는 Web Push로 발송하지 않고 앱 진입 시 인앱 알림 목록 조회로만 노출한다.
- 읽음·숨김·원본 완료·만료·설정 해제된 알림은 대기 또는 lease 중인 발송을 취소한다.
- 알림·canonical endpoint 조합을 유일하게 저장해 중복 enqueue를 막는다. worker는 실제 전송 직전에 한 건씩 lease한다. 일시 오류는 1분부터 지수 backoff로 최대 5회 시도하며, Push Service의 404·410 응답은 응답을 보낸 capability version이 현재 버전과 같을 때만 구독 만료로 처리한다.
- 전송 성공과 DB 완료 기록은 하나의 transaction으로 묶을 수 없으므로 성공 응답 직후 프로세스가 중단되면 lease 회수 뒤 같은 payload가 한 번 더 전달될 수 있다. 서비스 워커는 `notificationId` 기준 중복 표시를 억제해야 한다.
- payload는 공통 알림 항목의 `notificationId`, `type`, `title`, `body`, `targetType`, `targetId`를 담는다. 서비스 워커는 `targetType`과 `targetId`로 앱 화면을 연다.
