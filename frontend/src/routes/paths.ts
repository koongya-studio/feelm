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

/** 하단 내비 탭이 선택 상태로 보여야 하는 경로 접두사. 공통 내비 구현에서 사용한다. */
export const TAB_MATCH = {
  home: [ROUTE_PATTERN.home, ROUTE_PATTERN.recommendations],
  search: [ROUTE_PATTERN.search],
  film: [ROUTE_PATTERN.film, ROUTE_PATTERN.popcornBucket],
  party: [ROUTE_PATTERN.party],
  my: [ROUTE_PATTERN.my, ROUTE_PATTERN.ottManage],
} as const;
