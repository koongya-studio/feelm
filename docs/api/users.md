# API 명세 — 사용자

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-001](#api-001-이메일-회원가입) | 이메일 회원가입 | POST | `/auth/signup` | 1차 MVP | 공개 |
| [API-002](#api-002-이메일-인증-확인) | 이메일 인증 확인 | POST | `/auth/email/verify` | 1차 MVP | 공개 |
| [API-003](#api-003-인증-메일-재발송) | 인증 메일 재발송 | POST | `/auth/email/resend` | 1차 MVP | 공개 |
| [API-004](#api-004-이메일-로그인) | 이메일 로그인 | POST | `/auth/login` | 1차 MVP | 공개 |
| [API-005](#api-005-로그아웃) | 로그아웃 | POST | `/auth/logout` | 1차 MVP | 회원 |
| [API-006](#api-006-토큰-재발급) | 토큰 재발급 | POST | `/auth/token/refresh` | 1차 MVP | 공개 |
| [API-007](#api-007-소셜-로그인) | 소셜 로그인 | POST | `/auth/oauth/{provider}` | 1차 MVP | 공개 |
| [API-008](#api-008-소셜-회원가입-완료) | 소셜 회원가입 완료 | POST | `/auth/oauth/signup` | 1차 MVP | 공개 |
| [API-009](#api-009-소셜-계정-연결) | 소셜 계정 연결 | POST | `/users/me/oauth/{provider}` | 1차 MVP | 회원 |
| [API-010](#api-010-비밀번호-재설정-요청) | 비밀번호 재설정 요청 | POST | `/auth/password/reset-request` | 1차 MVP | 공개 |
| [API-011](#api-011-비밀번호-재설정) | 비밀번호 재설정 | POST | `/auth/password/reset` | 1차 MVP | 공개 |
| [API-012](#api-012-비밀번호-변경) | 비밀번호 변경 | PUT | `/users/me/password` | 1차 MVP | 회원 |
| [API-013](#api-013-회원-탈퇴) | 회원 탈퇴 | DELETE | `/users/me` | 1차 MVP | 회원 |
| [API-014](#api-014-내-프로필-조회) | 내 프로필 조회 | GET | `/users/me` | 1차 MVP | 회원 |
| [API-015](#api-015-내-프로필-수정) | 내 프로필 수정 | PATCH | `/users/me` | 1차 MVP | 회원 |
| [API-016](#api-016-닉네임-사용-가능-확인) | 닉네임 사용 가능 확인 | GET | `/users/nickname-availability` | 1차 MVP | 공개 |
| [API-017](#api-017-알림-설정-조회) | 알림 설정 조회 | GET | `/users/me/notification-settings` | 1차 MVP | 회원 |
| [API-018](#api-018-알림-설정-변경) | 알림 설정 변경 | PUT | `/users/me/notification-settings` | 1차 MVP | 회원 |
| [API-019](#api-019-사용자-검색) | 사용자 검색 | GET | `/users/search` | 1차 MVP | 회원 |
| [API-020](#api-020-사용자-차단) | 사용자 차단 | POST | `/users/{userId}/block` | 1차 MVP | 회원 |
| [API-021](#api-021-사용자-차단-해제) | 사용자 차단 해제 | DELETE | `/users/{userId}/block` | 1차 MVP | 회원 |
| [API-022](#api-022-차단-목록-조회) | 차단 목록 조회 | GET | `/users/me/blocks` | 1차 MVP | 회원 |

## API-001 이메일 회원가입

`POST` `/api/v1/auth/signup`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ② 회원가입 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

이메일·비밀번호로 가입을 요청한다. 가입 직후 계정은 `PENDING_VERIFICATION` 상태이며 이메일 인증(API-002)을 마쳐야 로그인할 수 있다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | string | O | RFC 5322 형식, 소문자 정규화 |
| `password` | string | O | 8~64자. 영문·숫자·특수문자를 각각 1자 이상 포함. 공백 불가 |
| `nickname` | string | O | 2~10자. 한글·영문·숫자·`_`만 허용(공백 불가). 공백·대소문자 무시 중복 검사 |
| `birthDate` | string | O | 생년월일 `YYYY-MM-DD` (D-8 결정). 실명은 수집하지 않음 |
| `gender` | string | O | `MALE` / `FEMALE` / `OTHER`. 가입 시 필수이며, DB 컬럼은 탈퇴 익명화를 위해 NULL을 허용 |

```json
{
  "email": "user@example.com",
  "password": "p@ssw0rd!",
  "nickname": "팝콘러버",
  "birthDate": "1999-03-14",
  "gender": "FEMALE"
}
```

### Response

`201 Created`

```json
{
  "userId": 1024,
  "email": "user@example.com",
  "nickname": "팝콘러버",
  "status": "PENDING_VERIFICATION"
}
```

### 오류 응답

- `400 VALIDATION_FAILED` 형식 오류
- `409 EMAIL_DUPLICATED` 이미 가입된 이메일
- `409 NICKNAME_DUPLICATED` 닉네임 중복
- `429 RATE_LIMITED` IP 단위 요청 제한 초과

### 처리 규칙

- 요청 제한은 IP 단위로 적용하며(기본 30건/1시간) 카운터는 인증 메일 재발송(API-003)·비밀번호 재설정 요청(API-010)과 공유한다. 세 경로가 같은 발송 수단을 쓰므로 한 경로만 막으면 발송 총량을 묶을 수 없다. 이메일 단위 간격은 두지 않는다. 같은 이메일의 반복 가입은 중복 검사가 막는다
- 제한은 중복 검사보다 먼저 적용한다. `429`가 이메일·닉네임의 사용 여부를 알아내는 수단이 되지 않아야 한다
- IP 단위 요청 제한은 요청 경로에서 판정하므로 fail-closed다. 카운터 저장소에 닿지 못하면 가입 요청 자체가 `500 INTERNAL_ERROR`로 실패한다. 장애 중 발송 폭탄을 허용하지 않는 방향을 API-003·API-010과 맞춘 결과이며, 아래 전역 발송 상한(발송 시점 판정)과는 실패 방식이 다르다
- 가입 성공 시 인증 메일을 발송한다. 인증 토큰 유효기간 24시간
- 메일 발송은 응답과 분리해 처리한다. 발송 실패·유실은 응답을 바꾸지 않으며 복구 경로는 인증 메일 재발송(API-003)이다
- 계정 메일 전체에 전역 발송 상한이 있다. 상한을 넘긴 시점의 인증 메일은 발송하지 않으며 이 응답은 바뀌지 않는다(API-003 처리 규칙의 발송 상한과 같은 값)
- 발송 상한을 확인할 수 없으면(저장소 장애) 인증 메일을 보내지 않는다. 상한은 발송 시점에 보므로 이 경로만으로는 응답이 `201` 그대로이고 복구 경로는 인증 메일 재발송(API-003)이다. 단 같은 장애에서는 위 IP 단위 요청 제한이 먼저 걸려 요청이 `500`이 되므로, 저장소 장애 중 실제로 관측되는 응답은 `500`이다
- 미인증 상태로 24시간이 지난 계정은 기존 행을 삭제한 뒤 같은 이메일의 재가입을 허용한다. 인증을 마친 계정은 대상이 아니다
- 비밀번호는 bcrypt 등 단방향 해시로 저장한다
- 실명은 수집하지 않는다

### 상태값

- 계정 상태: `PENDING_VERIFICATION` → `ACTIVE` (API-002 성공 시)

### 미정 사항

- [ ] 미인증 계정 정리 주기 — 재가입 허용 규칙은 확정됐고, 남아 있는 미인증 행을 주기적으로 지우는 배치는 후속 업무에서 정한다

## API-002 이메일 인증 확인

`POST` `/api/v1/auth/email/verify`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ③ 회원가입 완료 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

인증 메일의 토큰으로 이메일 소유를 확인하고 계정을 활성화한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "token": "kV9tQ2xZbG5fUjRhN3RXcw" }
```

### Response

`200 OK`

```json
{ "userId": 1024, "status": "ACTIVE" }
```

### 오류 응답

- `400 VERIFICATION_TOKEN_INVALID` 토큰 형식 오류 또는 위조
- `410 VERIFICATION_TOKEN_EXPIRED` 만료. 재발송(API-003) 안내
- `409 ALREADY_VERIFIED` 이미 활성화된 계정

### 처리 규칙

- 토큰은 1회용이며 사용 즉시 무효화한다
- 토큰은 서버가 발급한 불투명 난수 문자열이다. 클라이언트가 해석할 수 있는 정보를 담지 않으며 서버는 해시만 저장한다
- 인증 메일 링크는 프론트 페이지로 이동하고 프론트가 이 API를 호출한다

### 상태값

- 계정 상태 `PENDING_VERIFICATION` → `ACTIVE`

### 미정 사항

없음

## API-003 인증 메일 재발송

`POST` `/api/v1/auth/email/resend`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ② 회원가입 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

미인증 계정에 인증 메일을 다시 보낸다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "email": "user@example.com" }
```

### Response

`202 Accepted` (본문 없음)

### 오류 응답

- `429 RATE_LIMITED` 요청 제한 초과(이메일 단위 간격, IP 단위 중 어느 축이든)

### 처리 규칙

- 존재하지 않는 이메일, 이미 활성화된 계정이어도 `202`로 응답한다(계정 존재 여부 노출 방지)
- 요청 제한은 두 축으로 적용한다. 이메일 단위 최소 간격(기본 1분)과 IP 단위(기본 30건/1시간)이며, 값은 설정 `feelm.mail.rate-limit`으로 관리하므로 환경마다 다를 수 있다
- IP 카운터는 비밀번호 재설정 요청(API-010)과 공유한다. 두 경로가 같은 발송 수단을 쓰므로 한쪽만 막으면 발송 총량을 묶을 수 없다. 이메일 단위 간격은 경로별로 분리한다
- IP 축의 예산은 주소 하나가 아니라 집계 단위로 나눈다. IPv4는 주소 단위(`/32`), IPv6는 프리픽스 단위(기본 `/64`)이며 값은 설정 `feelm.auth.client-ip`로 관리한다. IPv6 클라이언트가 주소만 바꿔 예산을 새로 얻는 것을 막기 위해서다. 발신지를 알 수 없는 요청은 한 버킷의 예산을 공유한다
- 클라이언트 IP는 신뢰 목록 안의 프록시가 보낸 `X-Real-IP`일 때만 그 값을 쓰고, 그 밖에서 직접 온 요청의 헤더와 `X-Forwarded-For`는 무시한다. 헤더로 예산 주체를 바꿀 수 없어야 한다([ADR-0019](../adr/0019-reverse-proxy-client-ip-trust.md))
- 위 두 축은 계정 존재 여부와 실제 발송 여부와 무관하게 요청 시점에 적용한다. `429`로 계정 유무를 구분할 수 없어야 하고, 발송한 요청만 셌다면 카운터 소모 여부가 가입 여부를 알려 주는 신호가 된다
- 이와 별도로 계정 메일 전체(가입 인증·재발송·재설정)에 **전역 발송 상한**이 있다. 발송 계정의 쿼터를 지키는 값이라 시도가 아니라 실제 발송을 세며, 기본값은 100건/1시간과 300건/24시간이고 설정 `feelm.mail.send-budget`으로 관리한다
- 전역 발송 상한은 요청이 아니라 발송 시점에 적용하므로 **응답을 바꾸지 않는다**. 상한을 넘긴 시점의 메일은 발송하지 않고 버리며 응답은 `202` 그대로다. 사용자의 재요청이 복구 경로이고, 이 상한 때문에 `429`가 되지는 않는다. 요청 시점에 적용하면 메일을 한 통도 보내지 않는 요청만으로 상한을 소진시켜 정상 사용자의 계정 복구를 막을 수 있고, 응답이 바뀌는 순간 실제 발송을 세는 카운터가 계정 존재 여부를 알려 주는 신호가 된다
- 다만 상한이 없애는 것은 **응답 채널의 신호**뿐이다. 예산을 상한 직전까지 채운 뒤 대상 이메일과 자기 이메일을 차례로 요청하면, 자기 메일의 도착 여부로 대상 계정의 존재를 1비트 알아낼 수 있다. 탐침 한 번마다 전역 예산을 거의 소진시켜야 하고 그 상태는 경고 로그로 드러나므로 감수한다
- 전역 상한에 걸린 동안에는 **새 토큰을 발급하지 않는다**. 이미 보낸 링크를 무효화하고 새 메일도 보내지 않으면 사용자가 가진 유효한 링크만 사라지기 때문이다. 이 확인은 계정 존재 여부와 무관하게 모든 요청에 같은 방식으로 적용한다
- 발송 상한을 확인할 수 없으면(저장소 장애) 메일을 보내지 않는다. 상한은 발송 시점에 보므로 이 경로만으로는 응답이 `202` 그대로다. 단 요청 단위 제한도 fail-closed라서 같은 장애에서는 그 판정이 먼저 걸려 요청이 `500 INTERNAL_ERROR`가 되므로, 저장소 장애 중 실제로 관측되는 응답은 `500`이다
- 어느 축에서 걸렸든 응답의 상태와 본문은 같고 `Retry-After`는 주지 않는다. 걸린 축이나 남은 시간을 알려 주면 우회를 돕는다
- 이전 토큰은 무효화하고 새 토큰을 발급한다
- 미인증 상태로 24시간이 지난 계정에는 발송하지 않고 `202`로 응답한다. 그 계정은 재가입(API-001)이 삭제할 수 있으므로 재가입이 복구 경로다
- 메일 발송은 응답과 분리해 처리한다. 발송을 기다리면 계정 존재 여부가 응답 시간으로 드러나므로, 발송 결과는 응답에 영향을 주지 않는다. 발송 실패·유실은 다시 요청해 복구한다. 발송을 분리한 뒤에도 계정 유무에 따른 토큰 발급·저장 수 ms 차이는 남으며 감수한다

### 상태값

없음

### 미정 사항

없음

## API-004 이메일 로그인

`POST` `/api/v1/auth/login`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ⑫ 로그인 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

이메일·비밀번호로 로그인하고 토큰을 발급한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "email": "user@example.com", "password": "p@ssw0rd!" }
```

### Response

`200 OK` + `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Lax`

```json
{
  "accessToken": "eyJhbGciOi...",
  "expiresIn": 1800,
  "user": {
    "userId": 1024,
    "nickname": "팝콘러버",
    "onboardingCompleted": false
  }
}
```

### 오류 응답

- `401 INVALID_CREDENTIALS` 이메일 또는 비밀번호 불일치(어느 쪽인지 구분하지 않음)
- `403 EMAIL_NOT_VERIFIED` 미인증 계정
- `429 RATE_LIMITED` 이메일 단위 연속 실패 제한(기본 5회/10분) 또는 IP 단위 시도 제한(기본 10분 60회·1시간 300회)

### 처리 규칙

- `onboardingCompleted`가 `false`면 프론트는 ④ 취향 초기 설정으로 이동한다
- 탈퇴한 계정은 `INVALID_CREDENTIALS`로 응답한다
- 연속 실패가 한도(기본 5회)까지는 `401`이고 그 다음 요청부터 `429`다. 로그인에 성공하면 실패 횟수를 초기화한다
- 시도 한도와 창 길이는 설정 `feelm.auth.login`(기본 5회/10분)으로 관리하므로 환경마다 다를 수 있다. 카운터는 실패가 아니라 시도를 세므로 한도를 넘는 요청은 비밀번호가 맞아도 대조 없이 `429`다
- 제한은 이메일 축과 클라이언트 IP 축을 함께 본다. 이메일 축만으로는 비밀번호 하나를 서로 다른 계정에 한 번씩 넣는 시도(password spraying)와 남의 계정을 반복해서 잠그는 시도가 어디에도 세지지 않는다
- **평가 순서는 IP → 이메일**이다. IP 한도를 넘긴 요청은 비밀번호를 대조하지 않고 `429`이며 그 계정의 실패 횟수도 올리지 않는다. 공유 IP의 정상 사용자가 남의 예산 때문에 `429`를 받아도 자기 계정까지 잠기지 않게 하는 순서다. 반대로 이메일 축은 IP와 무관하게 적용되므로 IP를 바꿔가며 시도해도 계정별 한도는 그대로다
- **IP 축도 실패가 아니라 시도를 센다.** 확인과 기록을 하나의 원자적 증가로 합쳐야 동시 요청이 몰려도 한 창에서 비밀번호 대조가 한도만큼만 일어나고, 카운터 소모 여부가 계정 존재나 비밀번호 일치를 알려 주는 신호가 되지 않는다. 로그인 성공은 이메일 카운터만 초기화하고 IP 카운터는 남긴다
- IP 한도는 설정 `feelm.auth.login.ip` 목록(기본 10분 60회, 1시간 300회)이며 창을 늘리거나 줄일 수 있다. 정상 로그인도 예산을 소모하므로 값은 공유 NAT(캠퍼스망, 시연 환경, 모바일 CGNAT)에서 로그인이 한꺼번에 몰리는 구간을 통과하도록 정했다
- IP 축의 예산은 메일 축(API-003)과 같은 집계 단위로 나눈다. IPv4는 주소 단위(`/32`), IPv6는 프리픽스 단위(기본 `/64`)이며 값은 설정 `feelm.auth.client-ip`로 관리한다. 같은 단위를 쓰지 않으면 IPv6 클라이언트가 같은 `/64` 안에서 주소만 바꿔 로그인 예산을 새로 얻는다. 발신지를 알 수 없는 요청은 한 버킷의 예산을 공유한다
- 클라이언트 IP는 신뢰 목록 안의 프록시가 보낸 `X-Real-IP`일 때만 그 값을 쓰고, 그 밖에서 직접 온 요청의 헤더와 `X-Forwarded-For`는 무시한다. 헤더로 예산 주체를 바꿀 수 없어야 한다([ADR-0019](../adr/0019-reverse-proxy-client-ip-trust.md))
- 대가: 같은 IP를 쓰는 제3자가 예산을 고갈시키면 그 IP의 정상 사용자도 비밀번호가 맞아도 `429`다. 이메일 축에서 `429`가 된 요청과 미인증 계정의 반복 로그인도 IP 예산을 소모하므로, 잠긴 계정의 재시도가 같은 IP의 다른 사용자에게 번질 수 있다
- 두 축의 `429`는 코드와 본문이 같아 어느 축에서 막혔는지도, 그 이메일이 가입돼 있는지도 드러내지 않는다

### 상태값

없음

### 미정 사항

없음

## API-005 로그아웃

`POST` `/api/v1/auth/logout`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-01 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

현재 세션의 Refresh Token을 폐기하고 쿠키를 삭제한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`204 No Content` + `Set-Cookie: refresh_token=; Max-Age=0`

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- Refresh Token은 서버 저장소(Redis, 가정)에서 삭제한다
- Access Token은 만료까지 유효하다. 즉시 차단이 필요하면 블랙리스트를 검토한다

### 상태값

없음

### 미정 사항

없음

## API-006 토큰 재발급

`POST` `/api/v1/auth/token/refresh`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | - |
| 미정 사항 | 없음 |
| 비고 | 쿠키의 refresh_token 사용 |

### 설명

쿠키의 Refresh Token으로 새 Access Token을 발급한다.

### 인증 및 권한

- 공개 (Access Token 불필요, `refresh_token` 쿠키 필수)

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK` + 새 `refresh_token` 쿠키(rotation)

```json
{ "accessToken": "eyJhbGciOi...", "expiresIn": 1800 }
```

### 오류 응답

- `401 REFRESH_TOKEN_INVALID` 없음·위조·이미 사용됨
- `401 REFRESH_TOKEN_EXPIRED` 만료. 재로그인 필요

### 처리 규칙

- 사용된 Refresh Token은 즉시 폐기하고 새 토큰을 발급한다
- 이미 사용된 토큰이 재사용되면 해당 사용자의 모든 Refresh Token을 폐기한다(탈취 대응)

### 상태값

없음

### 미정 사항

없음

## API-007 소셜 로그인

`POST` `/api/v1/auth/oauth/{provider}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-35 |
| 화면 | ⑫ 로그인, ② 회원가입 |
| 미정 사항 | 없음 |
| 비고 | 신규 사용자는 signupToken 반환 |

### 설명

OAuth 인가 코드를 받아 소셜 계정을 확인한다. 이미 연결된 사용자는 로그인 처리하고, 연결된 사용자가 없으면 추가 정보 입력용 `signupToken`을 반환한다(API-008로 이어짐).

### 인증 및 권한

- 공개

### Path Parameters

- `provider`: `google`, `kakao`, `naver`

### Query Parameters

없음

### Request Body

```json
{ "code": "4/0AX4XfWh...", "redirectUri": "https://feelm.app/oauth/callback" }
```

### Response

기존 사용자 — `200 OK`, API-004와 동일한 본문 + `refresh_token` 쿠키

신규 사용자 — `200 OK`

```json
{
  "signupRequired": true,
  "signupToken": "eyJhbGciOi...",
  "suggestedNickname": "kakao_user"
}
```

### 오류 응답

- `400 OAUTH_CODE_INVALID` 코드 교환 실패
- `400 UNSUPPORTED_PROVIDER` 목록에 없거나 자격 증명이 설정되지 않은 제공자
- `400 VALIDATION_FAILED` 허용 목록에 없는 `redirectUri`
- `400 OAUTH_EMAIL_REQUIRED` 제공자가 이메일을 제공하지 않아 가입을 이어갈 수 없음
- `401 INVALID_CREDENTIALS` 연결된 계정이 탈퇴 등으로 로그인할 수 없는 상태
- `502 OAUTH_PROVIDER_ERROR` 제공자 응답 오류

### 처리 규칙

- 소셜 계정의 이메일이 기존 이메일 계정과 같아도 **자동 통합하지 않는다**. 이때 별도 신규 가입은 `users.email`
  유일 제약 때문에 만들 수 없으므로, 가입(API-008)을 `409 EMAIL_DUPLICATED`로 거절하고 이메일 로그인 후
  연결(API-009)을 안내한다 (S15P21E106-150 확정)
- 인가 코드는 **백엔드가 제공자와 교환한다** (S15P21E106-150 확정)
- `redirectUri`는 환경별 허용 목록과 **정확히 일치**해야 하며, 목록 밖의 값은 제공자를 호출하지 않고 거절한다.
  허용 목록이 비어 있으면 모든 요청을 거절한다 (S15P21E106-150 확정)
- 제공자가 이메일을 주지 않으면 계정을 만들 수 없으므로 이 단계에서 `400 OAUTH_EMAIL_REQUIRED`로 거절한다.
  제공자가 이메일 소유 확인을 마치지 않은 경우(Google `email_verified=false`, Kakao
  `is_email_verified=false`)도 이메일이 없는 것과 같게 취급한다. 연결(API-009)은 이메일이 필요 없어 이 검사를
  하지 않는다 (S15P21E106-150 확정)
- 자격 증명이 설정되지 않은 제공자는 서비스 기동을 막지 않고 `400 UNSUPPORTED_PROVIDER`로 응답한다
  (S15P21E106-150 확정)
- `signupToken` 유효기간 10분(가정, 설정값 `feelm.jwt.signup-token-ttl`), 제공자 식별자(`providerUserId`)를 담는다
- `suggestedNickname`은 제공자 닉네임이 닉네임 규칙(2~10자)을 만족할 때만 그 값을 쓰고, 아니면 규칙에 맞는
  값으로 대체한다
- 로그인 응답의 `onboardingCompleted`는 API-004와 동일하게 처리

### 상태값

- OAuthProvider: `GOOGLE`, `KAKAO`, `NAVER`

### 미정 사항

없음

## API-008 소셜 회원가입 완료

`POST` `/api/v1/auth/oauth/signup`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-35 |
| 화면 | ② 회원가입 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

`signupToken`과 닉네임·나이·성별을 받아 소셜 계정으로 새 사용자를 만들고 로그인 처리한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `signupToken` | string | O | API-007이 발급한 가입 토큰 |
| `nickname` | string | O | 2~10자. 한글·영문·숫자·`_`만 허용(공백 불가). 공백·대소문자 무시 중복 검사 |
| `birthDate` | string | O | 생년월일 `YYYY-MM-DD` (D-8 결정). 실명은 수집하지 않음 |
| `gender` | string | O | `MALE` / `FEMALE` / `OTHER`. 가입 시 필수이며, DB 컬럼은 탈퇴 익명화를 위해 NULL을 허용 |

```json
{
  "signupToken": "eyJhbGciOi...",
  "nickname": "팝콘러버",
  "birthDate": "1999-03-14",
  "gender": "FEMALE"
}
```

### Response

`201 Created`, API-004 로그인 응답과 동일 본문 + `refresh_token` 쿠키

### 오류 응답

- `400 VALIDATION_FAILED`
- `401 SIGNUP_TOKEN_INVALID` 만료 또는 위조
- `409 EMAIL_DUPLICATED` 같은 이메일의 계정이 이미 있음(자동 통합하지 않음)
- `409 NICKNAME_DUPLICATED`
- `409 OAUTH_ACCOUNT_ALREADY_LINKED` 토큰 발급 후 다른 계정에 연결된 경우

### 처리 규칙

- 소셜 가입 계정은 이메일 인증 없이 즉시 `ACTIVE`
- 이메일과 제공자 식별자는 `signupToken`에서 읽는다. 요청 본문으로 받지 않는다 (S15P21E106-150 확정)
- 같은 이메일의 계정이 이미 있으면 통합하지 않고 `409 EMAIL_DUPLICATED`로 거절한다. 이메일로 로그인한 뒤
  계정 연결(API-009)로 같은 결과를 얻는다. 단, 그 계정이 미인증 상태로 보존 기간(24시간)이 지났다면
  이메일 가입(API-001)과 같은 규칙으로 지우고 소셜 가입을 허용한다 (S15P21E106-150 확정)
- 비밀번호가 없으므로 비밀번호 변경(API-012)은 불가, 재설정(API-010)으로 이메일 로그인 수단을 추가할 수 있는지는 미정

### 상태값

없음

### 미정 사항

없음

## API-009 소셜 계정 연결

`POST` `/api/v1/users/me/oauth/{provider}`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-35 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

로그인한 사용자의 계정에 소셜 로그인 수단을 추가로 연결한다.

### 인증 및 권한

- 회원

### Path Parameters

- `provider`: `google`, `kakao`, `naver`

### Query Parameters

없음

### Request Body

```json
{ "code": "4/0AX4XfWh...", "redirectUri": "https://feelm.app/oauth/link-callback" }
```

### Response

`201 Created`

```json
{ "provider": "KAKAO", "linkedAt": "2026-08-30T05:21:00Z" }
```

### 오류 응답

- `400 UNSUPPORTED_PROVIDER` 목록에 없거나 자격 증명이 설정되지 않은 제공자
- `400 VALIDATION_FAILED` 허용 목록에 없는 `redirectUri`
- `409 OAUTH_ACCOUNT_ALREADY_LINKED` 해당 소셜 계정이 다른 사용자에 연결됨
- `409 PROVIDER_ALREADY_LINKED` 같은 provider가 이미 연결됨
- `502 OAUTH_PROVIDER_ERROR` 제공자 응답 오류

### 처리 규칙

- 연결된 수단 목록은 API-014 내 프로필의 `linkedProviders`로 조회한다
- 제공자가 이메일을 주지 않아도 연결은 성공한다. 이미 있는 계정에 로그인 수단만 더하기 때문이다
  (S15P21E106-150 확정)
- `redirectUri` 허용 목록과 미설정 제공자 처리는 API-007과 같다 (S15P21E106-150 확정)

### 상태값

없음

### 미정 사항

- [ ] 연결 해제(DELETE) 제공 여부. FR-35에 없음. 마지막 로그인 수단은 해제 불가 규칙 필요

## API-010 비밀번호 재설정 요청

`POST` `/api/v1/auth/password/reset-request`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-37 |
| 화면 | ⑫ 로그인 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

비밀번호 재설정 링크를 이메일로 보낸다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "email": "user@example.com" }
```

### Response

`202 Accepted` (본문 없음)

### 오류 응답

- `429 RATE_LIMITED` 요청 제한 초과(이메일 단위 간격, IP 단위 중 어느 축이든)

### 처리 규칙

- 계정 존재 여부와 무관하게 `202`로 응답한다
- 요청 제한은 인증 메일 재발송(API-003)과 같은 두 축이다. 이메일 단위 최소 간격(기본 1분)과 IP 단위이며, IP 카운터는 API-003과 공유한다. 값은 설정 `feelm.mail.rate-limit`으로 관리한다
- IP 축의 집계 단위와 클라이언트 IP 판정은 API-003과 같다([ADR-0019](../adr/0019-reverse-proxy-client-ip-trust.md))
- 위 두 축은 계정 존재 여부와 실제 발송 여부와 무관하게 요청 시점에 적용한다. `429`로 계정 유무를 구분할 수 없어야 한다
- 계정 메일 전체의 전역 발송 상한(API-003 처리 규칙)도 함께 적용된다. 그 상한은 발송 시점에 적용되어 응답을 바꾸지 않으며, 상한을 넘긴 시점의 메일은 발송되지 않고 재요청이 복구 경로다
- 상한에 걸린 동안에는 새 재설정 토큰을 발급하지 않아 이미 보낸 링크가 그대로 유효하다. 상한을 확인할 수 없으면(저장소 장애) 메일을 보내지 않으며, 저장소 장애 중의 응답은 API-003과 같다(요청 단위 제한이 fail-closed라 `500`)
- 어느 축에서 걸렸든 응답의 상태와 본문은 같고 `Retry-After`는 주지 않는다
- 재설정 토큰 유효기간 30분(가정), 1회용
- 이전 미사용 토큰은 무효화하고 새 토큰을 발급한다
- 메일 발송은 응답과 분리해 처리한다. 발송을 기다리면 계정 존재 여부가 응답 시간으로 드러나므로, 발송 결과는 응답에 영향을 주지 않는다. 발송 실패·유실은 간격이 지난 뒤 다시 요청해 복구한다. 발송을 분리한 뒤에도 계정 유무에 따른 토큰 발급·저장 수 ms 차이는 남으며 감수한다
- 발송 대상은 이메일 로그인 수단을 가진 활성 계정뿐이다. 미인증 계정과 비밀번호가 없는 계정에는 발송하지 않고 `202`로 응답한다. 미인증 계정의 복구 경로는 인증 메일 재발송(API-003)이며, 소셜 전용 계정에 이 경로로 이메일 로그인 수단을 추가할지는 API-008 처리 규칙의 미정 사항이므로 열지 않는다

### 상태값

없음

### 미정 사항

없음

## API-011 비밀번호 재설정

`POST` `/api/v1/auth/password/reset`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-37 |
| 화면 | ⑫ 로그인 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

재설정 토큰으로 새 비밀번호를 설정한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "token": "eyJhbGciOi...", "newPassword": "n3wP@ss!" }
```

### Response

`204 No Content`

### 오류 응답

- `400 VALIDATION_FAILED` 비밀번호 정책 위반
- `400 RESET_TOKEN_INVALID`
- `410 RESET_TOKEN_EXPIRED`

### 처리 규칙

- 성공 시 해당 사용자의 모든 Refresh Token을 폐기한다(전 기기 로그아웃)
- 검증 순서는 토큰(존재·사용·만료)이 먼저이고 계정 상태가 나중이다. 만료·재사용된 토큰으로 계정 상태를 알 수 없어야 한다
- 탈퇴한 계정의 토큰은 `RESET_TOKEN_INVALID`로 응답한다. 탈퇴 여부를 오류 코드로 구분할 수 없어야 한다

### 상태값

없음

### 미정 사항

없음

## API-012 비밀번호 변경

`PUT` `/api/v1/users/me/password`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-37 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

로그인한 이메일 계정 사용자가 현재 비밀번호를 확인한 뒤 새 비밀번호로 변경한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "currentPassword": "p@ssw0rd!", "newPassword": "n3wP@ss!" }
```

### Response

`204 No Content`

### 오류 응답

- `400 VALIDATION_FAILED`
- `401 INVALID_CREDENTIALS` 현재 비밀번호 불일치
- `409 PASSWORD_NOT_SET` 소셜 전용 계정(비밀번호 없음)

### 처리 규칙

- 성공 시 해당 사용자의 모든 Refresh Token을 폐기한다(전 기기 로그아웃). 09-07 `현재 세션을 제외한 폐기(가정)`에서 정정 — 이 엔드포인트는 현재 세션을 식별할 수 없다. Refresh Token 쿠키의 `Path`가 `/api/v1/auth`라 이 경로의 요청에는 실리지 않고, Access Token에는 세션 식별자가 없다(ADR-0009). 클라이언트는 변경 후 새 비밀번호로 다시 로그인한다
- 성공 시 해당 사용자의 미사용 비밀번호 재설정 토큰(API-010/API-011)도 모두 무효화한다. 재설정 메일이 유출된 상태에서 이 경로로 비밀번호를 바꿔도 남은 링크가 계속 유효해서는 안 되기 때문이다
- 탈퇴한 계정의 Access Token은 잔여 수명 안이라도 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않으므로 이 검사를 서버가 수행한다(로그인은 `INVALID_CREDENTIALS`, 재설정은 `RESET_TOKEN_INVALID`로 같은 방향으로 막는다)

### 상태값

없음

### 미정 사항

없음

## API-013 회원 탈퇴

`DELETE` `/api/v1/users/me`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-37 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 있음 |
| 비고 | - |

### 설명

계정을 탈퇴 처리한다. 연결된 모든 로그인 수단(이메일·소셜)에 적용된다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "password": "p@ssw0rd!" }
```

- 소셜 전용 계정은 `password` 생략, 대신 최근 로그인(10분 이내, 가정)을 요구한다

### Response

`204 No Content` + `refresh_token` 쿠키 삭제

### 오류 응답

- `401 INVALID_CREDENTIALS`

### 처리 규칙

- 사용자 상태를 `WITHDRAWN`으로 바꾸고 모든 토큰을 폐기한다
- 이메일·닉네임을 익명화 값으로 치환하고 `password_hash`·`birth_date`·`gender`를 비운다. 연결된 소셜 로그인 수단(`user_auth_providers`)과 인증·비밀번호 재설정 토큰은 삭제한다
- 치환으로 유일 제약이 풀리므로 같은 이메일·닉네임으로 즉시 재가입할 수 있다. 유예기간과 탈퇴 철회는 두지 않는다
- 내부·공개 식별자와 다른 사용자 데이터의 연결이 남으므로 이메일·닉네임 치환만으로 완전한 익명화를 전제하지 않는다. 계정 처리 목적이 끝난 생년월일·성별은 함께 삭제한다(D-9)
- 평가·한줄평·프레임 등 개인 데이터는 탈퇴 시 삭제하고, 추천 학습용 익명 평가 데이터 보존 여부는 미정
- 참여 중인 파티에서 제외되고 파티 인원 변경 규칙(FR-20)이 적용된다
- 탈퇴자가 오너이고 2명 이상이 남으면 최초 가입자에게 오너를 승계하며, 1명만 남으면 파티를 종료한다

### 상태값

- 계정 상태 → `WITHDRAWN`

### 미정 사항

- [x] 탈퇴 후 데이터 보존 범위·기간 — 계정 영역은 확정(이메일·닉네임 치환, 즉시 재사용, 생년월일·성별 삭제). 평가·학습 데이터 범위는 아래 항목에서 계속
- [ ] 공개 한줄평은 삭제 vs '탈퇴한 사용자'로 익명 유지

## API-014 내 프로필 조회

`GET` `/api/v1/users/me`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-38 |
| 화면 | ⑪ 프로필 대시보드, ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

로그인한 사용자의 프로필과 설정 요약을 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{
  "userId": 1024,
  "email": "user@example.com",
  "nickname": "팝콘러버",
  "birthDate": "1999-03-14",
  "gender": "FEMALE",
  "visibility": "PRIVATE",
  "hasPassword": true,
  "linkedProviders": ["KAKAO"],
  "onboardingCompleted": true,
  "stats": { "frameCount": 42, "popcornCount": 42, "ratingCount": 42 },
  "createdAt": "2026-08-01T00:00:00Z"
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- `stats`의 프레임 수와 평가 수는 1:1이며, 팝콘 수는 평가를 영화의 대표 맛별로 집계한 값이다
- 활성 계정이 아니면 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-012와 같은 판정)

### 상태값

- Visibility: `PUBLIC`, `PRIVATE`

### 미정 사항

없음

## API-015 내 프로필 수정

`PATCH` `/api/v1/users/me`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-38 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

닉네임, 나이, 성별, 프로필 공개 범위를 부분 수정한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

전달한 필드만 변경한다.

```json
{ "nickname": "새닉네임", "visibility": "PUBLIC" }
```

### Response

`200 OK`, API-014와 동일 본문

### 오류 응답

- `400 VALIDATION_FAILED`
- `409 NICKNAME_DUPLICATED` (공백·대소문자 무시 비교, FR-01과 동일 규칙)

### 처리 규칙

- `visibility`를 `PRIVATE`으로 바꾸면 공개 프로필(API-062)은 즉시 `404`가 된다
- 전달한 필드만 바꾸되 `nickname`·`birthDate`·`gender`는 가입 시 필수값이므로 `null`로 되돌릴 수 없다. `null`을 보내면 `400 VALIDATION_FAILED`로 응답한다
- `visibility`도 같은 이유로 `null`을 허용하지 않는다. 필드를 아예 보내지 않으면 변경하지 않는다
- `nickname` 형식 규칙은 가입(API-001)과 같다(2~10자, 한글·영문·숫자·`_`)
- 자기 현재 닉네임과 정규화 값이 같으면 중복으로 보지 않는다. 대소문자만 바꾸는 변경이 자기 자신 때문에 `409`가 되지 않게 한다
- 활성 계정이 아니면 `401 UNAUTHORIZED`로 거절한다(API-014와 같은 판정)
- 이메일은 변경 불가

### 상태값

- Visibility: `PUBLIC`, `PRIVATE` (기본값 `PRIVATE`)

### 미정 사항

없음

## API-016 닉네임 사용 가능 확인

`GET` `/api/v1/users/nickname-availability`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01, FR-38 |
| 화면 | ② 회원가입, ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

닉네임 중복 여부를 가입·수정 전에 확인한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

- `nickname` (string, 필수)

### Request Body

없음

### Response

`200 OK`

```json
{ "nickname": "팝콘러버", "available": false, "reason": "DUPLICATED" }
```

- `reason`: `DUPLICATED`, `INVALID_FORMAT`, `null`(사용 가능)

### 오류 응답

- `400 VALIDATION_FAILED` 파라미터 누락

### 처리 규칙

- 형식 검사를 먼저 하고 그다음 중복을 본다. 두 사유에 모두 걸리는 값은 `INVALID_FORMAT`으로 응답한다
- 형식 규칙은 가입(API-001)·수정(API-015)과 같다(2~10자, 한글·영문·숫자·`_`). 형식 위반은 `400`이 아니라 `available: false`인 `200`이다
- 비교 시 공백 제거·소문자 정규화 후 비교한다
- 확인 결과는 예약이 아니므로 가입 시점에 다시 검사한다
- 인증을 보지 않으므로 **호출자 자신의 현재 닉네임도 `DUPLICATED`로 응답한다.** 값이 바뀌지 않았거나 대소문자만 다른 경우 클라이언트가 확인을 호출하지 않는다. 수정(API-015)은 그 값을 허용하므로 두 API의 판정이 이 입력군에서만 다르다

### 상태값

없음

### 미정 사항

없음

## API-017 알림 설정 조회

`GET` `/api/v1/users/me/notification-settings`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-38 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 있음 |
| 비고 | 알림 발송 기능 FR 부재 |

### 설명

평가·추천·마케팅 알림 수신 설정을 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

없음

### Response

`200 OK`

```json
{ "RATING": true, "RECOMMENDATION": true, "MARKETING": false }
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 기본값: `RATING` true, `RECOMMENDATION` true, `MARKETING` false(가정)

### 상태값

- NotificationType: `RATING`, `RECOMMENDATION`, `MARKETING`

### 미정 사항

- [ ] 실제 알림 발송 채널(인앱·이메일·푸시)이 FR에 없음. 설정만 저장하는지 확정 필요(FR-38 비고)

## API-018 알림 설정 변경

`PUT` `/api/v1/users/me/notification-settings`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-38 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

알림 수신 설정을 전체 교체한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

```json
{ "RATING": true, "RECOMMENDATION": false, "MARKETING": false }
```

### Response

`200 OK`, API-017과 동일 본문

### 오류 응답

- `400 VALIDATION_FAILED` 세 키 중 누락

### 처리 규칙

- 멱등. 동일 요청 반복 시 결과 동일

### 상태값

- NotificationType 참조

### 미정 사항

없음

## API-019 사용자 검색

`GET` `/api/v1/users/search`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-20, FR-39 |
| 화면 | ⑧ 파티 모드 |
| 미정 사항 | 있음 |
| 비고 | 파티 초대 대상 검색 |

### 설명

닉네임으로 회원을 검색한다. 파티 초대 후보 선택에 사용한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `nickname` (string, 필수, 2자 이상)
- `cursor`, `size`

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    { "userId": 2048, "nickname": "영화광", "visibility": "PUBLIC" }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

### 오류 응답

- `400 VALIDATION_FAILED`

### 처리 규칙

- 차단 관계(양방향)인 사용자는 결과에서 제외한다(FR-39)
- 본인은 결과에서 제외한다
- 탈퇴·미인증 계정은 제외한다

### 상태값

없음

### 미정 사항

- [ ] 정확 일치 vs 접두 일치. FR-20은 '고유 닉네임으로 검색'이라 정확 일치가 기본(가정: 접두 일치 허용)
- [ ] `PRIVATE` 사용자도 검색 노출 여부

## API-020 사용자 차단

`POST` `/api/v1/users/{userId}/block`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-39 |
| 화면 | - |
| 미정 사항 | 있음 |
| 비고 | 화면 미정 |

### 설명

다른 사용자를 차단한다. 차단 이후 서로의 프로필·필름·팝콘·평가·한줄평이 노출되지 않고 검색·파티 초대 후보에서 제외된다.

### 인증 및 권한

- 회원

### Path Parameters

- `userId`: 차단 대상

### Query Parameters

없음

### Request Body

없음

### Response

`201 Created`

```json
{ "blockedUserId": 2048, "blockedAt": "2026-08-30T05:21:00Z" }
```

### 오류 응답

- `400 CANNOT_BLOCK_SELF`
- `404 RESOURCE_NOT_FOUND`
- `409 ALREADY_BLOCKED`

### 처리 규칙

- 차단 시 진행 중인 파티 초대(`PENDING`)는 양방향 모두 `DECLINED` 처리한다(가정)
- 차단자는 파티장 여부와 관계없이 상대와 함께 속한 모든 활성 파티에서 탈퇴한다. 오너 승계·인원 부족 종료는 API-055를 따른다. 차단 대상을 강퇴하지 않는다
- 차단 저장, 공동 파티 탈퇴, 오너 승계·종료 및 이에 따른 초대 무효화는 하나의 트랜잭션으로 처리한다. 일부 파티 처리에 실패하면 전체를 롤백한다
- 클라이언트는 차단 전에 공동 파티 탈퇴와 파티장 변경·종료 가능성을 안내한다
- 차단·초대 수락·탈퇴·강퇴가 동시에 실행되어도 차단 관계인 두 사용자가 같은 활성 파티에 남지 않아야 한다. 사용자·파티 도메인은 구현 전에 트랜잭션 조정 책임과 잠금 순서를 합의한다
- 차단은 상대에게 알리지 않는다
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)

### 상태값

없음

### 미정 사항

- [ ] 차단 진입 화면(FR-39 비고: 화면 미정)

## API-021 사용자 차단 해제

`DELETE` `/api/v1/users/{userId}/block`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-39 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

차단을 해제한다.

### 인증 및 권한

- 회원

### Path Parameters

- `userId`

### Query Parameters

없음

### Request Body

없음

### Response

`204 No Content`

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 멱등. 차단 관계가 없어도 `204`로 응답하며 대상 사용자의 존재 여부를 노출하지 않는다
  (2026-09-10 확정. 이전 명세는 차단 관계가 없을 때 `404 RESOURCE_NOT_FOUND`를 함께 적어
  처리 규칙과 어긋났다)
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)

### 상태값

없음

### 미정 사항

없음

## API-022 차단 목록 조회

`GET` `/api/v1/users/me/blocks`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-39 |
| 화면 | ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | - |

### 설명

내가 차단한 사용자 목록을 조회한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `cursor`, `size`

### Request Body

없음

### Response

`200 OK`

```json
{
  "items": [
    { "userId": 2048, "nickname": "영화광", "blockedAt": "2026-08-30T05:21:00Z" }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

### 오류 응답

- 공통 오류만 해당

### 처리 규칙

- 나를 차단한 사용자 목록은 제공하지 않는다
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)
- 차단 대상의 계정 상태와 무관하게 목록에 남는다. 대상이 탈퇴해도 차단 관계는 유지되며 이때 `nickname`은 API-018이 치환한 익명화 값이다. 차단 등록(API-020)이 비활성 대상을 `404`로 감추는 것과 달리 목록은 감추지 않는다. 클라이언트가 목록 항목에서 프로필로 이동하는 화면을 만들면 탈퇴 계정에서 `404`를 만날 수 있다
- `cursor`는 서버가 만든 불투명 문자열이다. 형식이 깨졌거나 서버가 만들 수 없는 값이면 `400 VALIDATION_FAILED`로 거절하며, 어떤 입력에도 `5xx`를 반환하지 않는다

### 상태값

없음

### 미정 사항

없음
