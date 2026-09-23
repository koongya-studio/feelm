# API 명세 — 파티

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-049](#api-049-파티-생성) | 파티 생성 | POST | `/parties` | 1차 MVP | 회원 |
| [API-050](#api-050-내-파티-목록) | 내 파티 목록 | GET | `/parties` | 1차 MVP | 회원 |
| [API-051](#api-051-파티-초대) | 파티 초대 | POST | `/parties/{partyId}/invitations` | 1차 MVP | 회원 |
| [API-052](#api-052-받은-초대-목록) | 받은 초대 목록 | GET | `/parties/invitations` | 1차 MVP | 회원 |
| [API-053](#api-053-초대-수락) | 초대 수락 | POST | `/parties/invitations/{invitationId}/accept` | 1차 MVP | 회원 |
| [API-054](#api-054-초대-거절) | 초대 거절 | POST | `/parties/invitations/{invitationId}/decline` | 1차 MVP | 회원 |
| [API-055](#api-055-파티-나가기) | 파티 나가기 | POST | `/parties/{partyId}/leave` | 1차 MVP | 회원 |
| [API-056](#api-056-파티-종료) | 파티 종료 | DELETE | `/parties/{partyId}` | 1차 MVP | 회원 |
| [API-057](#api-057-파티원-팝콘-조회) | 파티원 팝콘 조회 | GET | `/parties/{partyId}/popcorn` | 1차 MVP | 회원 |
| [API-058](#api-058-파티-영화-추천) | 파티 영화 추천 | GET | `/parties/{partyId}/recommendations` | 1차 MVP | 회원 |
| [API-083](#api-083-파티-추천-이력-조회) | 파티 추천 이력 조회 | GET | `/parties/{partyId}/recommendations/history` | 2차 MVP | 회원 |

> 2026-09-09 확정: 파티장 강퇴(API-069), 차단자의 공동 파티 탈퇴(API-020),
> PRIVATE 멤버의 파티 내 팝콘 공개(API-057). 강퇴·차단 연계 구현은
> S15P21E106-169 팝콘 조회와 별도 후속 업무로 진행한다.

| 추가 ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-069](#api-069-파티-멤버-강퇴) | 파티 멤버 강퇴 | DELETE | `/parties/{partyId}/members/{userId}` | 1차 MVP | 회원 |

## API-069 파티 멤버 강퇴

`DELETE` `/api/v1/parties/{partyId}/members/{userId}`

### 설명

활성 파티의 파티장이 다른 현재 멤버를 내보낸다(FR-20). 사용자 차단과 독립된
파티 관리 기능이며, 자기 자신은 API-055로 탈퇴한다.

### 인증 및 권한

- 회원. 현재 파티장만
- 파티 밖 요청자에게는 파티 존재를 노출하지 않는다

### Path Parameters

- `partyId`: 파티 ID
- `userId`: 강퇴할 현재 멤버 ID

### Query Parameters / Request Body

없음

### Response

`200 OK`, API-055와 동일한 파티 상태 응답

```json
{ "partyId": 9001, "status": "ACTIVE", "ownerId": 1024, "closedReason": null, "closedAt": null }
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`: 파티 없음, 요청자가 현재 멤버가 아님, 또는 대상이 현재 멤버가 아님
- `403 FORBIDDEN`: 현재 멤버인 요청자가 파티장이 아님
- `409 PARTY_CLOSED`: 이미 종료된 파티
- `400 VALIDATION_FAILED`: 자기 자신을 강퇴하려는 요청

검사 순서는 요청자의 파티 접근 권한 → 파티장 권한 → 활성 상태 → 자기 자신 여부
→ 대상의 현재 멤버 여부다. 인증 오류는 API-000을 따른다.

### 처리 규칙

- 대상의 현재 멤버 자격만 제거한다. 차단 관계를 생성하거나 다른 파티의 멤버 자격을 변경하지 않는다
- 1명 이상 남으면 파티와 파티장을 유지한다. 1인 파티도 `ACTIVE`이며 새 멤버를 초대할 수 있다
- 파티장은 자기 자신을 강퇴할 수 없으므로 강퇴만으로 0명이 되거나 파티가 종료되지는 않는다
- 멤버 제거와 인원 검사는 하나의 트랜잭션으로 처리한다
- 파티장 승계·종료·탈퇴·초대 수락과 교차 실행되어도 실행 시점의 파티장만 강퇴할 수 있고,
  파티 인원·오너 불변 조건이 유지되어야 한다
- 활성 파티가 유지되면 현재 파티원 누구나 대상을 다시 초대할 수 있다. 현재 파티원 중
  누구와든 차단 관계이면 API-051·053에 따라 초대·수락을 거절한다
- 멤버 변경에 따라 파티 추천 캐시(API-058)를 무효화한다. 과거 추천 이력은 보존한다
- 클라이언트는 실행 전에 대상 닉네임과 강퇴 사실을 확인시킨다
- 반복 요청은 대상이 이미 없으면 404, 파티가 종료됐으면 위 검사 순서에 따라 409를 반환한다

### 인수 검증

- 파티장이 다른 멤버를 강퇴하면 대상이 현재 멤버·팝콘·새 추천 집계에서 제외된다
- 일반 멤버·외부 사용자·자기 강퇴·없는 대상·종료 파티 요청은 위 오류를 반환하고 변경하지 않는다
- 3인 파티에서 강퇴하면 2인 ACTIVE, 2인 파티에서 강퇴하면 1인 ACTIVE가 된다
- 강퇴로 차단이 생기지 않고, 차단 관계가 없는 대상은 재초대·수락으로 복귀할 수 있다
- 동시 탈퇴·승계·종료·수락 및 처리 중 실패에도 인원·오너·트랜잭션 규칙을 지킨다

### 상태값 / 미정 사항

기존 PartyStatus·closedReason을 사용한다. 제품 정책 미정 사항은 없다.
잠금 방식과 도메인 조정 책임은 후속 구현 플랜에서 합의한다.

## API-049 파티 생성

`POST` `/api/v1/parties`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

함께 볼 영화를 고르기 위한 파티를 만든다. 생성자가 파티장이 된다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "name": "금요일 영화 모임" }
```

- `name` 필수, 20자 이하

### Response

`201 Created`

```json
{
  "partyId": 9001,
  "name": "금요일 영화 모임",
  "status": "ACTIVE",
  "owner": { "userId": 1024, "nickname": "팝콘러버", "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg" },
  "members": [
    { "userId": 1024, "nickname": "팝콘러버", "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg", "role": "OWNER", "joinedAt": "2026-08-30T05:21:00Z" }
  ],
  "maxMembers": 4,
  "createdAt": "2026-08-30T05:21:00Z"
}
```

### 오류 응답

- `409 ACTIVE_PARTY_LIMIT_REACHED` 참여 중인 활성 파티가 10개

### 처리 규칙

- 사용자당 참여 가능한 활성 파티는 최대 10개다. 생성과 초대 수락이 동시에 실행되어도 10개를 넘지 않도록 사용자 단위로 직렬화한다.
- 파티는 파티장 포함 최대 4명
- 생성자는 `party_members`에도 `OWNER` 역할로 저장한다
- `owner`와 `members`의 `avatarUrl`은 프로필 사진이 없으면 `null`이며, 있으면 API-014와 같은 공개·비만료 URL을 반환한다

### 상태값

- PartyStatus `ACTIVE`

### 미정 사항

없음

## API-050 내 파티 목록

`GET` `/api/v1/parties`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | 최대 10개의 활성 파티 목록 조회 |

### 설명

내가 속한 파티 목록을 조회한다. 기본은 활성 파티만 반환한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `status` (string, 선택, 기본 `ACTIVE`): `ACTIVE`, `CLOSED`

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "partyId": 9001,
      "name": "금요일 영화 모임",
      "status": "ACTIVE",
      "owner": { "userId": 1024, "nickname": "팝콘러버", "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg" },
      "members": [
        { "userId": 1024, "nickname": "팝콘러버", "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg", "role": "OWNER", "joinedAt": "2026-08-30T05:21:00Z" }
      ],
      "maxMembers": 4,
      "pendingInvitations": [
        { "invitationId": 501, "invitee": { "userId": 2048, "nickname": "영화광" }, "createdAt": "2026-08-30T05:21:00Z" }
      ],
      "createdAt": "2026-08-30T05:21:00Z"
    }
  ]
}
```

- `pendingInvitations`는 내가 현재 멤버인 파티에 포함한다
- `owner`와 `members`의 `avatarUrl`은 프로필 사진이 없으면 `null`이며, 있으면 API-014와 같은 공개·비만료 URL을 반환한다. `pendingInvitations.invitee`는 기존 필드를 유지한다

### 오류 응답

- 공통 오류만 해당. 파티가 없으면 빈 `items`와 `200`

### 처리 규칙

- 활성 파티가 여러 개일 수 있으며 생성 최신순으로 정렬한다
- `CLOSED` 파티는 `status=CLOSED`로 조회한다. 종료 파티가 추천받았던 영화는 파티 추천 이력(API-083)으로 본다
- 탈퇴(`WITHDRAWN`)한 사용자는 `owner`·`members`의 `nickname`을 `탈퇴한 사용자`로 표기하고 `userId`는 유지한다. 종료 파티는 멤버십과 마지막 오너를 이력으로 남기므로(API-013) 그 사람이 뒤에 탈퇴하면 종료 파티에서만 나타나며, 활성 파티에는 탈퇴자가 남지 않는다(API-013·API-051). 내부 치환 값 `withdrawn-{id}`는 응답에 내보내지 않고, 탈퇴자 행을 감추지도 않는다 — 이력의 인원과 오너를 그대로 보여 주기 위해서다(S15P21E106-759)

### 상태값

- PartyStatus

### 미정 사항

없음

## API-051 파티 초대

`POST` `/api/v1/parties/{partyId}/invitations`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20, FR-39 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 있음 |
| 비고 | 현재 파티원 전용 |

### 설명

닉네임 검색(API-019)으로 찾은 회원을 파티에 초대한다.

### 인증 및 권한

- 회원. 현재 파티원 누구나

### Path Parameters

- `partyId`

### Query Parameters

없음

### Request Body

```json
{ "userId": 2048 }
```

### Response

`201 Created`

```json
{
  "invitationId": 501,
  "partyId": 9001,
  "invitee": { "userId": 2048, "nickname": "영화광" },
  "status": "PENDING",
  "createdAt": "2026-08-30T05:21:00Z"
}
```

### 오류 응답

- `401 UNAUTHORIZED` 요청자가 활성 계정이 아님(탈퇴). Access Token은 계정 상태를 담지 않으므로 서버가 이 검사를 수행한다(API-013과 같은 규칙)
- `403 FORBIDDEN` 현재 파티원이 아님
- `404 RESOURCE_NOT_FOUND` 파티 없음, 대상 사용자 없음 또는 탈퇴, 차단 관계(존재하지 않는 것처럼 처리)
- `409 PARTY_CLOSED`
- `409 PARTY_FULL` 멤버 + 대기 초대가 4명 도달(가정)
- `409 ALREADY_MEMBER`
- `409 INVITATION_PENDING` 같은 사용자에게 대기 중 초대 존재

### 처리 규칙

- 초대는 자동 만료되지 않는다
- 자기 자신과 이미 참여 중인 멤버는 초대할 수 없다
- 거절한 사용자에게 다시 초대할 수 있지만 동일 사용자에게 `PENDING` 초대는 하나만 존재할 수 있다
- 초대 대상과 현재 파티원 중 누구라도 양방향 차단 관계이면 초대 후보에서 제외하고 초대 요청은 `404 RESOURCE_NOT_FOUND`로 거절한다(FR-39)
- 초대 대상이 다른 활성 파티에 있어도 초대할 수 있다. 단, 활성 파티 10개에 참여 중이면 API-053 수락을 거절한다.
- 초대 저장과 같은 트랜잭션에서 `PARTY_INVITE` 인앱 알림을 생성한다(API-070, ADR-0021). 초대 수락·거절 또는 파티 종료 시 연결 알림을 닫는다
- 판정 순서는 요청자 계정 상태(`401`) → 파티 존재(`404`) → 요청자 멤버 여부(`403`) → 파티 종료(`409`) → 대상 존재·활성(`404`) → 이미 멤버(`409`) → 차단(`404`) → 대기 초대 중복(`409`) → 정원(`409`)이다. 요청자 계정 상태가 맨 앞인 것은 탈퇴한 토큰에 파티 존재를 알리지 않기 위해서다
- 대상 계정 행을 **잠근 뒤** 활성 여부를 판정한다(S15P21E106-758). 탈퇴(API-013)의 파티 정리는 대기 초대 목록을 한 번 읽고 정리하므로, 잠금 없이 판정하면 목록을 읽은 뒤 커밋된 초대가 응답할 주체 없이 정원 한 자리를 차지한 채 남는다. 탈퇴가 같은 계정 행을 잠그므로 초대는 탈퇴보다 먼저 커밋되어 정리에 걸리거나, 탈퇴 뒤에 판정되어 `404`로 거절된다. 잠금 순서는 초대 수락(API-053)과 같은 대상 사용자 → 파티다
- 요청자의 계정 상태는 잠그지 않고 판정한다. 탈퇴한 요청자가 잔여 토큰으로 만든 초대는 파티 정리가 파티 행을 잠근 채 멤버십을 지우고 **보낸** 초대까지 `DECLINED`로 처리하므로 남지 않는다. 요청자 판정은 잔여 토큰의 조기 거절이다

### 상태값

- InvitationStatus `PENDING`

### 미정 사항

- [ ] `PARTY_FULL` 계산에 대기 초대 포함 여부(가정: 포함)

## API-052 받은 초대 목록

`GET` `/api/v1/parties/invitations`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

내가 받은 파티 초대를 조회한다. 기본은 대기 중인 초대만.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `status` (string, 선택, 기본 `PENDING`): `PENDING`, `ACCEPTED`, `DECLINED`
- `cursor`, `size`

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "invitationId": 501,
      "party": {
        "partyId": 9001,
        "name": "금요일 영화 모임",
        "owner": { "userId": 1024, "nickname": "팝콘러버" },
        "memberCount": 2
      },
      "status": "PENDING",
      "createdAt": "2026-08-30T05:21:00Z"
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 파티가 종료되면 대기 중 초대는 목록에서 제외한다(가정)
- `ACCEPTED`·`DECLINED` 이력에는 종료된 파티의 초대도 포함된다. 그 파티의 오너가 탈퇴했으면 `party.owner.nickname`을 `탈퇴한 사용자`로 표기하고 `userId`는 유지한다(API-050과 같은 규칙, S15P21E106-759)

### 상태값

- InvitationStatus

### 미정 사항

없음

## API-053 초대 수락

`POST` `/api/v1/parties/invitations/{invitationId}/accept`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

초대를 수락하고 파티에 참여한다.

### 인증 및 권한

- 회원. 초대받은 본인만

### Path Parameters

- `invitationId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`, API-049 파티 응답

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 INVITATION_NOT_PENDING` 이미 처리됨
- `409 PARTY_CLOSED`
- `409 PARTY_FULL`
- `409 ACTIVE_PARTY_LIMIT_REACHED` 참여 중인 활성 파티가 10개

### 처리 규칙

- 수락 직전에 초대받은 사용자와 현재 파티원 전원의 양방향 차단 관계를 다시 검사한다. 하나라도 있으면 `404 RESOURCE_NOT_FOUND`로 거절하며 멤버를 추가하지 않는다
- 차단·다른 초대 수락과 동시에 실행되어도 차단 관계가 같은 활성 파티에 합류하지 않도록 검사와 멤버 추가의 일관성을 보장한다
- 사용자당 참여 가능한 활성 파티는 최대 10개이며, 수락할 파티의 정원은 파티장 포함 최대 4명이다.
- 파티 생성과 여러 초대 수락이 동시에 실행되어도 사용자별 활성 파티가 10개를 넘지 않도록 사용자 행을 먼저 잠근다. 사용자와 파티를 함께 잠글 때의 순서는 사용자 → 파티다.
- 수락 시 인원이 바뀌므로 파티 추천 캐시(API-058)를 무효화한다
- 수락과 같은 트랜잭션에서 현재 파티장에게 `PARTY_INVITE_ACCEPTED` 인앱 알림을 생성한다. 알림은 해당 파티로 이동하며 Web Push 발송 대상이다(API-070, ADR-0021)
- 수락 후 파티원 전원에게 파티 정보가 갱신된다(폴링 가정)

### 상태값

- InvitationStatus `PENDING` → `ACCEPTED`

### 미정 사항

없음

## API-054 초대 거절

`POST` `/api/v1/parties/invitations/{invitationId}/decline`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

초대를 거절한다.

### 인증 및 권한

- 회원. 초대받은 본인만

### Path Parameters

- `invitationId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{ "invitationId": 501, "status": "DECLINED" }
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 INVITATION_NOT_PENDING`

### 처리 규칙

- 거절 후에는 현재 파티원 누구나 다시 초대할 수 있다

### 상태값

- InvitationStatus `PENDING` → `DECLINED`

### 미정 사항

없음

## API-055 파티 나가기

`POST` `/api/v1/parties/{partyId}/leave`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | 오너 승계 및 최소 인원 규칙 |

### 설명

파티에서 나간다. 오너가 나가더라도 충분한 멤버가 남으면 가장 먼저 가입한 멤버에게 오너를 승계한다.

### 인증 및 권한

- 회원. 파티 멤버만

### Path Parameters

- `partyId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{ "partyId": 9001, "status": "ACTIVE", "ownerId": 2048, "closedReason": null, "closedAt": null }
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 PARTY_CLOSED` 이미 종료됨

### 처리 규칙

- 오너 탈퇴 후 1명 이상 남으면 `joined_at ASC, user_id ASC` 순서의 첫 멤버를 `OWNER`로 승계하고 `parties.owner_id`도 같은 트랜잭션에서 변경한다
- 탈퇴 후 1명 이상 남으면 `ACTIVE`를 유지하며, 남은 멤버는 새 멤버를 초대할 수 있다
- 마지막 멤버가 탈퇴해 0명이 될 때만 파티를 `INSUFFICIENT_MEMBERS`로 종료하고 대기 중 초대를 모두 `DECLINED` 처리한다. 이때 `parties.owner_id`는 종료 이력의 마지막 오너를 가리킨다
- OWNER 멤버 존재와 `parties.owner_id` 일치 규칙은 `ACTIVE` 파티에 적용한다
- 종료된 파티의 추천 이력은 보존된다
- 회원 탈퇴(API-013)도 이 규칙을 그대로 쓴다. 탈퇴하면 참여 중인 활성 파티마다 이 경로가 실행되며, 승계·종료·대기 초대 무효화의 판정은 여기 한 곳에만 있다(S15P21E106-436)

### 상태값

- PartyStatus는 남은 인원에 따라 `ACTIVE` 유지 또는 `CLOSED` 전환
- `closedReason`: `INSUFFICIENT_MEMBERS`, `OWNER_CLOSED`

### 미정 사항

없음

## API-056 파티 종료

`DELETE` `/api/v1/parties/{partyId}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | 파티장 전용 |

### 설명

파티장이 파티를 종료한다.

### 인증 및 권한

- 회원. 파티장만

### Path Parameters

- `partyId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{ "partyId": 9001, "status": "CLOSED", "ownerId": 1024, "closedReason": "OWNER_CLOSED", "closedAt": "2026-08-30T05:21:00Z" }
```

### 오류 응답

- `403 FORBIDDEN`
- `404 RESOURCE_NOT_FOUND`
- `409 PARTY_CLOSED`

### 처리 규칙

- 대기 중 초대는 모두 무효화한다
- 파티 기록은 삭제하지 않고 `CLOSED`로 보존한다(추천 이력 연결)

### 상태값

- PartyStatus → `CLOSED`

### 미정 사항

없음

## API-057 파티원 팝콘 조회

`GET` `/api/v1/parties/{partyId}/popcorn`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-21 |
| 화면 | ⑧ 파티 모드, ⑧-1 파티 필름 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

활성 파티 파티원들의 팝콘 버킷을 한 화면에 보여주기 위한 데이터를 조회한다. 팝콘 기계 애니메이션은 클라이언트가 처리한다.

### 인증 및 권한

- 회원. 파티 멤버만

### Path Parameters

- `partyId`

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "partyId": 9001,
  "members": [
    {
      "user": { "userId": 1024, "nickname": "팝콘러버", "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg" },
      "totalCount": 42,
      "flavors": [
        { "flavorCode": "FLAVOR_A", "popcornCount": 12, "averageScore": 4.4 }
      ]
    }
  ]
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND` 파티 없음 또는 멤버 아님

### 처리 규칙

- 프로필 공개 범위가 `PRIVATE`이어도 같은 활성 파티의 현재 멤버에게 팝콘 집계를 노출한다. 개별 필름·프레임·평가의 공개 범위는 확대하지 않는다
- `user.avatarUrl`은 프로필 사진이 없으면 `null`이며, 있으면 API-014와 같은 공개·비만료 URL을 반환한다
- 각 파티원의 팝콘은 API-041과 같은 집계 규칙을 사용한다. 8개 맛을 항상 모두 포함하며 팝콘이 없으면 `popcornCount: 0`, `averageScore: null`로 반환한다
- 팝콘 공유·추가 비교 분석은 범위 밖(FR-21)

### 상태값

없음

### 미정 사항

없음

## API-058 파티 영화 추천

`GET` `/api/v1/parties/{partyId}/recommendations`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-22, FR-13 |
| 화면 | ⑧-1 파티 필름 |
| 미정 사항 | 없음 |
| 비고 | 파티 전용 Python Consumer의 `party-mean-fit-v2` 결과를 Spring이 최신성 검증·필터링해 제공 |

### 설명

파티원 전체의 취향을 고려해 함께 보기 좋은 영화를 추천한다.

### 인증 및 권한

- 회원. 파티 멤버만

### Path Parameters

- `partyId`

### Query Parameters

- `size` (integer, 기본 3, 최대 10). 한 묶음의 카드 수
- `refresh` (boolean, 기본 `false`). `true`면 **다시 받기**로, 아직 노출하지 않은 후보로 다음 묶음을 만든다. `false`면 현재 노출 중인 묶음을 그대로 돌려준다. boolean으로 해석할 수 없는 값은 `400 VALIDATION_FAILED`

### Request Body

없음

### Response

`200 OK`

```json
{
  "recommendationSetId": 7101,
  "partyId": 9001,
  "generatedAt": "2026-08-30T05:21:00Z",
  "items": [
    {
      "recommendationItemId": 3401,
      "type": "PARTY",
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://...", "ottProviders": ["NETFLIX"] }
    }
  ],
  "hasNext": true
}
```

- `hasNext`: 다시 받기로 더 보여줄 후보가 남아 있으면 `true`. 이 응답의 노출 처리 뒤 세트에 남은 미노출 후보 중 현재 파티원 기준으로 유효한 것이 하나라도 있는지로 판단한다. `false`면 더 보여줄 유효 후보가 없는 상태(저장 후보를 모두 소진했거나 남은 후보가 전부 파티원 기준으로 제외됨)이므로 화면은 다시 받기 버튼을 감추거나 더 없다고 안내한다. 제외된 후보가 평가 철회 등으로 되살아나면 다시 `true`가 될 수 있다

### 오류 응답

- `400 VALIDATION_FAILED`
- `404 RESOURCE_NOT_FOUND`
- `409 PARTY_CLOSED`
- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- 파티 전용 Python Consumer가 파티원별 Candidate 영화 ID 합집합을 재점수화해 `feelm:party:{partyId}:candidates`에 순위대로 게시한다. Spring은 알고리즘 버전이나 점수를 해석하지 않고 게시된 순서를 유지한다
- Spring은 **묶음을 새로 고르는 시점**(세트 생성, 첫 노출, 다시 받기)에 현재 파티원 중 한 명이라도 평가, 확정 감상(`WATCHED_UNRATED`·`RATED`), 관심없음 처리한 영화와 서비스 DB에 없는 영화를 제외한다. 이미 노출 중인 묶음은 파티원이 그 사이 무엇을 해도 바꾸지 않는다 — 파티원 행동을 조회마다 실시간으로 반영하지 않고 다시 받기 때 반영한다. 개인 추천 API-045의 빈 슬롯 보충과 다른 규칙이다
- **저장 후보와 노출 묶음**: 세트를 만들 때 Redis 후보 중 유효한 것을 순위대로 최대 50편 `recommendation_items`에 저장한다. 세트가 생긴 뒤에는 새 추천을 계산하지 않고 이 저장분을 다시 받기로 소진한다. 한 요청이 노출한 항목은 같은 `exposed_at`을 받으며, 세트에서 가장 최근 `exposed_at`과 같은 항목들이 **현재 묶음**이다. 세트가 파티 단위이므로 이 커서는 파티원 사이에 공유된다. 별도 커서 컬럼이나 스키마 변경은 없다
- **일반 조회**(`refresh=false`): 현재 묶음을 저장된 그대로 `position` 순 `size`개 돌려준다. 묶음이 `size`보다 적어도 보충하지 않고, 쓰기도 없다. 묶음의 크기는 그 묶음을 만든 요청의 `size`이며, 더 작은 `size`로 조회하면 앞부분만 보이고 나머지는 노출 처리된 상태로 남아 다시 볼 수 없으므로 호출 간 같은 `size`를 쓴다. 노출 이력이 없는 세트만 유효한 후보를 `position` 순으로 `size`개 골라 첫 묶음으로 노출한다. 노출 이력이 없고 유효한 후보도 없으면 `503 RECOMMENDATION_UNAVAILABLE`
- **다시 받기**(`refresh=true`): 아직 노출하지 않은 후보 중 유효한 것을 `position` 순으로 `size`개 골라 새 `exposed_at`으로 노출하고 그 항목만 돌려준다. 미노출만 고르므로 직전 응답과 겹치지 않는다. 남은 후보가 `size`보다 적으면 남은 만큼만 돌려주고 `hasNext`는 `false`다. 노출 이력이 없는 세트의 다시 받기는 첫 노출과 같다
- **소진**: 미노출 유효 후보가 없는 다시 받기는 커서를 움직이지 않고 일반 조회와 같은 결과(현재 묶음 + `hasNext: false`)를 돌려준다. 같은 `inputId`로 새 세트를 만들지 않으며, 파티원이 평가·관심없음·감상 확정을 하거나 파티원이 바뀌어 `inputId`가 달라질 때만 새 세트가 생긴다. 마지막 묶음이 사라지면 이 API로는 볼 방법이 없으므로 빈 목록을 돌려주지 않는다(노출된 항목 전체는 파티 추천 이력 API-083으로 본다)
- **동시성**: 묶음 조회, 노출 기록, `hasNext` 계산은 모두 파티 행 잠금 안의 한 트랜잭션에서 수행한다. 같은 파티의 다시 받기가 동시에 들어오면 직렬화되어 각각 다음 묶음을 노출하며, 이후 조회는 마지막에 노출한 묶음을 돌려준다. 노출 이력과 커서가 어긋나지 않는 것을 보장하고, 두 번 눌린 다시 받기를 하나로 합치지는 않는다
- **기존 세트**: 이 규칙 이전에 만들어진 세트(후보 10편, 노출 시각이 요청마다 다를 수 있음)에도 같은 규칙을 적용한다. 가장 최근 시각의 항목만 현재 묶음이 되어 카드가 `size`보다 적게 보일 수 있고, 다시 받기를 누르면 정상 크기의 다음 묶음이 나온다. 더 오래된 노출 항목은 다시 보이지 않는다
- **Rating Candidate 버전**은 파티원 한 명의 Candidate 점수 집합이 몇 번째로 갱신된 것인지 나타내는 값이다. Python Fold-in Consumer가 그 사용자의 평가·온보딩 이벤트를 반영해 Redis의 사용자별 Candidate 점수를 갱신할 때 함께 발급·기록하며, 저장 위치는 해당 사용자 Candidate 키와 같은 Redis 네임스페이스다. Spring은 값을 읽기만 한다(ADR-0017)
- 파티 입력 `inputId`는 `partyId` + 모델 버전 + 정렬된 파티원·각 Candidate 생성 세대로 결정한다. 파티 인원이나 생성 세대가 바뀌면 새 계산을 요청한다. Spring은 파티 Consumer 결과의 `inputId`와 모델 버전이 현재 입력과 같을 때만 결과를 사용한다
- Redis의 점수 통계·`memberFit`·`fallback` 진단 필드는 API-058 판단과 응답에 사용하지 않는다
- 동일 `partyId`·`inputId`·`modelVersion` 요청은 기존 추천 세트 이력을 재사용한다. 다시 받기도 이 세트 안에서 미노출 후보를 소진하며 세트를 추가로 만들지 않는다
- 결과는 `recommendation_sets.party_id`가 설정된 파티 추천 이력으로 저장한다. 노출한 항목의 `exposed_at`은 되돌리지 않는다
- 노출 처리는 같은 트랜잭션에서 파티 추천 이력 정본 `party_movie_exposures`(파티·영화별 최신 노출)를 갱신한다. 이력 조회(API-083)는 이 표를 읽는다(S15P21E106-815)

### 상태값

- RecommendationType `PARTY`

파티 추천은 개인 추천 이력 API(API-048)에 포함하지 않는다. 파티가 노출한 항목의 이력은 파티 추천 이력 조회(API-083)로 본다.

## API-083 파티 추천 이력 조회

`GET` `/api/v1/parties/{partyId}/recommendations/history`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-22, FR-33 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 없음 |
| 비고 | 종료 파티 포함. API-058이 노출한 항목의 이력 |

### 설명

파티가 지금까지 추천받은(화면에 노출된) 영화를 세트 최신순으로 조회한다. 파티가 종료된 뒤에도 조회할 수 있어, 내 파티 목록(API-050)에서 종료 파티를 골라 그때 추천받은 영화를 돌아보는 용도다. 개인 추천 이력(API-048)과 별개이며 개인 이력에는 파티 추천이 포함되지 않는다.

### 인증 및 권한

- 회원. 그 파티의 멤버십 행이 있는 사용자. 파티 상태(`ACTIVE`·`CLOSED`)는 보지 않는다. 파티장이 종료한 파티(`OWNER_CLOSED`)는 멤버십이 이력으로 남아 종료 당시의 멤버가 읽을 수 있다. 마지막 멤버가 나가서 종료된 파티(`INSUFFICIENT_MEMBERS`)는 나가기가 멤버십을 지운 뒤 종료하므로 읽을 수 있는 사람이 없다(API-055). 활성 파티에서 나가거나 강퇴된 사용자도 멤버십이 없어 읽지 못한다
- 멤버가 아니면 파티 존재를 노출하지 않는다

### Path Parameters

- `partyId`

### Query Parameters

- `cursor`, `size` (세트 단위. 기본 20, 최대 50)

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    {
      "recommendationSetId": 7102,
      "generatedAt": "2026-09-20T10:00:00Z",
      "items": [
        {
          "recommendationItemId": 3501,
          "type": "PARTY",
          "exposedAt": "2026-09-20T10:01:00Z",
          "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://...", "releaseYear": 1999, "genres": ["드라마"], "averageRating": 4.3, "ottProviders": ["NETFLIX"] }
        }
      ]
    }
  ],
  "nextCursor": "...",
  "hasNext": true
}
```

- `movie`는 API-058과 같은 영화 요약이다. 장르·평점·OTT는 조회 시점 값이며 이력 스냅샷이 아니다
- `exposedAt`은 그 항목이 화면에 나간 시각이다. 다시 받기(API-058)로 한 번에 노출된 항목들은 같은 값을 가지므로 클라이언트가 묶음으로 나눠 보일 수 있다
- 사용자 표기 필드가 없으므로 탈퇴 사용자 표기 규칙(API-050·052)은 해당 없다

### 오류 응답

- `400 VALIDATION_FAILED` `cursor` 해독 실패, `size`가 1~50 밖이거나 정수가 아님
- `404 RESOURCE_NOT_FOUND` 파티 없음 또는 멤버 아님

### 처리 규칙

- **노출된 항목만** 포함한다. 저장만 되고 화면에 나가지 않은 후보(`exposed_at`이 없는 항목)는 이력이 아니다
- 세트 최신순은 세트 ID 내림차순이다. `generatedAt`은 표시값이며 서버 시계가 역전되어도 순서를 바꾸지 않는다(API-048과 같은 규칙)
- 세트 안 항목은 `exposedAt` 오름차순, 같으면 `position` 순이다. 다시 받기 이전에 만들어진 세트(항목마다 노출 시각이 다를 수 있음)도 같은 규칙으로 시간순이 된다
- **같은 영화가 여러 세트에 노출되었으면 그 파티에서 가장 최신 세트의 항목 1건만 반환한다.** 파티원이 평가하거나 파티원이 바뀌어 새 세트가 생기면 상위 후보가 다시 노출되기 때문이다. 더 오래된 노출은 `exposed_at`을 보존한 채 응답에서만 제외한다. 이 판정은 조회 때 계산하지 않고 API-058이 노출 시점에 `party_movie_exposures`(파티·영화별 최신 노출, 개인 이력의 `user_movie_exposures`와 같은 구조)에 정리해 둔 것을 읽는다
- 남는 항목이 없는 세트는 `items`에 포함하지 않는다. `size`는 이 필터를 적용한 뒤의 세트 수이며 `cursor`·`nextCursor`·`hasNext`의 의미는 바뀌지 않는다. 중복 판정이 세트 정렬에만 의존하므로 어느 페이지를 조회해도 결과가 같고 페이지를 넘겨도 같은 영화가 다시 나오지 않는다
- 개인 이력의 `reason`·`dismissed`·`watchStatus`는 싣지 않는다. PARTY 항목의 이유는 빈 객체이고, 나머지 둘은 요청자 개인 기준 값이라 파티가 함께 보는 이력의 의미와 맞지 않는다
- 이 API는 읽기만 한다. `exposed_at`을 기록하거나 되돌리지 않으며 API-058의 현재 묶음·다시 받기·`hasNext`에 영향을 주지 않는다
- 파티가 종료되어도 세트는 보존되므로(ERD) 종료 파티의 이력이 그대로 나온다. 종료 파티에서 API-058은 여전히 `409 PARTY_CLOSED`다
- 파티 구성이 이전 상태로 돌아가면(파티원이 나갔다가 돌아옴) 추천 입력도 이전 값으로 돌아가 예전 세트가 다시 서빙된다. 그 세트가 새로 노출한 영화 중 더 새 세트에 이미 기록된 영화는 이력에서 위치가 바뀌지 않는다 — "최신 세트 1건" 규칙은 세트 ID 기준이며 노출 시각 기준이 아니다

### 상태값

- RecommendationType `PARTY`

### 미정 사항

없음
