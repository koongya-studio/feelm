# `src/api` 연동 상태표

업무 S15P21E106-395. Epic S15P21E106-363 완료 조건인 **"목업으로 남은 도메인이 무엇이고 왜
남았는지 추적된다"**를 채웁니다. `src/api/`의 함수마다 실제 API를 부르는지, 목업이면 왜 남았고
무엇을 기다리는지 적습니다.

기준 시점은 develop `2dc06f42`(2026-09-21)입니다. 백엔드 컨트롤러(`backend/src/main/**/*Controller.java`)
의 경로와 프론트 호출을 하나씩 맞춰 확인했습니다. 리포트 목록 · 상세 · 첫 발급(API-059 · 060 ·
080)이 생겨 연동했고(업무 -558), 그전 기준 시점은 `54038217`(2026-09-20)입니다.

**갱신 규칙.** 백엔드에 API가 생기거나 프론트가 목업을 실제 호출로 바꾸면 이 표를 같은 MR에서
고칩니다. 목업을 남기는 변경이면 2장에 이유와 선행 업무를 적습니다.

| 표시 | 뜻 |
| --- | --- |
| 실제 | 백엔드를 부른다 |
| 목업 | `mocks/` 값이나 세션 저장소를 돌려준다. 서버에 가지 않는다 |
| 조합 | 다른 실제 함수의 결과를 모으거나 거른다. 스스로 부르는 경로는 없다 |
| 기기 | 이 브라우저에만 두는 값이다. 계약 대상이 아니다 |

## 1. 요약

- **백엔드에 있는 API는 모두 프론트가 부릅니다.** 연결을 기다리는 백엔드 API는 없습니다.
  API-038 평가 삭제는 업무 -724에서, 리포트 목록 · 상세 · 첫 발급(API-059 · 060 · 080)은 업무
  -558에서 붙였습니다
- **목업으로 남은 것은 백엔드에 API가 없거나, 계약에 필드가 없는 것뿐입니다**(2장)
- **쓰는 화면이 없는 목업 함수가 하나 남았습니다**(3장). `getMockPopcornFlavors`는 업무 -707에서 지웠습니다

## 2. 목업으로 남은 것

| 화면 | 함수 | 남은 이유 | 선행 백엔드 업무 | 프론트 연동 업무 |
| --- | --- | --- | --- | --- |
| 7.4 리포트 공유 이미지 · 3.5 이미지로 저장 | `reports.createReportImage` | API-061 백엔드 미구현 | S15P21E106-557 · 인프라 S15P21E106-549(스토리지) | S15P21E106-731 |
| 7.2-B 다른 사용자 공개 프로필 | `users.getPublicProfile` | API-062 백엔드 미구현(2차 MVP) | S15P21E106-551 | S15P21E106-553 |
| 7.1 · 7.2 · 7.2-A 프로필 사진 · 자기소개 · 공개 키워드 · 공개 필름 | `users.getMyProfileView` · `updateProfileExtras` · `getMyPublicProfile` | 계약에 필드가 없다. API-015는 닉네임 · 생년월일 · 성별 · 공개 범위만 받는다(업무 -616) | 없음. 계약부터 정해야 한다 | 없음 |
| 2.6 · 2.7 `이번 주 +N` | `watch.getRatingCounts`의 `weeklyFrameDelta` | 대응 필드가 없어 고정값(`types/unspecified.ts`). 프레임 · 팝콘 수는 실제(API-014 · 041) | 없음 | 없음 |

화면에 없는 기능이라 함수가 없는 것도 적어 둡니다.

| 기능 | 상태 | 선행 백엔드 업무 | 프론트 연동 업무 |
| --- | --- | --- | --- |
| 4.1 급상승 영화(API-064) | 목업을 지워 화면에 없다(업무 -612) | S15P21E106-561 | S15P21E106-562 |
| 3.3 · 3.4 · 7.4 4컷의 `04 캐릭터` 칸과 상세의 캐릭터 설명 | 선정 기준이 없어 `characterCard`와 `fourCut.character`가 null로 온다. 4컷 레이아웃은 그대로 두고 04 칸만 `준비 중`으로, 상세의 캐릭터 문단은 숨긴다(백엔드 답 2026-09-21) | S15P21E106-618에서 기준 확정 후 별도 구현 | 없음. 값이 차면 그려진다 |
| 3.3 · 3.4 · 7.4 4컷의 장르 · 캐릭터 사진, 감독 대표작, 큰 글자 영문 표기 | 시안 자리표시자를 지워 화면에 없다(업무 -558). 계약이 감독 · 배우 칸의 `people.profile_url`만 주고 나머지 사진 · 영문 표기의 출처는 API-060 미정 사항이다. 사진이 없는 칸은 이름만 크게 쓴다 | 없음. 출처부터 정해야 한다 | 없음 |

## 3. 쓰는 화면이 없는 목업 함수

| 함수 | 원래 쓰던 곳 | 상태 |
| --- | --- | --- |
| `taste.getTasteFlow` | 3.1 `요즘 취향 흐름` 문장 · 키워드 근거(CR-16, `TasteFlowView`) | 화면에서 빠졌다. `mocks/taste.ts`는 공개 프로필 목업이 키워드를 빌려 써 남아 있다(리포트 목업은 업무 -558에서 지웠다) |

`taste.getMockPopcornFlavors`는 업무 S15P21E106-707에서 지웠습니다. 5.8 파티원 팝콘은 실제 `getPopcornFlavors`(API-044)를 쓰고 5.9 팝콘 섞기는 맛 코드만 써서, 옮길 화면이 남아 있지 않았습니다.

`taste.getTasteFlow`도 지울 대상입니다. 지우는 일은 이 표를 만드는 업무 밖이라 따로 합니다.

## 4. 함수별 상태

### `auth.ts` — 가입 · 로그인 · 비밀번호

| 함수 | 상태 | API |
| --- | --- | --- |
| `sendEmailVerificationCode` | 실제 | API-078 |
| `verifyEmailVerificationCode` | 실제 | API-079 |
| `signup` | 실제 | API-001 |
| `login` | 실제 | API-004. 개발 서버의 목업 계정(`demo@feelm.kr`)만 서버에 가지 않는다(`mockAccount.ts`) |
| `logout` | 실제 | API-005 |
| `restoreSession` | 조합 | API-006 재발급 → API-014 |
| `requestPasswordReset` | 실제 | API-010 |
| `resetPassword` | 실제 | API-011 |
| `changePassword` | 실제 | API-012 |
| `socialLogin` | 실제 | API-007 |
| `socialSignup` | 실제 | API-008 |
| `linkSocialAccount` | 실제 | API-009 |

`session.refreshAccessToken`이 API-006을 부릅니다. `mockAccount.ts`는 개발 서버 전용 목업 계정이고
배포 빌드에서는 꺼집니다(`import.meta.env.DEV`).

### `users.ts` — 프로필 · 닉네임 · 사용자 검색

| 함수 | 상태 | API |
| --- | --- | --- |
| `getMyProfile` | 실제 | API-014 |
| `updateMyProfile` | 실제 | API-015 |
| `checkNicknameAvailability` | 실제 | API-016 |
| `searchUsers` | 실제 | API-019 |
| `getMyProfileView` | 조합 + **목업** | 닉네임 · 수치는 API-014, 사진 · 소개 · 공개 항목은 목업(2장) |
| `getMyPublicProfile` | 조합 + **목업** | `getMyProfileView`를 공개용으로 거른다 |
| `getPublicProfile` | **목업** | API-062 없음(2장) |
| `updateProfileExtras` | **목업** | 계약 없음(2장) |

### `settings.ts` — 알림 설정 · 차단 · 탈퇴

| 함수 | 상태 | API |
| --- | --- | --- |
| `getNotificationSettings` · `updateNotificationSettings` | 실제 | API-017 · 018 |
| `getBlockedUserList` | 실제 | API-022 |
| `isBlockedUser` | 조합 | API-022 목록에서 찾는다 |
| `blockUser` · `unblockUser` | 실제 | API-020 · 021 |
| `withdraw` | 실제 | API-013 |

### `onboarding.ts` · `otts.ts` · `ottRecommendations.ts`

| 함수 | 상태 | API |
| --- | --- | --- |
| `getOnboardingCards` · `submitOnboardingPreferences` | 실제 | API-023 · 024 |
| `getOtts` | 실제 | API-025 |
| `getMyOttIds` · `setMyOttIds` | 실제 | API-026 · 027 |
| `getOttRecommendations` | 실제 | API-068 |
| `getOttRecommendation` | 조합 | API-068 결과에서 한 곳을 찾는다 |

### `movies.ts` · `search.ts`

| 함수 | 상태 | API |
| --- | --- | --- |
| `getMovieDetail` | 실제 | API-030 |
| `getSimilarMovies` | 실제 | API-031 |
| `getMovieReviews` | 실제 | API-032 |
| `searchMovies` | 실제 | API-028 |
| `countSearchResults` | 실제 | API-028(필터 시트의 결과 수) |
| `getFilterOptions` | 실제 | API-029 |

4.1 최근 검색어는 `mocks/sessionStore`에 둡니다(기기). 계약 대상이 아닙니다.

### `watch.ts` — 감상 · 평가

| 함수 | 상태 | API |
| --- | --- | --- |
| `recordOttClick` | 실제 | API-033 |
| `getWatchRecords` | 실제 | API-034 |
| `findWatchRecord` · `findLinkClickedRecord` · `findWatchedRecord` | 조합 | API-034 목록에서 찾는다 |
| `confirmWatch` | 실제 | API-035 |
| `createRating` | 실제 | API-036 |
| `updateRating` | 실제 | API-037 |
| — | 부르지 않음 | API-038 평가 삭제. 1장 |
| `getMyRatings` | 실제 | API-039 |
| `getRatingCounts` | 조합 + **목업** | API-014 · 041. `weeklyFrameDelta`만 고정값(2장) |

### `taste.ts` — 취향 · 팝콘 · 필름

| 함수 | 상태 | API |
| --- | --- | --- |
| `getMyTaste` | 실제 | API-040 |
| `getPopcornBucket` | 실제 | API-041 |
| `getMyFrames` | 실제 | API-042 |
| `getFrameDetail` | 실제 | API-043 |
| `getPopcornFlavors` | 실제 | API-044 |
| `getTasteFlow` | **목업** · 쓰는 곳 없음 | 3장 |

### `recommendations.ts`

| 함수 | 상태 | API |
| --- | --- | --- |
| `getRecommendations` | 실제 | API-045 |
| `dismissMovie` | 실제 | API-047 |
| `getRecommendationHistory` | 실제 | API-048 |

### `parties.ts`

| 함수 | 상태 | API |
| --- | --- | --- |
| `createParty` | 실제 | API-049 |
| `getMyParties` | 실제 | API-050 |
| `getParty` | 조합 | API-050 목록에서 찾는다 |
| `inviteToParty` | 실제 | API-051 |
| `getMyInvitations` | 실제 | API-052 |
| `getInvitation` | 조합 | API-052 목록에서 찾는다 |
| `acceptInvitation` · `declineInvitation` | 실제 | API-053 · 054 |
| `leaveParty` · `closeParty` | 실제 | API-055 · 056 |
| `getPartyPopcorn` | 실제 | API-057 |
| `getPartyRecommendations` | 실제 | API-058(업무 -705) |
| `kickPartyMember` | 실제 | API-069 |

### `reports.ts`

| 함수 | 상태 | API |
| --- | --- | --- |
| `getMyReports` | 실제 | API-059(업무 -558) |
| `getReportDetail` | 실제 | API-060(업무 -558) |
| `issueReport` | 실제 | API-080 첫 발급 기록(업무 -558) |
| `createReportImage` | **목업** | API-061 없음(2장) |
| `getLatestReadyReport` | 조합 | API-059 `current`로 3.1 버튼을 가른다. 목업 시절 세션에 두었던 `본 적 있음` 표시를 서버의 `issuedAt`으로 바꿨다 |

### `notifications.ts` · `push.ts`

| 함수 | 상태 | API |
| --- | --- | --- |
| `getNotifications` | 실제 | API-070(다음 페이지까지 이어 받는다) |
| `getUnreadNotificationCount` | 실제 | API-071 |
| `readNotification` · `readAllNotifications` | 실제 | API-072 · 073 |
| `dismissNotification` | 실제 | API-074 |
| `getLatestPushNotification` | 조합 | 8.2 예시. API-070 목록에서 고른다 |
| `getWebPushConfig` | 실제 | API-075(업무 -583) |
| `registerWebPushSubscription` · `unregisterWebPushSubscription` | 실제 | API-076 · 077 |

브라우저 구독을 만들고 지우는 일은 `src/pwa/webPush.ts`가 맡습니다.

## 5. 함께 보는 문서

| 문서 | 내용 |
| --- | --- |
| [`frontend-structure.md`](./frontend-structure.md) 7장 · 13장 | 데이터 계층 구조 · 목업과 실제 API의 경계 기준 |
| [`../../../docs/api/README.md`](../../../docs/api/README.md) | API 번호별 계약 |
| [`../../src/types/unspecified.ts`](../../src/types/unspecified.ts) | 화면이 쓰지만 계약에 없는 값 |
