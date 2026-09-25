# API 명세 — 사용자

[API 명세 색인](./README.md)으로 돌아가기. 공통 규약(Base URL, 인증, 오류 형식, 페이지네이션, 공통 상태값)은 색인 문서의 [공통 규약](./README.md#공통-규약)을 따릅니다.

| ID | 이름 | Method | Endpoint | 단계 | 인증 |
|---|---|---|---|---|---|
| [API-001](#api-001-이메일-회원가입) | 이메일 회원가입 | POST | `/auth/signup` | 1차 MVP | 공개 |
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
| [API-081](#api-081-프로필-사진-등록교체) | 프로필 사진 등록·교체 | PUT | `/users/me/avatar` | 2차 MVP | 회원 |
| [API-082](#api-082-프로필-사진-삭제) | 프로필 사진 삭제 | DELETE | `/users/me/avatar` | 2차 MVP | 회원 |
| [API-078](#api-078-이메일-인증번호-발송) | 이메일 인증번호 발송 | POST | `/auth/email/verification-code` | 1차 MVP | 공개 |
| [API-079](#api-079-이메일-인증번호-확인) | 이메일 인증번호 확인 | POST | `/auth/email/verification-code/verify` | 1차 MVP | 공개 |

## API-001 이메일 회원가입

`POST` `/api/v1/auth/signup`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ② 회원가입 |
| 미정 사항 | 없음 |
| 비고 | 인증번호 확인(API-079)이 발급한 토큰이 필요 |

### 설명

인증번호 확인(API-079)이 발급한 `emailVerificationToken`과 비밀번호·프로필을 받아 계정을 만든다. 이메일 소유는 가입 전에 이미 확인했으므로 계정은 인증 대기 상태를 거치지 않고 곧바로 `ACTIVE`가 되고, 응답은 소셜 회원가입(API-008)과 같은 형태로 `accessToken`과 refresh 쿠키를 함께 내려 가입 직후 ④ 취향 초기 설정으로 이어진다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `emailVerificationToken` | string | O | API-079이 발급한 이메일 인증 토큰. 가입할 이메일은 이 토큰에서만 읽는다 |
| `password` | string | O | 8~64자. 영문·숫자·특수문자를 각각 1자 이상 포함. 공백 불가 |
| `nickname` | string | O | 2~10자. 한글·영문·숫자·`_`만 허용(공백 불가). 공백·대소문자 무시 중복 검사 |
| `birthDate` | string | O | 생년월일 `YYYY-MM-DD` (D-8 결정). 실명은 수집하지 않음 |
| `gender` | string | O | `MALE` / `FEMALE` / `OTHER`. 가입 시 필수이며, DB 컬럼은 탈퇴 익명화를 위해 NULL을 허용 |

```json
{
  "emailVerificationToken": "eyJhbGciOi...",
  "password": "p@ssw0rd!",
  "nickname": "팝콘러버",
  "birthDate": "1999-03-14",
  "gender": "FEMALE"
}
```

### Response

`201 Created` + `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Lax`

본문은 API-004 로그인 응답과 같다.

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

- `400 VALIDATION_FAILED` 형식 오류
- `401 EMAIL_VERIFICATION_TOKEN_INVALID` 토큰 누락·위조·만료, 또는 이미 사용된 토큰
- `409 EMAIL_DUPLICATED` 토큰을 발급한 뒤 같은 이메일의 계정이 생긴 경우
- `409 NICKNAME_DUPLICATED` 닉네임 중복
- `429 RATE_LIMITED` IP 단위 요청 제한 초과

### 처리 규칙

- 가입할 이메일은 `emailVerificationToken`에서만 읽고 요청 본문으로 받지 않는다. 본문 값을 믿으면 남의 이메일로 가입할 수 있다. 소셜 회원가입(API-008)이 `signupToken`에서 이메일을 읽는 방식과 같다
- 토큰은 1회용이다. 같은 토큰으로 두 계정을 만들 수 없으며 재사용 요청은 `401 EMAIL_VERIFICATION_TOKEN_INVALID`다
- 토큰은 소셜 가입 토큰(API-007)과 종류가 구분되어 서로의 자리에 쓸 수 없다. 한 종류가 다른 자리에서 통하면 소셜 가입 토큰으로 이메일 계정을 만들거나 그 반대가 가능해진다
- 이 경로는 메일을 보내지 않는다. 발송과 그에 딸린 제한·전역 발송 상한은 인증번호 발송(API-078)에 있다
- 계정은 생성 즉시 `ACTIVE`다. 별도의 활성화 단계가 없고 가입 직후 바로 로그인 상태가 된다
- 요청 제한은 IP 단위로 적용하며(기본 30건/1시간) **이 경로만의 카운터를 쓴다.** 메일 축(API-078·API-010)과 공유하지 않는다. 가입은 더 이상 메일을 보내지 않아 발송 쿼터를 공유할 이유가 없고, 공유하면 가입 한 건이 같은 예산을 발송과 가입으로 두 번 소모해 공유 NAT 한 버킷의 실질 가입 한도가 절반이 된다. 메일 축의 값은 그런 환경을 기준으로 정해진 값이다. 이메일 단위 간격은 두지 않는다. 같은 이메일의 반복 가입은 토큰이 1회용이라는 성질이 막는다
- 제한은 토큰 검증과 중복 검사보다 먼저 적용한다. `429`가 이메일·닉네임의 사용 여부를 알아내는 수단이 되지 않아야 한다
- IP 단위 요청 제한은 요청 경로에서 판정하므로 fail-closed다. 카운터 저장소에 닿지 못하면 가입 요청 자체가 `500 INTERNAL_ERROR`로 실패한다
- 이메일 중복은 인증번호 발송(API-078)이 먼저 거른다. 이 경로의 `409 EMAIL_DUPLICATED`는 발송과 가입 사이에 같은 이메일이 선점된 경합에서만 나온다
- 비밀번호는 bcrypt 등 단방향 해시로 저장한다
- 실명은 수집하지 않는다

### 상태값

- 계정 상태: 생성 즉시 `ACTIVE`

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
- `429 RATE_LIMITED` 이메일 단위 연속 실패 제한(기본 5회/10분) 또는 IP 단위 시도 제한(기본 10분 60회·1시간 300회)

### 처리 규칙

- `onboardingCompleted`가 `false`면 프론트는 ④ 취향 초기 설정으로 이동한다
- `ACTIVE`가 아닌 계정은 모두 `INVALID_CREDENTIALS`로 응답한다. 탈퇴한 계정이 여기 해당한다. 미인증을 따로 알리던 `403 EMAIL_NOT_VERIFIED`는 가입 전에 이메일을 확인하는 새 흐름(API-078·API-079)에 인증 대기 상태가 없어 사라졌고, 그 상태의 잔존 행도 정리됐다(S15P21E106-604)
- 연속 실패가 한도(기본 5회)까지는 `401`이고 그 다음 요청부터 `429`다. 로그인에 성공하면 실패 횟수를 초기화한다
- 시도 한도와 창 길이는 설정 `feelm.auth.login`(기본 5회/10분)으로 관리하므로 환경마다 다를 수 있다. 카운터는 실패가 아니라 시도를 세므로 한도를 넘는 요청은 비밀번호가 맞아도 대조 없이 `429`다
- 제한은 이메일 축과 클라이언트 IP 축을 함께 본다. 이메일 축만으로는 비밀번호 하나를 서로 다른 계정에 한 번씩 넣는 시도(password spraying)와 남의 계정을 반복해서 잠그는 시도가 어디에도 세지지 않는다
- **평가 순서는 IP → 이메일**이다. IP 한도를 넘긴 요청은 비밀번호를 대조하지 않고 `429`이며 그 계정의 실패 횟수도 올리지 않는다. 공유 IP의 정상 사용자가 남의 예산 때문에 `429`를 받아도 자기 계정까지 잠기지 않게 하는 순서다. 반대로 이메일 축은 IP와 무관하게 적용되므로 IP를 바꿔가며 시도해도 계정별 한도는 그대로다
- **IP 축도 실패가 아니라 시도를 센다.** 확인과 기록을 하나의 원자적 증가로 합쳐야 동시 요청이 몰려도 한 창에서 비밀번호 대조가 한도만큼만 일어나고, 카운터 소모 여부가 계정 존재나 비밀번호 일치를 알려 주는 신호가 되지 않는다. 로그인 성공은 이메일 카운터만 초기화하고 IP 카운터는 남긴다
- IP 한도는 설정 `feelm.auth.login.ip` 목록(기본 10분 60회, 1시간 300회)이며 창을 늘리거나 줄일 수 있다. 정상 로그인도 예산을 소모하므로 값은 공유 NAT(캠퍼스망, 시연 환경, 모바일 CGNAT)에서 로그인이 한꺼번에 몰리는 구간을 통과하도록 정했다
- IP 축의 예산은 메일 축(API-078)과 같은 집계 단위로 나눈다. IPv4는 주소 단위(`/32`), IPv6는 프리픽스 단위(기본 `/64`)이며 값은 설정 `feelm.auth.client-ip`로 관리한다. 같은 단위를 쓰지 않으면 IPv6 클라이언트가 같은 `/64` 안에서 주소만 바꿔 로그인 예산을 새로 얻는다. 발신지를 알 수 없는 요청은 한 버킷의 예산을 공유한다
- 클라이언트 IP는 신뢰 목록 안의 프록시가 보낸 `X-Real-IP`일 때만 그 값을 쓰고, 그 밖에서 직접 온 요청의 헤더와 `X-Forwarded-For`는 무시한다. 헤더로 예산 주체를 바꿀 수 없어야 한다([ADR-0019](../adr/0019-reverse-proxy-client-ip-trust.md))
- 대가: 같은 IP를 쓰는 제3자가 예산을 고갈시키면 그 IP의 정상 사용자도 비밀번호가 맞아도 `429`다. 이메일 축에서 `429`가 된 요청과 로그인할 수 없는 계정의 반복 시도도 IP 예산을 소모하므로, 잠긴 계정의 재시도가 같은 IP의 다른 사용자에게 번질 수 있다
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
- 회전 직후 짧은 유예(`feelm.jwt.refresh-rotation-grace`, 기본 10초) 안에 같은 토큰이 다시 오면 그 세션의 현재 토큰을 다시 내려준다. 유예 안에 회전이 한 번 더 일어난 뒤라도 같다. 재발급 응답을 받지 못한 채 끊긴 요청이 한 번 더 도착하는 일은 정상 사용에서도 생기며(새로고침·화면 이탈), 이것까지 탈취로 처리하면 사용자의 모든 세션이 한 번에 끊긴다
- 유예를 지난 뒤 이미 사용된 토큰이 재사용되면 해당 사용자의 모든 Refresh Token을 폐기한다(탈취 대응)
- 로그아웃(API-005)·비밀번호 변경(API-012)·탈퇴(API-013)로 이미 폐기된 세션의 토큰이 뒤늦게 도착하면 거절만 하고 전량 폐기를 발동하지 않는다. 폐기할 것이 남아 있지 않고, 그 사이 다시 로그인한 세션을 끊지 않기 위해서다
- 전량 폐기된 세션은 유예로 되살아나지 않는다
- 활성 계정이 아니면 `REFRESH_TOKEN_INVALID`로 거절하고 남은 토큰을 모두 폐기한다. Refresh Token에는 계정 상태가 없어 이 검사가 없으면 탈퇴(API-013) 계정의 세션이 만료까지 계속 갱신된다. 특히 탈퇴와 동시에 진행되던 로그인이 발급한 토큰은 탈퇴의 전량 폐기를 앞지를 수 있다

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

- `400 VALIDATION_FAILED` 형식 오류, 또는 제공자가 준 이메일이 탈퇴 익명화가 예약한 `@feelm.invalid` 도메인인 경우(API-013 처리 규칙)
- `401 SIGNUP_TOKEN_INVALID` 만료 또는 위조
- `409 EMAIL_DUPLICATED` 같은 이메일의 계정이 이미 있음(자동 통합하지 않음)
- `409 NICKNAME_DUPLICATED`
- `409 OAUTH_ACCOUNT_ALREADY_LINKED` 토큰 발급 후 다른 계정에 연결된 경우

### 처리 규칙

- 소셜 가입 계정은 제공자가 이미 이메일을 확인했으므로 즉시 `ACTIVE`
- 이메일과 제공자 식별자는 `signupToken`에서 읽는다. 요청 본문으로 받지 않는다 (S15P21E106-150 확정)
- 같은 이메일의 계정이 이미 있으면 통합하지 않고 `409 EMAIL_DUPLICATED`로 거절한다. 이메일로 로그인한 뒤
  계정 연결(API-009)로 같은 결과를 얻는다 (S15P21E106-150 확정). 미인증 계정을 회수하던 예외는 그 상태가
  사라지면서 함께 없앴다(S15P21E106-604)
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
- 요청 제한은 인증번호 발송(API-078)과 같은 두 축이다. 이메일 단위 최소 간격(기본 1분)과 IP 단위이며, IP 카운터는 인증번호 발송(API-078)과 공유한다. 값은 설정 `feelm.mail.rate-limit`으로 관리한다
- IP 축의 집계 단위와 클라이언트 IP 판정은 API-078와 같다([ADR-0019](../adr/0019-reverse-proxy-client-ip-trust.md))
- 위 두 축은 계정 존재 여부와 실제 발송 여부와 무관하게 요청 시점에 적용한다. `429`로 계정 유무를 구분할 수 없어야 한다
- 계정 메일 전체의 전역 발송 상한(API-078 처리 규칙)도 함께 적용된다. 그 상한은 발송 시점에 적용되어 응답을 바꾸지 않으며, 상한을 넘긴 시점의 메일은 발송되지 않고 재요청이 복구 경로다
- 상한에 걸린 동안에는 새 재설정 토큰을 발급하지 않아 이미 보낸 링크가 그대로 유효하다. 상한을 확인할 수 없으면(저장소 장애) 메일을 보내지 않으며, 저장소 장애 중의 응답은 API-078와 같다(요청 단위 제한이 fail-closed라 `500`)
- 어느 축에서 걸렸든 응답의 상태와 본문은 같고 `Retry-After`는 주지 않는다
- 재설정 토큰 유효기간 30분(가정), 1회용
- 이전 미사용 토큰은 무효화하고 새 토큰을 발급한다
- 메일 발송은 응답과 분리해 처리한다. 발송을 기다리면 계정 존재 여부가 응답 시간으로 드러나므로, 발송 결과는 응답에 영향을 주지 않는다. 발송 실패·유실은 간격이 지난 뒤 다시 요청해 복구한다. 발송을 분리한 뒤에도 계정 유무에 따른 토큰 발급·저장 수 ms 차이는 남으며 감수한다
- 발송 대상은 이메일 로그인 수단을 가진 활성 계정뿐이다. 활성이 아닌 계정과 비밀번호가 없는 계정에는 발송하지 않고 `202`로 응답한다. 소셜 전용 계정에 이 경로로 이메일 로그인 수단을 추가할지는 API-008 처리 규칙의 미정 사항이므로 열지 않는다

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

계정을 탈퇴 처리한다. 탈퇴한 계정에는 연결된 모든 로그인 수단(이메일·소셜)으로 로그인할 수 없다.

되돌릴 수 없는 동작이므로 Access Token만으로는 받지 않고 본인 확인을 한 번 더 거친다. 수단은 두 가지이며 요청 본문이 어느 쪽인지 고른다 — 현재 비밀번호, 또는 연결된 소셜 계정의 재인가 코드다(S15P21E106-435 확정).

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

비밀번호 경로

```json
{ "password": "p@ssw0rd!" }
```

소셜 재인증 경로

```json
{ "provider": "google", "code": "4/0AX4XfWh...", "redirectUri": "https://feelm.app/oauth/link-callback" }
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `password` | string | 조건부 | 비밀번호 경로에서 필수. 로그인에 쓰는 현재 비밀번호 |
| `provider` | string | 조건부 | 소셜 재인증 경로에서 필수. `google` / `kakao` / `naver`(대소문자 무시) |
| `code` | string | 조건부 | 소셜 재인증 경로에서 필수. 제공자가 발급한 인가 코드 |
| `redirectUri` | string | 조건부 | 소셜 재인증 경로에서 필수. 허용 목록과 정확히 일치해야 한다(API-007과 같은 규칙) |

- **경로는 `provider`가 고른다.** 값이 있으면 소셜 재인증, 없거나 비어 있으면 비밀번호다. 빈 문자열을 소셜 요청으로 보면 비밀번호를 보낸 계정이 `UNSUPPORTED_PROVIDER`로 거절되는데 사용자가 고칠 수 없는 오류이므로, 빈 값은 없는 것과 같게 본다
- 비밀번호가 없는 계정(소셜 전용)이 `provider` 없이 요청하면 `409 PASSWORD_NOT_SET`이다. "이 계정은 비밀번호 경로로 탈퇴할 수 없다"는 뜻이며, 소셜 재인증 경로로 다시 요청하면 탈퇴할 수 있다
- 비밀번호를 가진 계정도 연결된 소셜로 재인증할 수 있다. 수단을 계정 종류가 아니라 요청이 고르게 두어 분기를 하나로 유지한다(S15P21E106-435 결정 D2). **`provider`가 있으면 `password`는 읽지 않는다.** 그래서 두 수단을 가진 계정의 탈퇴 난이도는 둘 중 약한 쪽이 되는데, 그 소셜 계정을 쥔 쪽은 이미 로그인(API-007)으로 계정 전체를 쓸 수 있으므로 탈퇴만 더 어렵게 막을 실익이 없다고 보았다(리뷰 A-9·B-4)
- 재인증의 `redirectUri`는 소셜 계정 연결(API-009)이 쓰는 값을 그대로 재사용한다. 탈퇴 전용 주소를 허용 목록에 더하지 않는다(결정 D5)

### Response

`204 No Content` + `refresh_token` 쿠키 삭제

- 쿠키는 이 요청에 실려 오지 않지만(`Path=/api/v1/auth`) 응답은 같은 `Path`의 만료 쿠키를 내려보낸다. 로그아웃(API-005)과 같은 처리다

### 오류 응답

- `400 VALIDATION_FAILED` 비밀번호를 가진 계정인데 `password`가 없거나 비어 있음, 또는 소셜 재인증 경로인데 `code`가 비어 있거나 `redirectUri`가 허용 목록 밖
- `400 UNSUPPORTED_PROVIDER` 목록에 없거나 자격 증명이 설정되지 않은 `provider`
- `400 OAUTH_CODE_INVALID` 코드 교환 실패
- `401 UNAUTHORIZED` 활성 계정이 아님(탈퇴). Access Token은 계정 상태를 담지 않으므로 서버가 이 검사를 수행한다
- `401 INVALID_CREDENTIALS` 비밀번호 불일치, 또는 교환한 제공자 계정이 이 사용자에게 연결돼 있지 않음
- `409 PASSWORD_NOT_SET` 비밀번호가 없는 계정이 비밀번호 경로로 요청. 위 Request Body 항목 참고
- `502 OAUTH_PROVIDER_ERROR` 제공자 응답 오류

판정 순서는 경로마다 다르다.

- 비밀번호 경로: 계정 상태 → 비밀번호 보유 → `password` 값 유무 → 대조. 값 검증을 계정 판정보다 앞에 두면 소셜 전용 계정이 `409`에 닿지 못하고 `400`을 받는다
- 소셜 재인증 경로: `provider` 판정 → `code` 값 유무 → `redirectUri` 허용 목록 → 코드 교환 → **계정 상태** → 연결 대조. 계정 상태가 교환보다 뒤인 것은 계정 판정을 잠금 안에서만 하기 때문이다(아래 처리 규칙). 그래서 이미 탈퇴한 계정이 목록에 없는 `provider`를 보내면 `401`이 아니라 `400 UNSUPPORTED_PROVIDER`를 받는다

소셜 재인증의 실패를 `INVALID_CREDENTIALS` 하나로 모으는 이유는 열거를 막기 위해서다. 연결이 없는 경우와 다른 사람의 계정인 경우를 구분해 주면 어떤 제공자 계정이 이 서비스에 연결돼 있는지 확인하는 수단이 된다.

### 처리 규칙

- 소셜 재인증은 인가 코드를 **백엔드가 제공자와 교환하고**(API-007과 같은 규칙), 교환으로 받은 제공자 식별자가 이 사용자에게 연결된 `user_auth_providers` 행과 `provider`까지 함께 일치할 때만 통과시킨다. 식별자는 제공자 안에서만 유일하므로 제공자를 빼고 비교하지 않는다
- 코드 교환은 외부 호출이라 계정 행을 잠그기 전에 끝내고, 교환 결과와 연결 행의 대조는 잠금 안에서 한다. 잠금 밖에서 대조하면 그 사이에 끼어든 탈퇴나 재연결을 놓친다. 교환 단계에서 실패한 요청은 계정을 바꾸지 않는다
- 이미 탈퇴한 계정의 소셜 요청도 교환까지는 진행한 뒤 `401 UNAUTHORIZED`로 거절된다. 계정 판정은 잠금을 쥔 단계에서만 하기 때문이며, 되돌릴 수 없는 변경은 일어나지 않는다
- 사용자 상태를 `WITHDRAWN`으로 바꾸고 `withdrawn_at`을 기록하며 모든 Refresh Token을 폐기한다
- 이메일·닉네임을 익명화 값으로 치환하고 `password_hash`·`birth_date`·`gender`를 비우며 `visibility`를 `PRIVATE`으로 되돌린다. 연결된 소셜 로그인 수단(`user_auth_providers`)과 인증·비밀번호 재설정 토큰은 삭제한다
- 치환 값은 `email` → `withdrawn-{id}@feelm.invalid`, `nickname` → `withdrawn-{id}`이다. 이 도메인으로는 가입할 수 없다(API-001·008) — 막지 않으면 치환 값을 미리 선점해 특정 사용자의 탈퇴를 유일 제약 위반으로 실패시킬 수 있다. `-`는 닉네임 규칙(한글·영문·숫자·밑줄)에 없는 문자라 살아 있는 닉네임과 겹치지 않고, 식별자를 붙여 탈퇴 계정끼리도 겹치지 않는다. `.invalid`는 RFC 2606 예약 TLD라 실재하는 주소가 되지 않는다
- 계정에 매달린 OTT 구독(`user_otts`), 알림 수신 설정(`notification_settings`), 인앱 알림(`notifications`), 온보딩 초기 취향(`onboarding_preferences`), 차단 관계(`user_blocks`)를 삭제한다. 차단은 탈퇴자가 건 것과 탈퇴자를 향한 것을 모두 지운다 — 계정이 사라졌는데 상대의 차단 목록(API-022)에 죽은 항목이 남을 이유가 없다
- 계정 행을 잠그고 처리하므로 같은 계정의 프로필 수정(API-015)·비밀번호 변경(API-012)·소셜 계정 연결(API-009)과 동시에 들어와도 치환한 값이나 지운 연결이 되살아나지 않는다
- 계정 상태 전이·치환·소셜 수단 삭제는 한 트랜잭션이고, 인증 토큰과 차단 관계 정리는 그 커밋 이후에 수행한다. 계정을 잠근 채 인증 토큰을 지우면 저장소가 세운 `토큰 → 계정` 잠금 순서를 거슬러 재설정(API-011)·비밀번호 변경(API-012)과 교착한다. 커밋 이후의 토큰은 재설정·이메일 인증 어느 경로도 통과시키지 않아 늦게 지워도 무해하다
- 치환으로 유일 제약이 풀리므로 같은 이메일·닉네임으로 즉시 재가입할 수 있다. 유예기간과 탈퇴 철회는 두지 않는다
- 내부·공개 식별자와 다른 사용자 데이터의 연결이 남으므로 이메일·닉네임 치환만으로 완전한 익명화를 전제하지 않는다. 계정 처리 목적이 끝난 생년월일·성별은 함께 삭제한다(D-9)
- 평가·한줄평·프레임 등 개인 데이터는 탈퇴 시 삭제한다. **아직 구현하지 않았다** — 해당 도메인이 구현되기 전이고, 공개 한줄평 처리와 추천 학습용 익명 평가 데이터 보존 여부가 미정이다(아래 미정 사항)
- 참여 중인 파티에서 제외되고 파티 인원 변경 규칙(FR-20)이 적용된다
- 탈퇴자가 오너이고 1명 이상 남으면 최초 가입자에게 오너를 승계하며, 탈퇴자가 마지막 멤버였으면 파티를 종료한다. 1인 파티도 활성이므로 남은 멤버가 1명이라는 이유로 종료하지 않는다(API-055와 동일 규칙)
- 파티 처리는 API-055의 나가기 규칙을 그대로 재사용한다. 계정 도메인은 파티 도메인이 제공하는 탈퇴 전용 진입점 하나만 호출하고 승계·종료·초대 무효화·추천 갱신은 그 안에서 일어난다(S15P21E106-436)
- 파티 정리는 **계정 상태 전이를 커밋한 뒤** 파티마다 별도 트랜잭션으로 수행한다. 계정 행을 잠근 채 파티 행까지 잠그면 잠금 범위가 그 사용자가 속한 모든 파티로 번지기 때문이다. 목록을 읽은 뒤 먼저 종료됐거나 멤버십이 사라진 파티는 건너뛴다
- 정리 시점에 이미 종료돼 있던 파티의 멤버십 행은 남긴다. 종료된 파티는 이력이고, 익명화된 계정 행이 남아 있어 이력이 가리키는 곳도 사라지지 않는다. 탈퇴 처리 중에 종료되는 파티는 나가기 규칙을 그대로 거치므로 행이 삭제된다
- 탈퇴자가 **보낸** 대기 초대와 **받은** 대기 초대를 모두 `DECLINED`로 정리하고 초대 알림을 닫는다. 사라진 계정의 초대가 상대의 받은 초대 목록(API-052)에 응답할 수 없는 항목으로 남지 않게 한다. 탈퇴자가 마지막 멤버였던 파티의 초대는 파티 종료가 이미 정리한다
- 파티 하나의 정리가 실패해도 **나머지 파티와 초대는 끝까지 처리**하고, 그 뒤 응답을 `500`으로 돌려준다. 탈퇴는 다시 호출할 수 없으므로(두 번째 요청은 계정이 이미 `WITHDRAWN`이라 `401`) 중간에서 멈추면 남은 파티에 탈퇴한 계정이 멤버·오너로 남는다. 실패를 응답으로 드러내는 것은 그 상태를 사람이 찾을 수 있게 하기 위해서이며, 복구는 수동이다
- 탈퇴 정리가 대기 초대 목록을 읽은 **뒤에** 그 사용자를 향해 커밋되는 초대는 이 정리에 걸리지 않는다. 그래서 초대 생성(API-051)이 대상 계정 행을 잠근 뒤 활성 여부를 판정한다(S15P21E106-758). 1단계가 같은 행을 잠그므로 초대는 1단계보다 먼저 커밋되어 이 정리에 포함되거나, 1단계 뒤에 판정되어 `404`로 거절된다. 탈퇴한 요청자가 잔여 Access Token으로 만드는 초대는 같은 API가 `401`로 거절하고, 그 사이에 만들어진 것은 위의 **보낸** 초대 정리가 처리한다

### 상태값

- 계정 상태 → `WITHDRAWN`

### 미정 사항

- [x] 탈퇴 후 데이터 보존 범위·기간 — 계정 영역은 확정(이메일·닉네임 치환, 즉시 재사용, 생년월일·성별 삭제, 계정 부속 데이터 삭제). 평가·학습 데이터 범위는 아래 항목에서 계속
- [ ] 공개 한줄평은 삭제 vs '탈퇴한 사용자'로 익명 유지 — 파티 도메인은 종료 파티 이력(API-050·052)에서 탈퇴자의 `nickname`을 '탈퇴한 사용자'로 표기하기로 정했다(S15P21E106-759). 한줄평이 익명 유지를 택하면 같은 문구를 쓰고, 그때 문구를 공용 위치로 올린다
- [x] 소셜 전용 계정의 본인 확인 수단 — **provider 재인증으로 확정**(S15P21E106-435). `users.last_login_at` 신설 안은 기각했다. "최근 로그인(10분 이내)" 창은 Access Token의 유효 구간과 겹쳐, 토큰을 쥔 쪽에게 추가 관문이 되지 못한다
- [ ] 평가·한줄평·프레임 삭제 — 해당 도메인 구현 후 적용

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
  "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg",
  "birthDate": "1999-03-14",
  "gender": "FEMALE",
  "visibility": "PRIVATE",
  "frameVisibility": "PUBLIC",
  "popcornVisibility": "PUBLIC",
  "reviewVisibility": "PRIVATE",
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
- 등록된 프로필 사진이 없으면 `avatarUrl`은 `null`이다. URL은 공개·비만료 경로이며 사진 교체·삭제·회원 탈퇴 후 기존 URL은 `404`가 된다
- 활성 계정이 아니면 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-012와 같은 판정)
- `frameVisibility`·`popcornVisibility`·`reviewVisibility`는 항목별 공개 범위다(S15P21E106-856). 각각 취향 필름(프레임), 팝콘 버킷, 한줄평을 다른 사용자에게 보일지 정한다. 가림 규칙은 [항목별 공개 범위](#항목별-공개-범위)를 따른다

### 상태값

- Visibility: `PUBLIC`, `PRIVATE`. `visibility` 기본값 `PRIVATE`, 세 항목 기본값 `PUBLIC`

### 항목별 공개 범위

| 필드 | 항목 (7.8-B) | `PRIVATE`일 때 다른 사용자에게 가려지는 곳 |
|---|---|---|
| `frameVisibility` | 취향 필름 | API-062 `recentFrames`, `stats.frameCount` |
| `popcornVisibility` | 팝콘 버킷 | API-062 `popcorn.flavors`, `stats.popcornCount` |
| `reviewVisibility` | 한줄평 | API-062 `publicReviewCount`, API-032 목록 |

- 값은 `Visibility`(`PUBLIC`·`PRIVATE`)를 그대로 쓴다. 와이어프레임의 `파티원만`은 지원하지 않는다
- 기본값은 `PUBLIC`이다. 프로필 `visibility`가 `PRIVATE`이면 공개 프로필 자체가 `404`이므로, 세 항목은 프로필이 `PUBLIC`일 때 그 안에서 무엇을 보일지 정한다. 한줄평은 프로필과 별개로 API-032에도 적용된다
- 본인 조회(API-014, 자기 `publicId`의 API-062, API-032의 본인 한줄평)에는 적용하지 않는다
- 평가별 `visibility`(API-036·037)와 함께 적용한다. 한줄평이 타인에게 보이려면 평가가 `PUBLIC`이고 `reviewVisibility`도 `PUBLIC`이어야 한다
- 같은 활성 파티의 현재 멤버에게 팝콘 집계를 보이는 API-057의 예외는 `popcornVisibility`에도 그대로 적용한다(파티 추천의 입력이다)
- 가림은 **응답에 해당 구역을 싣지 않는 것**까지다. 공개로 둔 다른 구역에서 추정하는 것은 막지 않는다 — 예: 팝콘만 비공개여도 공개된 최근 필름(영화·점수)과 영화의 대표 맛(API-030)으로 분포를 짐작할 수 있고, 필름만 비공개여도 팝콘 수는 평가 수에 가깝다
- 취향 키워드(API-040 결과, API-062 `keywords`)는 세 항목에 속하지 않아 가리지 않는다

### 미정 사항

없음

## API-081 프로필 사진 등록·교체

`PUT` `/api/v1/users/me/avatar`

| 항목 | 값 |
|---|---|
| 단계 | 2차 MVP |
| 인증 | 회원 |
| 관련 요구사항 | FR-38 |
| 화면 | ⑪ 프로필 대시보드, ⑪-6 설정 |
| 미정 사항 | 없음 |
| 비고 | `multipart/form-data` |

### 설명

프로필 사진을 처음 등록하거나 새 사진으로 교체한다.

### Request Body

- multipart 필드 `file` (필수)
- JPEG, PNG, WEBP
- 최대 10 MiB

### Response

`200 OK`

```json
{ "avatarUrl": "/media/avatars/0b649505-0917-41d9-a41f-0d4402ec63d5.jpg" }
```

### 오류 응답

- `400 AVATAR_INVALID`: 비어 있거나 디코딩할 수 없는 파일, 지원하지 않는 이미지 형식
- `413 AVATAR_TOO_LARGE`: 10 MiB 초과
- `503 AVATAR_STORAGE_UNAVAILABLE`: 로컬 저장소를 사용할 수 없음

### 처리 규칙

- 서버가 실제 파일 내용을 판별하며 클라이언트의 파일명과 Content-Type을 신뢰하지 않는다
- EXIF 방향을 반영하고 메타데이터를 제거한 뒤 가운데 기준으로 자르고 512×512 JPEG(품질 85)로 저장한다. 투명 영역은 흰색으로 합성한다
- 파일명은 서버가 생성한 UUID이며 URL은 인증 없이 조회할 수 있다. `Cache-Control: public, max-age=86400`을 적용한다
- DB 교체가 완료된 다음 이전 파일을 삭제한다. 실패 시 기존 사진은 유지한다

## API-082 프로필 사진 삭제

`DELETE` `/api/v1/users/me/avatar`

로그인한 사용자의 프로필 사진 연결과 원본 파일을 제거한다. 이미 사진이 없어도 성공한다.

### Response

`204 No Content`

### 처리 규칙

- 삭제 후 API-014와 API-062의 `avatarUrl`은 `null`이며 기존 공개 URL은 `404`가 된다

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

닉네임, 나이, 성별, 프로필 공개 범위와 항목별 공개 범위를 부분 수정한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

없음

### Request Body

전달한 필드만 변경한다.

```json
{ "nickname": "새닉네임", "visibility": "PUBLIC", "reviewVisibility": "PRIVATE" }
```

- `frameVisibility`·`popcornVisibility`·`reviewVisibility`: `PUBLIC`·`PRIVATE`. [항목별 공개 범위](#항목별-공개-범위)

### Response

`200 OK`, API-014와 동일 본문

### 오류 응답

- `400 VALIDATION_FAILED`
- `409 NICKNAME_DUPLICATED` (공백·대소문자 무시 비교, FR-01과 동일 규칙)

### 처리 규칙

- `visibility`를 `PRIVATE`으로 바꾸면 공개 프로필(API-062)은 즉시 `404`가 된다
- 전달한 필드만 바꾸되 `nickname`·`birthDate`·`gender`는 가입 시 필수값이므로 `null`로 되돌릴 수 없다. `null`을 보내면 `400 VALIDATION_FAILED`로 응답한다
- `visibility`도 같은 이유로 `null`을 허용하지 않는다. 필드를 아예 보내지 않으면 변경하지 않는다
- 세 항목 공개 범위도 `visibility`와 같다. `null`·빈 문자열·허용하지 않는 값(예: `PARTY`)은 `400 VALIDATION_FAILED`이고, 보내지 않으면 변경하지 않는다. 바꾸면 다른 사용자의 다음 조회부터 즉시 적용된다
- `nickname` 형식 규칙은 가입(API-001)과 같다(2~10자, 한글·영문·숫자·`_`)
- 자기 현재 닉네임과 정규화 값이 같으면 중복으로 보지 않는다. 대소문자만 바꾸는 변경이 자기 자신 때문에 `409`가 되지 않게 한다
- 활성 계정이 아니면 `401 UNAUTHORIZED`로 거절한다(API-014와 같은 판정)
- 이메일은 변경 불가

### 상태값

- Visibility: `PUBLIC`, `PRIVATE` (`visibility` 기본값 `PRIVATE`, 세 항목 기본값 `PUBLIC`)

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
| 비고 | 인앱 알림은 FR-41·API-070~074, 푸시 채널 적용은 S15P21E106-582 |

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

- 인앱 알림 정본과 읽음·숨김 동기화는 FR-41·API-070~074에서 다룬다. 이 설정을 브라우저 푸시 채널에 적용하는 범위는 S15P21E106-582에서 확정한다

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
| 미정 사항 | 없음 |
| 비고 | 파티 초대 대상 검색 |

### 설명

닉네임 접두로 회원을 검색한다. 파티 초대 후보 선택에 사용한다.

### 인증 및 권한

- 회원

### Path Parameters

없음

### Query Parameters

- `nickname` (string, 필수). 정규화(공백 제거·소문자) 후 2~10자여야 한다. 상한은 닉네임 형식 상한과 같으며, 이를 넘는 검색어는 어떤 닉네임과도 일치할 수 없다
- `cursor` (string, 선택). 서버가 발급한 불투명 값이며 정렬 키 `(정규화 닉네임, userId)`를 담는다
- `size` (int, 선택, 기본 20, 1~50)

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
- `401 UNAUTHORIZED`

### 처리 규칙

- **접두 일치**로 검색한다. 비교는 닉네임 유일성과 같은 정규화식 `lower(replace(nickname, ' ', ''))`으로 하며, 검색어도 같은 규칙으로 정규화한다. 중복 판정과 검색 판정이 갈리면 가입할 때 중복이던 닉네임이 검색되지 않는 불일치가 생긴다
- 검색어의 `%`, `_`는 와일드카드가 아니라 리터럴로 취급한다. `_`는 닉네임에 실제로 쓰이는 문자다
- 닉네임에 쓸 수 없는 문자가 섞인 검색어는 오류가 아니라 빈 결과로 응답한다. 길이만 검증한다
- **`PRIVATE` 사용자도 결과에 포함**하고 `visibility`를 함께 반환한다. 가입 기본값이 `PRIVATE`라 제외하면 FR-20의 닉네임 초대가 사실상 막히므로, 클라이언트가 `visibility`로 초대 후보 카드의 노출 수위를 정한다
- 차단 관계(양방향)인 사용자는 결과에서 제외한다(FR-39)
- 본인은 결과에서 제외한다
- 탈퇴 계정은 제외한다
- 정렬은 정규화 닉네임 오름차순이며 동률은 `userId` 오름차순으로 깬다. 정규화 닉네임은 유일 인덱스로 중복될 수 없어 실제로는 동률이 생기지 않지만, 유일성 규칙이 바뀌어도 커서가 흔들리지 않도록 두 번째 키를 둔다
- 커서가 정렬 키를 담으므로 정렬 키가 그대로인 동안에는 페이지 경계에서 행이 중복되거나 빠지지 않는다. 다만 페이지를 넘기는 사이에 대상이 닉네임을 바꾸면(API-015) 정렬 키가 옮겨 가므로 그 사용자는 한 번 더 보이거나 건너뛸 수 있다. 커서 페이지네이션이 가변 정렬 키에서 갖는 한계이며, 파티 초대 후보 선택에서는 허용한다
- 페이지를 넘기는 사이에 차단이 생기거나 계정이 비활성으로 바뀌면 해당 사용자만 이후 페이지에서 빠진다. 커서 위치는 유지된다
- 형식이 깨진 커서와 범위를 벗어난 `size`는 `400 VALIDATION_FAILED`로 응답한다. 커서에 서명하지 않으므로 형식만 맞으면 클라이언트가 만든 값도 받아들인다. 커서는 정렬 위치만 담고 제외 조건(본인·차단·비활성)은 매 요청 다시 계산하므로, 커서를 바꿔도 노출 범위를 넓힐 수는 없다
- 어떤 검색어·커서 입력에도 `5xx`로 응답하지 않는다. 닉네임에 담길 수 없는 제어문자가 섞인 검색어는 빈 결과로, 그런 커서는 `400 VALIDATION_FAILED`로 답한다
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)

### 상태값

없음

### 미정 사항

없음

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
- 위 파티 연계(공동 파티 탈퇴, 오너 승계·인원 부족 종료, 초대 무효화)는 아직 구현되지 않았다. 차단 등록·해제·목록(API-020~022)만 구현된 상태이며, 조정 서비스와 잠금 순서 합의를 포함한 파티 연계까지 구현된 뒤 API-055의 탈퇴 경로를 재사용해 한 트랜잭션으로 적용한다
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)

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
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)

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
- 활성 계정이 아닌 요청자는 `401 UNAUTHORIZED`로 거절한다. Access Token은 계정 상태를 담지 않아 탈퇴 계정도 잔여 수명 동안 서명이 유효하기 때문이다(API-014와 같은 판정)
- 차단 대상의 계정 상태와 무관하게 목록에 남는다. 대상이 탈퇴해도 차단 관계는 유지되며 이때 `nickname`은 API-018이 치환한 익명화 값이다. 차단 등록(API-020)이 비활성 대상을 `404`로 감추는 것과 달리 목록은 감추지 않는다. 클라이언트가 목록 항목에서 프로필로 이동하는 화면을 만들면 탈퇴 계정에서 `404`를 만날 수 있다
- `cursor`는 서버가 만든 불투명 문자열이다. 형식이 깨졌거나 서버가 만들 수 없는 값이면 `400 VALIDATION_FAILED`로 거절하며, 어떤 입력에도 `5xx`를 반환하지 않는다

### 상태값

없음

### 미정 사항

없음

## API-078 이메일 인증번호 발송

`POST` `/api/v1/auth/email/verification-code`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ② 회원가입 |
| 미정 사항 | 없음 |
| 비고 | 계정 생성 전 단계 |

### 설명

가입하려는 이메일로 6자리 인증번호를 보낸다. 계정을 만들기 전에 이메일 소유를 확인하는 경로이며, 확인(API-079)과 가입(API-001)이 뒤를 잇는다.

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

```json
{ "email": "user@example.com" }
```

### Response

`202 Accepted` (본문 없음)

### 오류 응답

- `400 VALIDATION_FAILED` 형식 오류, 또는 탈퇴 익명화가 예약한 `@feelm.invalid` 도메인의 이메일(API-013 처리 규칙)
- `409 EMAIL_DUPLICATED` 이미 가입된 이메일
- `429 RATE_LIMITED` 요청 제한 초과(이메일 단위 간격, IP 단위 중 어느 축이든)

### 처리 규칙

- 인증번호는 6자리 숫자이며 암호학적 난수로 만든다. 유효기간 10분
- 원문은 저장소와 로그 어디에도 남기지 않고 SHA-256 해시만 저장한다. 원문이 있는 곳은 발송한 메일 본문뿐이다
- 유효한 인증번호는 이메일당 하나다. 다시 발송하면 이전 번호를 무효화한다
- 확인 시도 상한은 인증번호 1건당 5회이며 상한을 넘기면 번호를 폐기한다(API-079)
- **이미 가입된 이메일은 번호를 보내지 않고 `409 EMAIL_DUPLICATED`로 응답한다.** 계정 존재 여부를 숨기지 않는 자리다(팀 결정). 숨기면 이미 가입한 사용자가 오지 않는 메일을 기다리게 되고, 가입 단계(API-001)에서 어차피 중복을 알려 주므로 숨겨서 얻는 것이 없다. 대가로 이 경로는 이메일 열거(enumeration) 수단이 되며 아래 요청 제한이 그 속도를 묶는다
- 요청 제한은 두 축으로 적용한다. 이메일 단위 최소 간격(기본 1분)과 IP 단위(기본 30건/1시간)이며, 값은 설정 `feelm.mail.rate-limit`으로 관리하므로 환경마다 다를 수 있다
- IP 축은 중복 검사보다 **먼저** 적용하고 시도 시점에 센다. `409`로 끝나는 요청도 예산을 소모한다. 중복 검사를 먼저 하면 `409`를 받는 요청이 예산을 쓰지 않아 열거 속도를 묶을 수 없다
- 이메일 단위 간격은 확인을 먼저 하고 **기록은 처리를 마친 뒤**에 한다. `409`로 거절한 요청은 간격 슬롯을 심지 않는다. 슬롯은 요청자가 아니라 그 이메일 주인의 것이어서, 거절될 요청이 슬롯을 심으면 예산을 이미 넘긴 쪽이 임의의 이메일에 대해 발송을 분당 한 건씩 지연시킬 수 있다
- IP 카운터는 비밀번호 재설정 요청(API-010)과 공유한다. 두 경로가 같은 발송 수단의 쿼터를 소모하므로 한쪽만 막으면 발송 총량을 묶을 수 없다. 메일을 보내지 않는 회원가입(API-001)은 이 카운터를 쓰지 않는다. 이메일 단위 간격은 경로별로 분리한다
- IP 축의 예산은 주소 하나가 아니라 집계 단위로 나눈다. IPv4는 주소 단위(`/32`), IPv6는 프리픽스 단위(기본 `/64`)이며 값은 설정 `feelm.auth.client-ip`로 관리한다. 발신지를 알 수 없는 요청은 한 버킷의 예산을 공유한다
- 클라이언트 IP는 신뢰 목록 안의 프록시가 보낸 `X-Real-IP`일 때만 그 값을 쓰고, 그 밖에서 직접 온 요청의 헤더와 `X-Forwarded-For`는 무시한다([ADR-0019](../adr/0019-reverse-proxy-client-ip-trust.md))
- 이와 별도로 계정 메일 전체(인증번호·재설정)에 **전역 발송 상한**이 있다. 실제 발송을 세며 기본값은 100건/1시간과 300건/24시간이고 설정 `feelm.mail.send-budget`으로 관리한다
- 전역 발송 상한은 요청이 아니라 발송 시점에 적용하므로 **응답을 바꾸지 않는다**. 상한을 넘긴 시점의 메일은 보내지 않고 버리며 응답은 `202` 그대로다. 사용자의 재요청이 복구 경로다
- 전역 상한에 걸린 동안에는 **새 인증번호를 발급하지 않는다**. 이미 보낸 번호를 무효화하고 새 메일도 보내지 않으면 사용자가 가진 유효한 번호만 사라지기 때문이다
- 발송 상한을 확인할 수 없으면(저장소 장애) 메일을 보내지 않는다. 다만 요청 단위 제한도 fail-closed라서 같은 장애에서는 그 판정이 먼저 걸려 요청이 `500 INTERNAL_ERROR`가 되므로, 저장소 장애 중 실제로 관측되는 응답은 `500`이다
- 메일 발송은 응답과 분리해 처리한다. 발송 실패·유실은 응답을 바꾸지 않으며 재요청이 복구 경로다
- 어느 축에서 걸렸든 `429`의 상태와 본문은 같고 `Retry-After`는 주지 않는다. 걸린 축이나 남은 시간을 알려 주면 우회를 돕는다

### 상태값

없음

### 미정 사항

없음

## API-079 이메일 인증번호 확인

`POST` `/api/v1/auth/email/verification-code/verify`

| 항목 | 값 |
|---|---|
| 단계 | 1차 MVP |
| 인증 | 공개 |
| 관련 요구사항 | FR-01 |
| 화면 | ② 회원가입 |
| 미정 사항 | 없음 |
| 비고 | 가입(API-001)에 쓰는 토큰을 발급 |

### 설명

발송한 인증번호(API-078)를 대조하고, 통과하면 가입(API-001)에 쓰는 단기 `emailVerificationToken`을 발급한다.

### 인증 및 권한

- 공개

### Path Parameters

없음

### Query Parameters

없음

### Request Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | string | O | 인증번호를 받은 이메일 |
| `code` | string | O | 6자리 숫자 |

```json
{ "email": "user@example.com", "code": "032815" }
```

### Response

`200 OK`

```json
{
  "emailVerificationToken": "eyJhbGciOi...",
  "expiresIn": 600
}
```

### 오류 응답

- `400 VALIDATION_FAILED` 형식 오류(6자리 숫자가 아님)
- `400 VERIFICATION_CODE_MISMATCH` 번호 불일치
- `400 VERIFICATION_CODE_EXHAUSTED` 시도 상한을 넘겨 폐기된 번호. 새 번호를 받아야 한다(API-078)
- `410 VERIFICATION_CODE_EXPIRED` 만료됐거나 발급된 번호가 없음
- `429 RATE_LIMITED` 시도 상한을 넘긴 요청

### 처리 규칙

- 대조는 저장된 SHA-256 해시로 한다. 요청의 번호 원문은 로그를 포함해 어디에도 남기지 않는다
- 시도는 인증번호 1건당 5회까지 센다. 1~5회째는 맞으면 `200`, 틀리면 `400 VERIFICATION_CODE_MISMATCH`다
- 상한을 넘긴 6회째 요청은 대조하지 않고 `429 RATE_LIMITED`로 거절하며 그 시점에 번호를 폐기한다. 그 뒤 같은 번호로 오는 요청은 `400 VERIFICATION_CODE_EXHAUSTED`다
- 6자리 숫자는 10^6짜리 무차별 대입 표면이므로 이 시도 제한이 이 경로의 핵심 방어다. 번호 발급 자체가 메일 축 제한(API-078)을 받으므로 별도의 IP 축은 두지 않는다. 한 IP가 한 시간에 얻을 수 있는 대입 횟수는 발송 한도와 시도 상한의 곱으로 묶인다
- 성공하면 인증번호를 즉시 무효화한다. 같은 번호로 토큰을 두 번 받을 수 없다
- 발급 토큰은 서명 토큰이며 이메일과 토큰 종류를 담는다. 소셜 가입 토큰(API-007)과 종류가 구분되어 서로의 자리에 쓸 수 없다
- 토큰 유효기간 10분(가정, 설정값 `feelm.jwt.email-verification-token-ttl`)
- 토큰은 가입(API-001)에서 1회만 쓸 수 있다. 사용한 토큰의 식별자는 만료 시점까지 보관해 재사용을 막는다
- 이 경로는 계정을 만들지 않는다. 확인만 하고 가입을 끝내지 않으면 아무 흔적도 남지 않는다

### 상태값

없음

### 미정 사항

없음
