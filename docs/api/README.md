# API 명세

FEELM 서비스 REST API 명세입니다. Notion 「설계 > API 명세서」 초안(2026-08-30)을 저장소로 옮겼으며, 저장소의 이 문서를 API 계약 기준 문서로 관리합니다.
구현 전 팀 결정이 필요한 값은 각 API의 **미정 사항**과 [공통 규약](#공통-규약)의 미정 사항에 정리되어 있습니다.

## 문서 구성

| 문서 | 도메인 | API 수 |
|---|---|---|
| [사용자](./users.md) | 사용자 | 22 |
| [온보딩·OTT](./onboarding-ott.md) | 온보딩, OTT | 5 |
| [영화·검색](./movies-search.md) | 영화, 검색 | 3 |
| [감상](./watch.md) | 감상 | 7 |
| [취향](./taste.md) | 취향 | 5 |
| [추천](./recommendation.md) | 추천 | 7 |
| [파티](./party.md) | 파티 | 10 |
| [소셜](./social.md) | 소셜 | 5 |
| [리포트·트렌드](./report-trend.md) | 리포트, 트렌드 | 5 |
| [알림](./notifications.md) | 인앱 알림·브라우저 푸시 | 8 |

FastAPI 내부 API 초안은 ADR-0016을 거쳐 ADR-0017로 대체되어 사용하지 않습니다. 현재 이벤트·Redis 경계는 [스트리밍 계약](./streaming-contract.md), 이전 계약과 변경 배경은 [폐기된 내부 API 초안](./internal.md)을 참고합니다.

## 범례

### 사용자 평가 점수 공통 계약

개인 평가 `score`는 JSON `number`이며 0.5~5.0점, 0.5점 단위다(FR-19, API-036·037).
평가·영화 상세·한줄평·프레임·공개 프로필의 요청과 응답에서 같은 원값을 전달한다.
평균 평점과 모델 예측 점수는 별도 지표이므로 0.5 배수 조건을 적용하지 않는다.
자세한 검증과 오류는 [평가 생성·수정](./watch.md)을 따른다.

### 표기

- **ID**: `API-nnn`. Jira·MR·코드 주석에서 이 ID로 참조합니다. `API-000`은 공통 규약입니다.
- **채번**: 번호는 순차 증가하며 **재사용하지 않습니다**. 삭제한 API의 번호도 비워 두고 [삭제된 API](#삭제된-api)에 사유를 남깁니다. 같은 번호가 다른 계약을 가리키면 Jira·MR·코드 주석의 과거 참조가 조용히 틀린 곳을 가리키게 됩니다.
- **단계**: `1차 MVP` → `2차 MVP` → `확장` 순으로 구현합니다.
- **인증**: `공개` 비회원 호출 가능 / `회원` Access Token 필수 / `선택` 비회원 가능하나 로그인 시 개인화·차단 필터 적용
- **관련 요구사항**: [요구사항 정의서](../requirements.md)의 FR/NFR ID
- **화면**: `README.md` 와이어프레임 번호(①~⑫)
- **(가정)**: 팀 결정 전 AI 초안 기본값입니다. 채택 여부를 확정한 뒤 문구를 갱신합니다.
- 모든 API의 상태는 현재 `초안`입니다. 상태는 Notion DB에서 관리합니다.

## API 목록

| ID | 이름 | Method | Endpoint | 도메인 | 단계 | 인증 | 관련 요구사항 | 미정 |
|---|---|---|---|---|---|---|---|---|
| API-001 | [이메일 회원가입](./users.md#api-001-이메일-회원가입) | POST | `/auth/signup` | 사용자 | 1차 MVP | 공개 | FR-01 |  |
| API-004 | [이메일 로그인](./users.md#api-004-이메일-로그인) | POST | `/auth/login` | 사용자 | 1차 MVP | 공개 | FR-01 |  |
| API-005 | [로그아웃](./users.md#api-005-로그아웃) | POST | `/auth/logout` | 사용자 | 1차 MVP | 회원 | FR-01 |  |
| API-006 | [토큰 재발급](./users.md#api-006-토큰-재발급) | POST | `/auth/token/refresh` | 사용자 | 1차 MVP | 공개 | FR-01 |  |
| API-007 | [소셜 로그인](./users.md#api-007-소셜-로그인) | POST | `/auth/oauth/{provider}` | 사용자 | 1차 MVP | 공개 | FR-35 |  |
| API-008 | [소셜 회원가입 완료](./users.md#api-008-소셜-회원가입-완료) | POST | `/auth/oauth/signup` | 사용자 | 1차 MVP | 공개 | FR-35 |  |
| API-009 | [소셜 계정 연결](./users.md#api-009-소셜-계정-연결) | POST | `/users/me/oauth/{provider}` | 사용자 | 1차 MVP | 회원 | FR-35 | O |
| API-010 | [비밀번호 재설정 요청](./users.md#api-010-비밀번호-재설정-요청) | POST | `/auth/password/reset-request` | 사용자 | 1차 MVP | 공개 | FR-37 |  |
| API-011 | [비밀번호 재설정](./users.md#api-011-비밀번호-재설정) | POST | `/auth/password/reset` | 사용자 | 1차 MVP | 공개 | FR-37 |  |
| API-012 | [비밀번호 변경](./users.md#api-012-비밀번호-변경) | PUT | `/users/me/password` | 사용자 | 1차 MVP | 회원 | FR-37 |  |
| API-013 | [회원 탈퇴](./users.md#api-013-회원-탈퇴) | DELETE | `/users/me` | 사용자 | 1차 MVP | 회원 | FR-37 | O |
| API-014 | [내 프로필 조회](./users.md#api-014-내-프로필-조회) | GET | `/users/me` | 사용자 | 1차 MVP | 회원 | FR-38 |  |
| API-015 | [내 프로필 수정](./users.md#api-015-내-프로필-수정) | PATCH | `/users/me` | 사용자 | 1차 MVP | 회원 | FR-38 |  |
| API-016 | [닉네임 사용 가능 확인](./users.md#api-016-닉네임-사용-가능-확인) | GET | `/users/nickname-availability` | 사용자 | 1차 MVP | 공개 | FR-01, FR-38 |  |
| API-017 | [알림 설정 조회](./users.md#api-017-알림-설정-조회) | GET | `/users/me/notification-settings` | 사용자 | 1차 MVP | 회원 | FR-38 | O |
| API-018 | [알림 설정 변경](./users.md#api-018-알림-설정-변경) | PUT | `/users/me/notification-settings` | 사용자 | 1차 MVP | 회원 | FR-38 |  |
| API-019 | [사용자 검색](./users.md#api-019-사용자-검색) | GET | `/users/search` | 사용자 | 1차 MVP | 회원 | FR-20, FR-39 |  |
| API-020 | [사용자 차단](./users.md#api-020-사용자-차단) | POST | `/users/{userId}/block` | 사용자 | 1차 MVP | 회원 | FR-39 | O |
| API-021 | [사용자 차단 해제](./users.md#api-021-사용자-차단-해제) | DELETE | `/users/{userId}/block` | 사용자 | 1차 MVP | 회원 | FR-39 |  |
| API-022 | [차단 목록 조회](./users.md#api-022-차단-목록-조회) | GET | `/users/me/blocks` | 사용자 | 1차 MVP | 회원 | FR-39 |  |
| API-023 | [초기 취향 카드 목록](./onboarding-ott.md#api-023-초기-취향-카드-목록) | GET | `/onboarding/cards` | 온보딩 | 1차 MVP | 회원 | FR-02 |  |
| API-024 | [초기 취향 제출](./onboarding-ott.md#api-024-초기-취향-제출) | POST | `/onboarding/preferences` | 온보딩 | 1차 MVP | 회원 | FR-02 |  |
| API-025 | [OTT 목록 조회](./onboarding-ott.md#api-025-ott-목록-조회) | GET | `/otts` | OTT | 1차 MVP | 공개 | FR-03, FR-16 |  |
| API-026 | [내 구독 OTT 조회](./onboarding-ott.md#api-026-내-구독-ott-조회) | GET | `/users/me/otts` | OTT | 1차 MVP | 회원 | FR-03 |  |
| API-027 | [내 구독 OTT 설정](./onboarding-ott.md#api-027-내-구독-ott-설정) | PUT | `/users/me/otts` | OTT | 1차 MVP | 회원 | FR-03 |  |
| API-028 | [영화 검색](./movies-search.md#api-028-영화-검색) | GET | `/movies/search` | 검색 | 1차 MVP | 회원 | FR-14, FR-15 | O |
| API-029 | [검색 필터 옵션 조회](./movies-search.md#api-029-검색-필터-옵션-조회) | GET | `/movies/filter-options` | 검색 | 1차 MVP | 공개 | FR-15 |  |
| API-030 | [영화 상세 조회](./movies-search.md#api-030-영화-상세-조회) | GET | `/movies/{movieId}` | 영화 | 1차 MVP | 선택 | FR-34, FR-16, FR-13 | O |
| API-031 | [유사 영화 추천](./recommendation.md#api-031-유사-영화-추천) | GET | `/movies/{movieId}/similar` | 추천 | 1차 MVP | 선택 | FR-11 | O |
| API-032 | [공개 한줄평 목록](./social.md#api-032-공개-한줄평-목록) | GET | `/movies/{movieId}/reviews` | 소셜 | 1차 MVP | 회원 | FR-26, FR-34, FR-39 |  |
| API-033 | [OTT 링크 클릭 기록](./watch.md#api-033-ott-링크-클릭-기록) | POST | `/movies/{movieId}/ott-clicks` | 감상 | 1차 MVP | 회원 | FR-17 | O |
| API-034 | [감상 기록 목록](./watch.md#api-034-감상-기록-목록) | GET | `/watch-records` | 감상 | 1차 MVP | 회원 | FR-18 |  |
| API-035 | [감상 확인 응답](./watch.md#api-035-감상-확인-응답) | POST | `/watch-records/{watchRecordId}/confirm` | 감상 | 1차 MVP | 회원 | FR-18 |  |
| API-036 | [평가 생성](./watch.md#api-036-평가-생성) | POST | `/movies/{movieId}/ratings` | 감상 | 1차 MVP | 회원 | FR-19, FR-26, FR-07, FR-09 | O |
| API-037 | [평가 수정](./watch.md#api-037-평가-수정) | PUT | `/ratings/{ratingId}` | 감상 | 1차 MVP | 회원 | FR-19, FR-26 |  |
| API-038 | [평가 삭제](./watch.md#api-038-평가-삭제) | DELETE | `/ratings/{ratingId}` | 감상 | 1차 MVP | 회원 | FR-19, FR-07, FR-09, FR-18 | O |
| API-039 | [내 평가 목록](./watch.md#api-039-내-평가-목록) | GET | `/users/me/ratings` | 감상 | 1차 MVP | 회원 | FR-19 |  |
| API-040 | [취향 분석·키워드 조회](./taste.md#api-040-취향-분석키워드-조회) | GET | `/users/me/taste` | 취향 | 1차 MVP | 회원 | FR-04, FR-05 | O |
| API-041 | [팝콘 버킷 조회](./taste.md#api-041-팝콘-버킷-조회) | GET | `/users/me/popcorn` | 취향 | 1차 MVP | 회원 | FR-06, FR-09, FR-32 | O |
| API-042 | [내 필름(프레임 목록) 조회](./taste.md#api-042-내-필름프레임-목록-조회) | GET | `/users/me/frames` | 취향 | 1차 MVP | 회원 | FR-07 |  |
| API-043 | [프레임 상세 조회](./taste.md#api-043-프레임-상세-조회) | GET | `/frames/{frameId}` | 취향 | 1차 MVP | 회원 | FR-08 |  |
| API-044 | [팝콘 맛 기준정보 조회](./taste.md#api-044-팝콘-맛-기준정보-조회) | GET | `/popcorn-flavors` | 취향 | 1차 MVP | 공개 | FR-06 | O |
| API-045 | [기본 추천 조회](./recommendation.md#api-045-기본-추천-조회) | GET | `/recommendations` | 추천 | 1차 MVP | 회원 | FR-10, FR-12, FR-13 | O |
| API-047 | [관심없음 표시](./recommendation.md#api-047-관심없음-표시) | POST | `/movies/{movieId}/dismiss` | 추천 | 1차 MVP | 회원 | FR-36 | O |
| API-048 | [추천 이력 조회](./recommendation.md#api-048-추천-이력-조회) | GET | `/recommendations/history` | 추천 | 1차 MVP | 회원 | FR-33, FR-13 |  |
| API-049 | [파티 생성](./party.md#api-049-파티-생성) | POST | `/parties` | 파티 | 1차 MVP | 회원 | FR-20 | O |
| API-050 | [내 파티 목록](./party.md#api-050-내-파티-목록) | GET | `/parties` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-051 | [파티 초대](./party.md#api-051-파티-초대) | POST | `/parties/{partyId}/invitations` | 파티 | 1차 MVP | 회원 | FR-20, FR-39 | O |
| API-052 | [받은 초대 목록](./party.md#api-052-받은-초대-목록) | GET | `/parties/invitations` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-053 | [초대 수락](./party.md#api-053-초대-수락) | POST | `/parties/invitations/{invitationId}/accept` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-054 | [초대 거절](./party.md#api-054-초대-거절) | POST | `/parties/invitations/{invitationId}/decline` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-055 | [파티 나가기](./party.md#api-055-파티-나가기) | POST | `/parties/{partyId}/leave` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-056 | [파티 종료](./party.md#api-056-파티-종료) | DELETE | `/parties/{partyId}` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-057 | [파티원 팝콘 조회](./party.md#api-057-파티원-팝콘-조회) | GET | `/parties/{partyId}/popcorn` | 파티 | 1차 MVP | 회원 | FR-21 | O |
| API-058 | [파티 영화 추천](./party.md#api-058-파티-영화-추천) | GET | `/parties/{partyId}/recommendations` | 파티 | 1차 MVP | 회원 | FR-22, FR-13 | O |
| API-059 | [내 리포트 목록](./report-trend.md#api-059-내-리포트-목록) | GET | `/users/me/reports` | 리포트 | 2차 MVP | 회원 | FR-23 |  |
| API-060 | [리포트 상세 조회](./report-trend.md#api-060-리포트-상세-조회) | GET | `/reports/{reportId}` | 리포트 | 2차 MVP | 회원 | FR-23 | O |
| API-061 | [리포트 이미지 카드 생성](./report-trend.md#api-061-리포트-이미지-카드-생성) | POST | `/reports/{reportId}/image` | 리포트 | 2차 MVP | 회원 | FR-24, NFR-07 | O |
| API-062 | [공개 프로필 조회](./social.md#api-062-공개-프로필-조회) | GET | `/users/{publicId}/profile` | 소셜 | 2차 MVP | 선택 | FR-25, FR-39, NFR-07 |  |
| API-063 | [취향 비교](./social.md#api-063-취향-비교) | GET | `/users/{publicId}/taste-comparison` | 소셜 | 2차 MVP | 회원 | FR-25 | O |
| API-064 | [급상승 영화](./report-trend.md#api-064-급상승-영화) | GET | `/movies/trending` | 트렌드 | 2차 MVP | 공개 | FR-27 | O |
| API-065 | [사용자 랭킹](./social.md#api-065-사용자-랭킹) | GET | `/rankings/users` | 소셜 | 확장 | 선택 | FR-28 | O |
| API-066 | [오늘의 영화](./recommendation.md#api-066-오늘의-영화) | GET | `/recommendations/today` | 추천 | 확장 | 회원 | FR-30 | O |
| API-067 | [커뮤니티](./social.md#api-067-커뮤니티) | - | `/community (미정)` | 소셜 | 확장 | 회원 | FR-31 | O |
| API-068 | [OTT 구독 추천](./recommendation.md#api-068-ott-구독-추천) | GET | `/recommendations/otts` | 추천 | 2차 MVP | 회원 | FR-40 | O |
| API-069 | [파티 멤버 강퇴](./party.md#api-069-파티-멤버-강퇴) | DELETE | `/parties/{partyId}/members/{userId}` | 파티 | 1차 MVP | 회원 | FR-20 |  |
| API-070 | [인앱 알림 목록](./notifications.md#api-070-인앱-알림-목록) | GET | `/notifications` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-071 | [미확인 알림 개수](./notifications.md#api-071-미확인-알림-개수) | GET | `/notifications/unread-count` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-072 | [알림 읽음 처리](./notifications.md#api-072-알림-읽음-처리) | PATCH | `/notifications/{notificationId}/read` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-073 | [전체 알림 읽음 처리](./notifications.md#api-073-전체-알림-읽음-처리) | PATCH | `/notifications/read-all` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-074 | [알림 숨김](./notifications.md#api-074-알림-숨김) | DELETE | `/notifications/{notificationId}` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-075 | [Web Push 공개 설정 조회](./notifications.md#api-075-web-push-공개-설정-조회) | GET | `/notifications/push/config` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-076 | [Web Push 구독 등록·갱신](./notifications.md#api-076-web-push-구독-등록갱신) | PUT | `/notifications/push/subscriptions` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-077 | [Web Push 구독 해제](./notifications.md#api-077-web-push-구독-해제) | DELETE | `/notifications/push/subscriptions/{subscriptionId}` | 알림 | 2차 MVP | 회원 | FR-41 |  |
| API-078 | [이메일 인증번호 발송](./users.md#api-078-이메일-인증번호-발송) | POST | `/auth/email/verification-code` | 사용자 | 1차 MVP | 공개 | FR-01 |  |
| API-079 | [이메일 인증번호 확인](./users.md#api-079-이메일-인증번호-확인) | POST | `/auth/email/verification-code/verify` | 사용자 | 1차 MVP | 공개 | FR-01 |  |
| API-080 | [리포트 첫 발급 기록](./report-trend.md#api-080-리포트-첫-발급-기록) | POST | `/reports/{reportId}/issue` | 리포트 | 2차 MVP | 회원 | FR-23 |  |
| API-081 | [프로필 사진 등록·교체](./users.md#api-081-프로필-사진-등록교체) | PUT | `/users/me/avatar` | 사용자 | 2차 MVP | 회원 | FR-38 |  |
| API-082 | [프로필 사진 삭제](./users.md#api-082-프로필-사진-삭제) | DELETE | `/users/me/avatar` | 사용자 | 2차 MVP | 회원 | FR-38 |  |
| API-083 | [파티 추천 이력 조회](./party.md#api-083-파티-추천-이력-조회) | GET | `/parties/{partyId}/recommendations/history` | 파티 | 2차 MVP | 회원 | FR-22, FR-33 |  |

### 삭제된 API

| ID | 이름 | 삭제 | 사유 |
|---|---|---|---|
| API-002 | 이메일 인증 확인 | S15P21E106-601 | 가입 흐름이 메일 링크 인증에서 인증번호 확인으로 바뀌면서 사라졌습니다. 대체 경로는 API-079입니다 |
| API-003 | 인증 메일 재발송 | S15P21E106-601 | 같은 개정. 재발송은 인증번호 발송(API-078)을 다시 호출하는 것으로 대신합니다 |

## 공통 규약

`API-000` · 관련 요구사항 NFR-04, NFR-07

> 이 페이지의 값은 팀 결정 전 **AI 초안 기본값**이다. 각 항목의 채택 여부를 회의에서 확정하고 '미정 사항'을 정리한다.

### 설명

모든 API가 공유하는 URL, 인증, 요청·응답 형식, 오류 형식, 페이지네이션, 공통 상태값을 정의한다. 개별 API 페이지는 이 규약과 다른 부분만 기술한다.

### Base URL 및 버전

- Base URL: `https://<host>/api/v1`
- 하위 호환이 깨지는 변경은 `/api/v2`로 분리한다.
- 경로는 소문자 kebab-case 복수형 리소스를 사용한다. 예: `/watch-records`, `/popcorn-flavors`

### 인증 및 권한

- 방식: JWT Bearer. 로그인 응답의 `accessToken`을 `Authorization: Bearer <token>` 헤더로 전달한다.
- Refresh Token은 `httpOnly; Secure; SameSite=Lax` 쿠키 `refresh_token`으로 발급하며 본문에 노출하지 않는다.
- Refresh Token은 Redis에 TTL로 저장하고 사용 시 회전(rotation)한다 (ADR-0009). 유효기간은 설정값(기본 Access 30분, Refresh 14일).
- 인증 구분
	- **공개**: 비회원 호출 가능
	- **회원**: 유효한 Access Token 필수. 없거나 만료 시 `401`
	- **선택**: 토큰 없이 호출 가능. 토큰이 있으면 차단 필터·개인화가 적용된다
- 본인 리소스는 경로 `me`로 접근한다. 다른 사용자의 비공개 리소스 접근은 `403`이 아니라 존재하지 않는 것처럼 `404`로 응답한다(NFR-07, 정보 노출 방지).

### 요청 형식

- `Content-Type: application/json; charset=utf-8`
- JSON 키는 camelCase
- 시간은 ISO 8601 UTC 문자열. 예: `2026-08-30T05:21:00Z`
- 식별자는 64bit 정수(`Long`)이며 JSON에서는 숫자로 전달한다. 공개 URL에 노출되는 사용자 식별자는 별도 `publicId`(불투명 문자열)를 사용한다 (ADR-0008).
- 열거형 값은 UPPER_SNAKE_CASE 문자열

### 응답 형식

- 성공 응답은 envelope 없이 리소스 본문을 그대로 반환한다.
- 생성은 `201 Created` + 생성된 리소스, 삭제는 `204 No Content`
- 목록 응답은 아래 페이지네이션 구조를 따른다.

#### 페이지네이션 (cursor)

- 요청: `?cursor=<opaque>&size=<int>` (size 기본 20, 최대 50)
- 응답:

```json
{
  "items": [],
  "nextCursor": "eyJpZCI6MTIzfQ",
  "hasNext": true
}
```

- `cursor`는 서버가 발급한 불투명 문자열이며 클라이언트가 해석하지 않는다.

### 오류 응답

모든 오류는 동일한 구조로 응답한다.

```json
{
  "code": "NICKNAME_DUPLICATED",
  "message": "이미 사용 중인 닉네임입니다.",
  "details": { "field": "nickname" }
}
```

- `code`: UPPER_SNAKE_CASE 의미 코드. 클라이언트 분기 기준
- `message`: 사용자에게 표시 가능한 한국어 문구
- `details`: 선택. 검증 실패 필드 등 부가 정보

#### 공통 오류 코드

| HTTP | code | 의미 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 필수값 누락, 형식 오류. `details.errors[]`에 필드별 사유 |
| 401 | `UNAUTHORIZED` | 토큰 없음 또는 서명 불일치 |
| 401 | `TOKEN_EXPIRED` | Access Token 만료. 클라이언트는 refresh 후 재시도 |
| 403 | `FORBIDDEN` | 인증됐으나 권한 없음(예: 파티장 전용) |
| 404 | `RESOURCE_NOT_FOUND` | 리소스 없음 또는 접근 불가 비공개 리소스 |
| 405 | `METHOD_NOT_ALLOWED` | 요청 경로에서 허용하지 않는 HTTP 메서드 |
| 409 | `CONFLICT` | 상태 충돌. 개별 API에서 구체 코드로 세분화 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 요청 `Content-Type` |
| 429 | `RATE_LIMITED` | 요청 제한 초과 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |
| 503 | `RECOMMENDATION_UNAVAILABLE` | 추천 서비스 장애. 핵심 기능은 정상 동작(NFR-05) |
| 503 | `RECOMMENDATION_PREPARING` | 추천을 준비 중이라 보여줄 카드가 없음. 장애가 아니며 재시도로 회복될 수 있음(API-045) |
| 503 | `PUSH_UNAVAILABLE` | Web Push 비활성화 또는 일시적 사용 불가 |
| 503 | `TREND_UNAVAILABLE` | Redis 조회 장애로 최신 또는 마지막 정상 급상승 결과(B)를 조회할 수 없음(API-064) |

### 처리 규칙

- 차단 관계 필터(FR-39)는 서버가 적용한다. 클라이언트는 차단 목록을 받아 직접 걸러내지 않는다.
- 공개 범위 `PRIVATE` 리소스는 본인 외 조회 시 `404`
- 추천 서비스 장애 시 추천 API만 `503`을 반환하고 영화·검색·평가 API는 영향받지 않는다(NFR-05).
- 멱등성: `PUT`·`DELETE`는 멱등. 동일 리소스 중복 `POST`는 `409`로 거절한다.

### 상태값

#### WatchStatus — 감상 상태 (FR-17, FR-18, FR-19)

| 값 | 의미 | 전이 |
|---|---|---|
| `LINK_CLICKED` | OTT 보러가기 클릭. 감상 확정 아님 | 클릭 24시간 후 접속 시 감상 확인 질문 대상 |
| `WATCHED_UNRATED` | 감상 완료(평가 전) | 감상 확인에 '감상함' 응답 |
| `RATED` | 평가 완료. 프레임이 생성되고 팝콘 버킷 집계에 반영됨 | 평가 저장. 평가 삭제 시 `WATCHED_UNRATED`로 복귀 |
| `EXPIRED` | 만료. 추천 신호로 사용하지 않음 | '감상하지 않음' 응답 또는 7일 미응답 |

#### Visibility — 공개 범위

- `PUBLIC`, `PRIVATE` (기본값 `PRIVATE`)

#### RecommendationType — 추천 유형

- `TASTE` 취향 기반(FR-10), `DISCOVERY` 취향 발견(FR-12), `PARTY` 파티(FR-22). 유사 영화는 영화 벡터 기반 별도 API이며 추천 이력 유형에 포함하지 않는다

#### OnboardingPreference — 온보딩 반응

- `LIKE`, `DISLIKE` (제외한 카드는 저장하지 않음)

#### InvitationStatus — 파티 초대 상태

- `PENDING`, `ACCEPTED`, `DECLINED`

#### PartyStatus — 파티 상태

- `ACTIVE`, `CLOSED`

#### OAuthProvider

- `GOOGLE`, `KAKAO`, `NAVER`

#### NotificationType — 알림 설정 항목 (FR-38)

- `RATING`, `RECOMMENDATION`, `MARKETING`

#### InAppNotificationType — 인앱 알림 사건 (FR-41)

- `PARTY_INVITE`, `PARTY_INVITE_ACCEPTED`, `WATCH_CONFIRM`. 알림 설정 항목과 인앱 사건 종류는 서로 다른 계약이다.

#### PopcornFlavor — 팝콘 맛 (FR-06)

- 8종 코드·표시명·색상은 기준정보를 따르고, 영화는 ADR-0020·S15P21E106-439의 고정 상위 맛 결과로 하나에 배정한다. 코드는 `/popcorn-flavors`(API-044)로 조회한다.

### 미정 사항

- [ ] Rate limit 정책과 임계치 — 설정값, 운영 전 확정
- [ ] Python Fold-in Consumer·Spark Streaming 구현의 Kafka Consumer Group과 운영 설정 (ADR-0017, NFR-04)

해소된 항목(2026-08-30~2026-09-17 결정): 식별자 타입·공개 식별자(ADR-0008), Refresh 전달·저장(ADR-0009), 오류 `code` 규칙(의미 코드), 1차 알림 폴링(ADR-0011), 2차 인앱 알림 정본(ADR-0021). 결정 기록은 [ADR](../adr/README.md)과 [ERD 5장](../erd.md#5-물리-스키마-전-결정-사항)을 참조합니다.
