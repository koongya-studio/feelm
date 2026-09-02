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
| `password` | string | O | 8~64자, 영문·숫자 포함(가정) |
| `nickname` | string | O | 2~20자(가정). 공백·대소문자 무시 중복 검사 |
| `birthDate` | string | O | 생년월일 `YYYY-MM-DD` (D-8 결정). 실명은 수집하지 않음 |
| `gender` | string | O | `MALE` / `FEMALE` / `OTHER`, 선택 입력(NULL 허용) |

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

### 처리 규칙

- 가입 성공 시 인증 메일을 발송한다. 인증 토큰 유효기간 24시간(가정)
- 미인증 상태로 24시간 경과한 계정은 재가입을 허용한다(가정)
- 비밀번호는 bcrypt 등 단방향 해시로 저장한다
- 실명은 수집하지 않는다

### 상태값

- 계정 상태: `PENDING_VERIFICATION` → `ACTIVE` (API-002 성공 시)

### 미정 사항

- [ ] 비밀번호 정책(길이·문자 조합)
- [ ] 닉네임 길이·허용 문자
- [ ] 미인증 계정 정리 주기

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
{ "token": "eyJhbGciOi..." }
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

- `429 RATE_LIMITED` 재발송 간격 미달(1분, 가정)

### 처리 규칙

- 존재하지 않는 이메일, 이미 활성화된 계정이어도 `202`로 응답한다(계정 존재 여부 노출 방지)
- 이전 토큰은 무효화하고 새 토큰을 발급한다

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
- `429 RATE_LIMITED` 연속 실패 제한(5회/10분, 가정)

### 처리 규칙

- `onboardingCompleted`가 `false`면 프론트는 ④ 취향 초기 설정으로 이동한다
- 탈퇴한 계정은 `INVALID_CREDENTIALS`로 응답한다

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
| 미정 사항 | 있음 |
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
- `400 UNSUPPORTED_PROVIDER`
- `502 OAUTH_PROVIDER_ERROR` 제공자 응답 오류

### 처리 규칙

- 소셜 계정의 이메일이 기존 이메일 계정과 같아도 **자동 통합하지 않는다**. 별도 신규 가입 또는 로그인 후 연결(API-009)
- `signupToken` 유효기간 10분(가정), 제공자 식별자(`providerUserId`)를 담는다
- 로그인 응답의 `onboardingCompleted`는 API-004와 동일하게 처리

### 상태값

- OAuthProvider: `GOOGLE`, `KAKAO`, `NAVER`

### 미정 사항

- [ ] 인가 코드 교환 위치: 백엔드(가정) vs 프론트에서 토큰 교환 후 전달
- [ ] `redirectUri` 허용 목록

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
- `409 NICKNAME_DUPLICATED`
- `409 OAUTH_ACCOUNT_ALREADY_LINKED` 토큰 발급 후 다른 계정에 연결된 경우

### 처리 규칙

- 소셜 가입 계정은 이메일 인증 없이 즉시 `ACTIVE`
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

- `409 OAUTH_ACCOUNT_ALREADY_LINKED` 해당 소셜 계정이 다른 사용자에 연결됨
- `409 PROVIDER_ALREADY_LINKED` 같은 provider가 이미 연결됨

### 처리 규칙

- 연결된 수단 목록은 API-014 내 프로필의 `linkedProviders`로 조회한다

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

- `429 RATE_LIMITED`

### 처리 규칙

- 계정 존재 여부와 무관하게 `202`로 응답한다
- 재설정 토큰 유효기간 30분(가정), 1회용

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

- 성공 시 현재 세션을 제외한 Refresh Token을 폐기한다(가정)

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
- `409 PARTY_OWNER_ACTIVE` 활성 파티의 파티장인 경우 먼저 종료 필요(가정)

### 처리 규칙

- 사용자 상태를 `WITHDRAWN`으로 바꾸고 모든 토큰을 폐기한다
- 이메일·닉네임을 익명화 값으로 치환하고 `password_hash`·`birth_date`·`gender`를 비운다. 연결된 소셜 로그인 수단(`user_auth_providers`)과 인증·비밀번호 재설정 토큰은 삭제한다
- 치환으로 유일 제약이 풀리므로 같은 이메일·닉네임으로 즉시 재가입할 수 있다. 유예기간과 탈퇴 철회는 두지 않는다
- 평가·한줄평·프레임·팝콘 등 개인 데이터는 탈퇴 시 삭제하고, 추천 학습용 익명 평가 데이터 보존 여부는 미정
- 참여 중인 파티에서 제외되고 파티 인원 변경 규칙(FR-20)이 적용된다

### 상태값

- 계정 상태 → `WITHDRAWN`

### 미정 사항

- [x] 탈퇴 후 데이터 보존 범위·기간 — 계정 영역은 확정(익명화 치환, 즉시 재사용). 평가·학습 데이터 범위는 아래 항목에서 계속
- [ ] 공개 한줄평은 삭제 vs '탈퇴한 사용자'로 익명 유지
- [ ] 파티장 탈퇴 시 파티 자동 종료 vs 탈퇴 차단

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

- `stats`는 프레임·팝콘·평가 수이며 FR-07/09 규칙상 서로 같아야 한다(정합성 점검 지표)

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

- 비교 시 공백 제거·소문자 정규화 후 비교한다
- 확인 결과는 예약이 아니므로 가입 시점에 다시 검사한다

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
- 같은 활성 파티에 있으면 파티에서 분리하지는 않는다(가정, 확인 필요)
- 차단은 상대에게 알리지 않는다

### 상태값

없음

### 미정 사항

- [ ] 차단 진입 화면(FR-39 비고: 화면 미정)
- [ ] 같은 파티 소속 사용자 차단 시 파티 처리

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

- `404 RESOURCE_NOT_FOUND` 차단 관계 없음

### 처리 규칙

- 멱등

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

### 상태값

없음

### 미정 사항

없음
