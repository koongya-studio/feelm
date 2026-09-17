/*
 * 화면 경로 정의.
 * 화면 목록과 이동 경로는 frontend/docs/screens/screen-spec.md,
 * 라우팅 구조는 frontend/docs/screens/frontend-structure.md 3장을 따른다.
 *
 * ROUTE_PATTERN: 라우터 정의에 쓰는 경로 패턴
 * path: 화면 이동에 쓰는 경로. 파라미터가 있으면 함수로 만든다
 *
 * 화면에서 경로 문자열을 직접 쓰지 않고 이 파일만 사용한다.
 */

export const ROUTE_PATTERN = {
  // 인증 (하단 내비 없음). 0.1 ~ 0.8-A
  authStart: '/start',
  signupMethod: '/signup',
  signupSocial: '/signup/social',
  /**
   * 0.3-A 소셜 가입 정보 입력. 와이어프레임에 없이 새로 만든 화면이다(CR-23).
   * API-008이 닉네임·생년월일·성별을 필수로 받는데 이동표에는 입력 자리가 없었다
   * (S15P21E106-499).
   */
  signupSocialProfile: '/signup/social/profile',
  signupEmail: '/signup/email',
  signupComplete: '/signup/complete',
  login: '/login',
  accountFind: '/account/find',
  passwordReset: '/account/reset',
  /**
   * 소셜 로그인 콜백. 화면 번호가 없는 통과 지점이다.
   *
   * 제공자 콘솔과 백엔드 허용 목록에 등록한 주소와 **정확히 같아야** 한다
   * (`utils/oauth.ts`). 제공자를 경로에 넣지 않고 `state`로 기억하는 것은 콘솔마다
   * 주소를 따로 등록하지 않기 위해서다.
   */
  oauthCallback: '/oauth/callback',
  // 0.8. 인증 메일 링크가 도착하는 곳이라 백엔드 설정값과 같아야 한다(S15P21E106-367)
  verifyEmail: '/verify-email',
  // 0.8-A. 0.8이 인증에 성공하면 넘어온다(팀 결정 2026-09-14, S15P21E106-319)
  verifyEmailComplete: '/verify-email/complete',

  // 온보딩 (하단 내비 없음)
  onboardingTaste: '/onboarding/taste',
  onboardingOtt: '/onboarding/ott',
  onboardingComplete: '/onboarding/complete',

  /*
   * 앱 진입점. **화면이 아니다** — 로그인 전이면 0.1, 로그인했으면 `/film`으로 보내기만
   * 한다(`routes/HomeEntry.tsx`). 홈 화면은 업무 -461에서 없앴다.
   */
  entry: '/',

  // 탭 화면 (하단 내비 표시)
  recommendations: '/recommendations',
  film: '/film',
  popcornBucket: '/film/popcorn',
  search: '/search',
  party: '/party',
  my: '/my',

  // 구독 OTT 관리 (6.1). 탭 화면이지만 탭 자리는 없고 MY에서 들어간다
  ottManage: '/otts',

  // MY 프로필 (7.2 ~ 7.2-C). 7.1은 위의 `my` 탭이다
  profileEdit: '/my/profile/edit',
  profilePreview: '/my/profile/preview',
  /**
   * 7.2-B 다른 사용자 공개 프로필.
   * 와이어프레임 이동표에 진입 화살표가 없었다. **5.8 파티원 카드에서 들어온다**
   * (팀 결정 2026-09-11, screen-spec-my.md 2장 8번).
   */
  publicProfile: '/users/:userId',

  // 설정 (업무 -307). 7.8만 탭 화면이고 나머지는 전체화면이다
  settings: '/my/settings',
  accountManage: '/my/settings/account',
  /** 7.8-A-2. 와이어프레임에 없는 화면이다(CR-22, S15P21E106-391) */
  passwordChange: '/my/settings/account/password',
  withdraw: '/my/settings/account/withdraw',
  privacySettings: '/my/settings/privacy',
  notificationSettings: '/my/settings/notifications',
  blockedUsers: '/my/settings/blocks',

  /*
   * 취향 리포트 (업무 -308). 같은 리포트를 두 길로 본다.
   * - 필름 쪽: 3.1 → 3.3 새 리포트 발급 → 3.4 상세. `/reports` 아래
   * - MY 쪽: 7.1 · 3.1 → 7.3 목록 → 7.4 상세. `/my/reports` 아래라 하단 탭이 MY로 남는다
   * 3.5 공유는 3.4 · 7.4 위의 시트라 경로가 없다
   */
  tasteReports: '/my/reports',
  myReport: '/my/reports/:reportId',
  reportIssued: '/reports/:reportId/new',
  report: '/reports/:reportId',

  // 알림 (업무 -308). 8.2는 OS 잠금화면 예시라 들어오는 화살표가 없다. 주소로만 연다
  notifications: '/notifications',
  pushPreview: '/notifications/push-preview',

  /*
   * 나에게 맞는 OTT 찾기 (업무 -308). EXT-01 ~ EXT-05. API-068은 확장 단계라 목업이다.
   * `analyzing` · `result` · `insufficient`는 고정 경로라 `:ottId`보다 먼저 맞는다
   */
  ottFinder: '/otts/recommend',
  ottAnalyzing: '/otts/recommend/analyzing',
  ottResult: '/otts/recommend/result',
  ottInsufficient: '/otts/recommend/insufficient',
  ottResultDetail: '/otts/recommend/:ottId',

  // 전체화면 (하단 내비 없음)
  movieDetail: '/movies/:movieId',
  movieRating: '/movies/:movieId/rate',
  ratingResult: '/movies/:movieId/rate/result',
  watchConfirm: '/watch-records/:watchRecordId/confirm',
  frameDetail: '/frames/:frameId',
  partyPopcorn: '/parties/:partyId/popcorn',
  partyPopcornMix: '/parties/:partyId/popcorn/mix',

  // 파티 만들기와 초대 (5.2 ~ 5.7)
  partyNew: '/party/new',
  partySetup: '/party/new/setup',
  partyAnalyzing: '/parties/:partyId/analyzing',
  partyRecommendations: '/parties/:partyId/recommendations',
  partyInvitation: '/parties/invitations/:invitationId',
  partyJoined: '/parties/:partyId/joined',

  // 없는 경로
  notFound: '*',
} as const;

export const path = {
  authStart: () => ROUTE_PATTERN.authStart,
  signupMethod: () => ROUTE_PATTERN.signupMethod,
  signupSocial: () => ROUTE_PATTERN.signupSocial,
  signupSocialProfile: () => ROUTE_PATTERN.signupSocialProfile,
  signupEmail: () => ROUTE_PATTERN.signupEmail,
  signupComplete: () => ROUTE_PATTERN.signupComplete,
  login: () => ROUTE_PATTERN.login,
  accountFind: () => ROUTE_PATTERN.accountFind,
  passwordReset: () => ROUTE_PATTERN.passwordReset,
  oauthCallback: () => ROUTE_PATTERN.oauthCallback,
  verifyEmail: () => ROUTE_PATTERN.verifyEmail,
  verifyEmailComplete: () => ROUTE_PATTERN.verifyEmailComplete,

  onboardingTaste: () => ROUTE_PATTERN.onboardingTaste,
  onboardingOtt: () => ROUTE_PATTERN.onboardingOtt,
  onboardingComplete: () => ROUTE_PATTERN.onboardingComplete,

  recommendations: () => ROUTE_PATTERN.recommendations,
  film: () => ROUTE_PATTERN.film,
  popcornBucket: () => ROUTE_PATTERN.popcornBucket,
  search: () => ROUTE_PATTERN.search,
  party: () => ROUTE_PATTERN.party,
  my: () => ROUTE_PATTERN.my,
  /**
   * MY의 `안 남긴 영화` 탭. 탭은 화면 안 전환이라 라우트가 아니고 주소의 `?tab=`이다.
   * 화면 밖에서 이 탭으로 보내는 곳이 EXT-05뿐이라 여기 한 줄로 둔다(업무 -495).
   */
  myUnrated: () => `${ROUTE_PATTERN.my}?tab=unrated`,
  ottManage: () => ROUTE_PATTERN.ottManage,

  profileEdit: () => ROUTE_PATTERN.profileEdit,
  profilePreview: () => ROUTE_PATTERN.profilePreview,
  publicProfile: (userId: string | number) => `/users/${userId}`,

  settings: () => ROUTE_PATTERN.settings,
  accountManage: () => ROUTE_PATTERN.accountManage,
  passwordChange: () => ROUTE_PATTERN.passwordChange,
  withdraw: () => ROUTE_PATTERN.withdraw,
  privacySettings: () => ROUTE_PATTERN.privacySettings,
  notificationSettings: () => ROUTE_PATTERN.notificationSettings,
  blockedUsers: () => ROUTE_PATTERN.blockedUsers,
  tasteReports: () => ROUTE_PATTERN.tasteReports,
  myReport: (reportId: string | number) => `/my/reports/${reportId}`,
  reportIssued: (reportId: string | number) => `/reports/${reportId}/new`,
  report: (reportId: string | number) => `/reports/${reportId}`,
  notifications: () => ROUTE_PATTERN.notifications,
  pushPreview: () => ROUTE_PATTERN.pushPreview,
  ottFinder: () => ROUTE_PATTERN.ottFinder,
  ottAnalyzing: () => ROUTE_PATTERN.ottAnalyzing,
  ottResult: () => ROUTE_PATTERN.ottResult,
  ottInsufficient: () => ROUTE_PATTERN.ottInsufficient,
  ottResultDetail: (ottId: string | number) => `/otts/recommend/${ottId}`,

  movieDetail: (movieId: string | number) => `/movies/${movieId}`,
  movieRating: (movieId: string | number) => `/movies/${movieId}/rate`,
  ratingResult: (movieId: string | number) => `/movies/${movieId}/rate/result`,
  watchConfirm: (watchRecordId: string | number) => `/watch-records/${watchRecordId}/confirm`,
  frameDetail: (frameId: string | number) => `/frames/${frameId}`,
  partyPopcorn: (partyId: string | number) => `/parties/${partyId}/popcorn`,
  partyPopcornMix: (partyId: string | number) => `/parties/${partyId}/popcorn/mix`,

  partyNew: () => ROUTE_PATTERN.partyNew,
  partySetup: () => ROUTE_PATTERN.partySetup,
  partyAnalyzing: (partyId: string | number) => `/parties/${partyId}/analyzing`,
  partyRecommendations: (partyId: string | number) => `/parties/${partyId}/recommendations`,
  partyInvitation: (invitationId: string | number) => `/parties/invitations/${invitationId}`,
  partyJoined: (partyId: string | number) => `/parties/${partyId}/joined`,
} as const;

/**
 * 하단 내비 탭이 선택 상태로 보여야 하는 경로 접두사. 공통 내비 구현에서 사용한다.
 *
 * **내 화면만 넣는다.** 그 탭에서 출발해 머무는 동안 탭이 선택된 채로 보여야 하는
 * 경로다. 다른 사람의 화면은 어느 탭에도 속하지 않는다.
 *
 * 그래서 `my`에 `publicProfile`(7.2-B)을 **넣지 않는다.** 5.8 파티원 카드에서
 * 들어오는 남의 프로필이라 MY가 아니라 파티 쪽 흐름이고, 파티에 넣지도 않았다 —
 * 5.8 자체가 전체화면이라 그 아래는 탭 흐름 밖이다.
 * (업무 -305 코드리뷰 지적. 의도적 누락이라 여기 적어 둔다)
 */
export const TAB_MATCH = {
  /*
   * 왼쪽 탭. 5.1 파티 목록에서 출발하는 흐름이다.
   */
  party: [ROUTE_PATTERN.party],
  /*
   * 가운데 탭이자 **앱의 시작 화면**이다(사용자 결정 2026-09-16, 업무 -461).
   *
   * - `recommendations`(2.2) — 추천은 필름을 늘리는 행위라 이 흐름에 속한다.
   *   상시 진입점은 3.1의 `필름 늘리기` 하나다
   * - `search`(4.1) — 검색은 탭이 아니라 **필름에서 들어가는 하위 화면**이다.
   *   진입점은 3.1 헤더의 돋보기 하나이고, 검색에 있는 동안에도 `필름`이 선택된다
   */
  film: [
    ROUTE_PATTERN.film,
    ROUTE_PATTERN.popcornBucket,
    ROUTE_PATTERN.recommendations,
    ROUTE_PATTERN.search,
  ],
  my: [
    ROUTE_PATTERN.my,
    ROUTE_PATTERN.ottManage,
    ROUTE_PATTERN.profileEdit,
    ROUTE_PATTERN.profilePreview,
  ],
} as const;
