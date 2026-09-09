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
- 2명 이상이 남으면 파티와 파티장을 유지한다. 1명만 남으면 API-055와 같이
  `INSUFFICIENT_MEMBERS`로 종료하고 대기 초대를 모두 `DECLINED` 처리한다
- 멤버 제거·인원 검사·필요한 종료·초대 무효화는 하나의 트랜잭션으로 처리한다
- 파티장 승계·종료·탈퇴·초대 수락과 교차 실행되어도 실행 시점의 파티장만 강퇴할 수 있고,
  파티 인원·오너 불변 조건이 유지되어야 한다
- 활성 파티가 유지되면 현재 파티원 누구나 대상을 다시 초대할 수 있다. 현재 파티원 중
  누구와든 차단 관계이면 API-051·053에 따라 초대·수락을 거절한다
- 멤버 변경에 따라 파티 추천 캐시(API-058)를 무효화한다. 과거 추천 이력은 보존한다
- 클라이언트는 실행 전에 대상 닉네임과 강퇴 사실을 확인시키고, 2인 파티라면 종료됨을 안내한다
- 반복 요청은 대상이 이미 없으면 404, 파티가 종료됐으면 위 검사 순서에 따라 409를 반환한다

### 인수 검증

- 파티장이 다른 멤버를 강퇴하면 대상이 현재 멤버·팝콘·새 추천 집계에서 제외된다
- 일반 멤버·외부 사용자·자기 강퇴·없는 대상·종료 파티 요청은 위 오류를 반환하고 변경하지 않는다
- 3인 파티에서 강퇴하면 2인 ACTIVE, 2인 파티에서 강퇴하면 CLOSED/INSUFFICIENT_MEMBERS가 된다
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
  "owner": { "userId": 1024, "nickname": "팝콘러버" },
  "members": [
    { "userId": 1024, "nickname": "팝콘러버", "role": "OWNER", "joinedAt": "2026-08-30T05:21:00Z" }
  ],
  "maxMembers": 4,
  "createdAt": "2026-08-30T05:21:00Z"
}
```

### 오류 응답

- 공통 오류만 해당.

### 처리 규칙

- 사용자당 참여 가능한 활성 파티 수는 제한하지 않는다 (D-3 결정).
- 파티는 파티장 포함 최대 4명
- 생성자는 `party_members`에도 `OWNER` 역할로 저장한다

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
| 비고 | 복수 활성 파티(D-3 결정)에 따라 목록 조회 |

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
      "owner": { "userId": 1024, "nickname": "팝콘러버" },
      "members": [
        { "userId": 1024, "nickname": "팝콘러버", "role": "OWNER", "joinedAt": "2026-08-30T05:21:00Z" }
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

### 오류 응답

- 공통 오류만 해당. 파티가 없으면 빈 `items`와 `200`

### 처리 규칙

- 활성 파티가 여러 개일 수 있으며 생성 최신순으로 정렬한다 (D-3 결정)
- `CLOSED` 파티는 `status=CLOSED`로 조회한다(추천 이력 확인용)

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

- `403 FORBIDDEN` 현재 파티원이 아님
- `404 RESOURCE_NOT_FOUND` 파티 없음, 대상 사용자 없음, 차단 관계(존재하지 않는 것처럼 처리)
- `409 PARTY_CLOSED`
- `409 PARTY_FULL` 멤버 + 대기 초대가 4명 도달(가정)
- `409 ALREADY_MEMBER`
- `409 INVITATION_PENDING` 같은 사용자에게 대기 중 초대 존재

### 처리 규칙

- 초대는 자동 만료되지 않는다
- 자기 자신과 이미 참여 중인 멤버는 초대할 수 없다
- 거절한 사용자에게 다시 초대할 수 있지만 동일 사용자에게 `PENDING` 초대는 하나만 존재할 수 있다
- 초대 대상과 현재 파티원 중 누구라도 양방향 차단 관계이면 초대 후보에서 제외하고 초대 요청은 `404 RESOURCE_NOT_FOUND`로 거절한다(FR-39)
- 초대 대상이 다른 활성 파티에 있어도 초대할 수 있다. 수락 시 대상의 참여 파티 수는 제한하지 않는다 (D-3 결정).
- 초대 알림 전달 방식은 공통 규약 미정 사항 참조(폴링 가정)

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

### 처리 규칙

- 수락 직전에 초대받은 사용자와 현재 파티원 전원의 양방향 차단 관계를 다시 검사한다. 하나라도 있으면 `404 RESOURCE_NOT_FOUND`로 거절하며 멤버를 추가하지 않는다
- 차단·다른 초대 수락과 동시에 실행되어도 차단 관계가 같은 활성 파티에 합류하지 않도록 검사와 멤버 추가의 일관성을 보장한다
- 사용자당 참여 파티 수는 제한하지 않지만, 수락할 파티의 정원은 파티장 포함 최대 4명이다.
- 수락 시 인원이 바뀌므로 파티 추천 캐시(API-058)를 무효화한다
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

- 오너 탈퇴 후 2명 이상이 남으면 `joined_at ASC, user_id ASC` 순서의 첫 멤버를 `OWNER`로 승계하고 `parties.owner_id`도 같은 트랜잭션에서 변경한다
- 탈퇴 후 1명만 남으면 파티를 `INSUFFICIENT_MEMBERS`로 종료하고 대기 중 초대를 모두 `DECLINED` 처리한다
- 종료된 파티의 추천 이력은 보존된다

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
{ "partyId": 9001, "status": "CLOSED", "closedReason": "OWNER_CLOSED", "closedAt": "2026-08-30T05:21:00Z" }
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
      "user": { "userId": 1024, "nickname": "팝콘러버" },
      "totalCount": 42,
      "flavors": [
        { "flavorCode": "FLAVOR_X", "popcornCount": 12, "averageScore": 4.4 }
      ]
    }
  ]
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND` 파티 없음 또는 멤버 아님

### 처리 규칙

- 프로필 공개 범위가 `PRIVATE`이어도 같은 활성 파티의 현재 멤버에게 팝콘 집계를 노출한다. 개별 필름·프레임·평가의 공개 범위는 확대하지 않는다
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
| 미정 사항 | 있음 |
| 비고 | 알고리즘 비교 후 확정 |

### 설명

파티원 전체의 취향을 고려해 함께 보기 좋은 영화를 추천한다.

### 인증 및 권한

- 회원. 파티 멤버만

### Path Parameters

- `partyId`

### Query Parameters

- `size` (integer, 기본 3, 최대 10)

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
      "movie": { "movieId": 550, "title": "파이트 클럽", "posterUrl": "https://...", "ottProviders": ["NETFLIX"] },
      "reason": {
        "summary": "모두가 좋아하는 스릴러",
        "memberFit": [
          { "userId": 1024, "score": 0.91 },
          { "userId": 2048, "score": 0.78 }
        ]
      }
    }
  ]
}
```

### 오류 응답

- `404 RESOURCE_NOT_FOUND`
- `409 PARTY_CLOSED`
- `503 RECOMMENDATION_UNAVAILABLE`

### 처리 규칙

- Spring이 Redis의 파티원별 Candidate 점수를 합쳐 요청 시 파티 점수를 계산한다. 별도 ML 추론 서비스는 사용하지 않는다(ADR-0017)
- 파티원 중 한 명이라도 감상 기록이 있는 영화는 제외한다(가정)
- **Rating Candidate 버전**은 파티원 한 명의 Candidate 점수 집합이 몇 번째로 갱신된 것인지 나타내는 값이다. Python Fold-in Consumer가 그 사용자의 평가·온보딩 이벤트를 반영해 Redis의 사용자별 Candidate 점수를 갱신할 때 함께 발급·기록하며, 저장 위치는 해당 사용자 Candidate 키와 같은 Redis 네임스페이스다. Spring은 값을 읽기만 한다(ADR-0017)
- 파티 점수 캐시 키는 `partyId` + 파티원 목록 + 파티원별 Rating Candidate 버전으로 구성한다. 파티 인원이나 파티원의 Rating Candidate 버전이 바뀌면 재계산하고, 입력 버전이 같으면 캐시된 결과를 반환한다
- 검증 지표: Average·Least Misery 대비 최저 만족도 상승, 만족도 편차 감소, 평균 만족도 유지(FR-22)
- 결과는 `recommendation_sets.party_id`가 설정된 파티 추천 이력으로 저장한다

### 상태값

- RecommendationType `PARTY`

### 미정 사항

- [ ] 집계 알고리즘(Average / Least Misery / 개선식) 확정
- [ ] 감상 영화 제외 기준(한 명이라도 vs 전원)
- [ ] 파티 추천을 개인 추천 이력에 포함할지
