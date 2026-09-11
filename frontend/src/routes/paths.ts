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
  // 인증 (하단 내비 없음). 0.1 ~ 0.7-A
  authStart: '/start',
  signupMethod: '/signup',
  signupSocial: '/signup/social',
  signupEmail: '/signup/email',
  signupComplete: '/signup/complete',
  login: '/login',
  accountFind: '/account/find',
  passwordReset: '/account/reset',
  // 0.8. 인증 메일 링크가 도착하는 곳이라 백엔드 설정값과 같아야 한다(S15P21E106-367)
  verifyEmail: '/verify-email',

  // 온보딩 (하단 내비 없음)
  onboardingTaste: '/onboarding/taste',
  onboardingOtt: '/onboarding/ott',
  onboardingComplete: '/onboarding/complete',

  // 탭 화면 (하단 내비 표시)
  home: '/',
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
  tasteCompare: '/users/:userId/compare',

  /*
   * 7.1 메뉴가 가리키는 화면 중 아직 만들지 않은 것(7.3 · 7.5 · 7.6 · 7.8).
   * 306~308 몫이며 지금은 `NotInScopePage`로 간다.
   *
   * 경로를 미리 두는 이유는 **7.1 → 각 화면 이동 자체가 검증 대상**이기 때문이다
   * (screen-spec-my.md 2장 13번). 경로가 없으면 `*`에 걸려 404로 떨어져서
   * `아직 안 만든 화면`과 `잘못된 주소`가 구분되지 않는다.
   */
  tasteReports: '/my/reports',
  recommendationHistory: '/my/recommendations',
  myRatings: '/my/ratings',
  settings: '/my/settings',

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
  signupEmail: () => ROUTE_PATTERN.signupEmail,
  signupComplete: () => ROUTE_PATTERN.signupComplete,
  login: () => ROUTE_PATTERN.login,
  accountFind: () => ROUTE_PATTERN.accountFind,
  passwordReset: () => ROUTE_PATTERN.passwordReset,
  verifyEmail: () => ROUTE_PATTERN.verifyEmail,

  onboardingTaste: () => ROUTE_PATTERN.onboardingTaste,
  onboardingOtt: () => ROUTE_PATTERN.onboardingOtt,
  onboardingComplete: () => ROUTE_PATTERN.onboardingComplete,

  home: () => ROUTE_PATTERN.home,
  recommendations: () => ROUTE_PATTERN.recommendations,
  film: () => ROUTE_PATTERN.film,
  popcornBucket: () => ROUTE_PATTERN.popcornBucket,
  search: () => ROUTE_PATTERN.search,
  party: () => ROUTE_PATTERN.party,
  my: () => ROUTE_PATTERN.my,
  ottManage: () => ROUTE_PATTERN.ottManage,

  profileEdit: () => ROUTE_PATTERN.profileEdit,
  profilePreview: () => ROUTE_PATTERN.profilePreview,
  publicProfile: (userId: string | number) => `/users/${userId}`,
  tasteCompare: (userId: string | number) => `/users/${userId}/compare`,

  tasteReports: () => ROUTE_PATTERN.tasteReports,
  recommendationHistory: () => ROUTE_PATTERN.recommendationHistory,
  myRatings: () => ROUTE_PATTERN.myRatings,
  settings: () => ROUTE_PATTERN.settings,

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
 * 그래서 `my`에 `publicProfile`(7.2-B)과 `tasteCompare`(7.2-C)는 **넣지 않는다.**
 * 둘은 5.8 파티원 카드에서 들어오는 남의 프로필이라 MY가 아니라 파티 쪽 흐름이고,
 * 파티에 넣지도 않았다 — 5.8 자체가 전체화면이라 그 아래는 탭 흐름 밖이다.
 * (업무 -305 코드리뷰 지적. 의도적 누락이라 여기 적어 둔다)
 */
export const TAB_MATCH = {
  home: [ROUTE_PATTERN.home, ROUTE_PATTERN.recommendations],
  search: [ROUTE_PATTERN.search],
  film: [ROUTE_PATTERN.film, ROUTE_PATTERN.popcornBucket],
  party: [ROUTE_PATTERN.party],
  my: [
    ROUTE_PATTERN.my,
    ROUTE_PATTERN.ottManage,
    ROUTE_PATTERN.profileEdit,
    ROUTE_PATTERN.profilePreview,
  ],
} as const;
