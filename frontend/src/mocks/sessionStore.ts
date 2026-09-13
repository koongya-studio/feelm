/*
 * 세션 동안 유지되는 목업 상태.
 *
 * 지금까지 목업은 읽기 전용이라 관심없음 처리 · 평가 · 감상 기록이 화면을 벗어나면
 * 사라졌다. 여기에 모아 두면 화면들이 같은 상태를 보게 되어 앱처럼 동작한다
 * (업무 S15P21E106-262).
 *
 * **화면은 이 파일을 직접 import하지 않는다.** `src/api/`의 함수만 이 파일을 쓰고,
 * 화면은 지금처럼 `src/api/`만 호출한다(frontend-structure.md 7.1). 실제 연동
 * 단계에서는 `src/api/` 함수 본문에서 이 파일 호출만 걷어내면 된다.
 *
 * 저장 위치는 메모리다. 새로고침하면 초기 상태로 돌아간다(팀 결정 2026-09-08).
 * `sessionStorage`를 쓰지 않는 이유는 검증을 매번 같은 상태에서 시작하기 위해서다.
 *
 * 여기 담는 것은 **사용자가 이번 세션에 만든 변화**뿐이다. 기준 목업(mocks/의 나머지
 * 파일)은 그대로 두고, `src/api/`가 둘을 합쳐 응답을 만든다.
 *
 * 예외는 두 가지다. 최근 검색어와 7.7의 감상 기록은 와이어프레임이 그린 상태로 시작한다.
 * 둘 다 사용자가 지우거나 바꾸는 값이라 저장소 안에 있어야 변화가 남는다.
 */
import { createSeedWatchRecords } from './watch';
import type { OnboardingPreference } from '../types/onboarding';
import type {
  NotificationSettingsView,
  PartyView,
  PrivacySettingsView,
} from '../types/unspecified';
import type { BlockedUser } from '../types/user';
import type { Rating, RatingUpdateInput, WatchRecord } from '../types/watch';

/**
 * 저장소가 보관하는 감상 기록.
 *
 * API-033 응답(`WatchRecord`)에는 없지만 API-034 목록이 함께 내려주는 값
 * (`ott` · `watchedAt` · `ratingId`)을 붙인 것이다. 서버라면 한 행에 들어 있을 값이라
 * 목업도 한 곳에 모아 두고, `src/api/`가 API별 응답 모양으로 잘라 내려준다.
 */
export type StoredWatchRecord = WatchRecord & {
  /** 어느 OTT 링크를 눌렀는지 (API-033 요청 본문의 `ottId`) */
  ottId: number;
  /** 감상 확인에서 `봤어요`를 고른 시각 (API-035) */
  watchedAt: string | null;
  /** 이 기록으로 이어진 평가 (API-036) */
  ratingId: number | null;
};

type SessionState = {
  /** API-024로 저장한 초기 취향. 건너뛰면 빈 배열 */
  onboardingPreferences: OnboardingPreference[];
  onboardingSubmitted: boolean;
  /**
   * API-027로 설정한 구독 OTT. `null`이면 아직 설정하지 않은 상태이며
   * 기준 목업의 구독 정보를 쓴다.
   */
  subscribedOttIds: number[] | null;
  /** API-047로 관심없음 처리한 영화 */
  dismissedMovieIds: number[];
  /** API-033 · API-034 · API-035 감상 기록 */
  watchRecords: StoredWatchRecord[];
  /** API-036 평가. 영화당 한 번만 반영된다 */
  ratings: Rating[];
  /**
   * API-037로 고친 **기준 목업 평가**. `ratingId` → 고친 값.
   *
   * 기준 평가(mocks/frames.ts `mockMyRatings`)를 `ratings`에 넣으면 안 된다. `ratings`는
   * 이번 세션에 새로 만든 평가라서 하나마다 프레임 1개와 팝콘 1알로 셈해진다
   * (api/taste.ts). 수정은 프레임을 만들지 않으므로(API-037 처리 규칙) 따로 둔다.
   * 이번 세션에 만든 평가를 고치면 `ratings`의 그 항목을 바로 고친다.
   */
  ratingEdits: Record<number, RatingUpdateInput>;
  /**
   * 4.1 · 4.2의 최근 검색어. 계약이 없다.
   *
   * 서버가 보관하는 값이 아니라 이 기기에서 무엇을 찾았는지이므로 API가 없는 것이
   * 자연스럽다. 실제 앱이라면 `localStorage`가 맞지만, 목업은 매번 같은 상태에서
   * 시작해야 하므로(이 파일 머리말) 다른 세션 상태와 같이 메모리에 둔다.
   */
  recentSearchQueries: string[];
  /** API-049로 만든 파티. 기준 목업(`mocks/parties.ts`) 뒤에 붙는다 */
  createdParties: PartyView[];
  /** API-053 · API-054로 답한 초대. `invitationId` → 바뀐 상태 */
  invitationStatuses: Record<number, 'ACCEPTED' | 'DECLINED'>;
  /**
   * 7.2 프로필 편집이 저장한 **계약에 없는 값**. `null`이면 아직 편집하지 않았다.
   *
   * 닉네임 · 생년월일 · 성별 · 공개 범위는 여기 없다. API-015가 실제로 저장하므로
   * 로컬에 둘 이유가 없다(`api/users.ts`). 한 줄 소개만 계약에 자리가 없어 남는다.
   */
  profileEdit: { introduction: string } | null;
  /** 7.8-C에서 바꾼 알림 설정. `null`이면 기준 목업(mocks/settings.ts)이다 */
  notificationSettings: NotificationSettingsView | null;
  /** 7.8-B에서 바꾼 공개 범위. `null`이면 기준 목업이다 */
  privacySettings: PrivacySettingsView | null;
  /**
   * API-020 · API-021로 바뀐 차단 목록 **전체**. `null`이면 기준 목업이다.
   *
   * 기준 목업의 사람을 해제할 수 있어야 하므로 추가분만 따로 두지 않고 처음 바뀔 때
   * 목록 전체를 옮겨 온다.
   */
  blockedUsers: BlockedUser[] | null;
};

function emptyState(): SessionState {
  return {
    onboardingPreferences: [],
    onboardingSubmitted: false,
    subscribedOttIds: null,
    dismissedMovieIds: [],
    // 7.7이 그리는 `평가 안 남긴 영화`로 시작한다. 이유는 createSeedWatchRecords 주석에 있다
    watchRecords: createSeedWatchRecords(),
    ratings: [],
    ratingEdits: {},
    // 와이어프레임 4.1이 최근 검색어 세 개가 있는 상태를 그리므로 그 값으로 시작한다
    recentSearchQueries: ['케이퍼 무비', '크리스토퍼 놀란', 'SF 명작'],
    createdParties: [],
    invitationStatuses: {},
    profileEdit: null,
    notificationSettings: null,
    privacySettings: null,
    blockedUsers: null,
  };
}

/**
 * 최근 검색어 보관 개수.
 *
 * `[?]` 와이어프레임 4.1은 세 개를 그렸지만 상한인지 그때의 개수인지 알 수 없다.
 * 한 줄에 들어가는 만큼으로 잡았다.
 */
const MAX_RECENT_SEARCH_QUERIES = 10;

let state: SessionState = emptyState();

/** 검증과 개발 확인용. 화면에서는 쓰지 않는다 */
export function resetSessionStore(): void {
  state = emptyState();
}

// ─────────────────────────────────────────── 온보딩

export function saveOnboardingPreferences(preferences: OnboardingPreference[]): void {
  state.onboardingPreferences = preferences;
  state.onboardingSubmitted = true;
}

export function getOnboardingPreferences(): OnboardingPreference[] {
  return state.onboardingPreferences;
}

export function isOnboardingSubmitted(): boolean {
  return state.onboardingSubmitted;
}

// ─────────────────────────────────────────── 구독 OTT

export function setSubscribedOttIds(ottIds: number[]): void {
  state.subscribedOttIds = [...ottIds];
}

/** 아직 설정하지 않았으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getSubscribedOttIds(): number[] | null {
  return state.subscribedOttIds;
}

// ─────────────────────────────────────────── 최근 검색어

/** 최신이 앞이다. 같은 검색어를 다시 찾으면 중복을 만들지 않고 앞으로 옮긴다 */
export function addRecentSearchQuery(query: string): void {
  const trimmed = query.trim();

  if (!trimmed) return;

  const rest = state.recentSearchQueries.filter((item) => item !== trimmed);
  state.recentSearchQueries = [trimmed, ...rest].slice(0, MAX_RECENT_SEARCH_QUERIES);
}

export function getRecentSearchQueries(): string[] {
  return state.recentSearchQueries;
}

export function removeRecentSearchQuery(query: string): void {
  state.recentSearchQueries = state.recentSearchQueries.filter((item) => item !== query);
}

export function clearRecentSearchQueries(): void {
  state.recentSearchQueries = [];
}

// ─────────────────────────────────────────── 파티

export function addCreatedParty(party: PartyView): void {
  state.createdParties = [party, ...state.createdParties];
}

export function getCreatedParties(): PartyView[] {
  return state.createdParties;
}

/**
 * 다음에 만들 파티의 id.
 *
 * 서버가 정하는 값이라 목업이 흉내 낼 뿐이다. 기준 목업이 9001을 쓰고 초대 목업이
 * 9002를 쓰므로 9100부터 센다.
 */
export function nextPartyId(): number {
  return 9100 + state.createdParties.length;
}

export function setInvitationStatus(invitationId: number, status: 'ACCEPTED' | 'DECLINED'): void {
  state.invitationStatuses = { ...state.invitationStatuses, [invitationId]: status };
}

export function getInvitationStatus(invitationId: number): 'ACCEPTED' | 'DECLINED' | null {
  return state.invitationStatuses[invitationId] ?? null;
}

// ─────────────────────────────────────────── 프로필 편집

export function saveProfileEdit(update: { introduction: string }): void {
  state.profileEdit = { ...update };
}

/** 아직 편집하지 않았으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getProfileEdit(): { introduction: string } | null {
  return state.profileEdit;
}

// ─────────────────────────────────────────── 설정 (업무 -307)

export function saveNotificationSettings(settings: NotificationSettingsView): void {
  state.notificationSettings = { ...settings };
}

/** 바꾼 적 없으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getNotificationSettings(): NotificationSettingsView | null {
  return state.notificationSettings;
}

export function savePrivacySettings(settings: PrivacySettingsView): void {
  state.privacySettings = { ...settings };
}

/** 바꾼 적 없으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getPrivacySettings(): PrivacySettingsView | null {
  return state.privacySettings;
}

export function saveBlockedUsers(users: BlockedUser[]): void {
  state.blockedUsers = [...users];
}

/** 바꾼 적 없으면 null. 기준 목업을 쓰라는 뜻이다 */
export function getBlockedUsers(): BlockedUser[] | null {
  return state.blockedUsers;
}

// ─────────────────────────────────────────── 관심없음

export function addDismissedMovie(movieId: number): void {
  if (!state.dismissedMovieIds.includes(movieId)) {
    state.dismissedMovieIds.push(movieId);
  }
}

export function getDismissedMovieIds(): number[] {
  return state.dismissedMovieIds;
}

export function isDismissed(movieId: number): boolean {
  return state.dismissedMovieIds.includes(movieId);
}

// ─────────────────────────────────────────── 감상 기록

export function addWatchRecord(record: StoredWatchRecord): void {
  state.watchRecords.push(record);
}

/** API-034가 목록으로 내려줄 원본. 정렬은 `src/api/`가 한다 */
export function getWatchRecords(): StoredWatchRecord[] {
  return state.watchRecords;
}

export function findWatchRecord(watchRecordId: number): StoredWatchRecord | null {
  return state.watchRecords.find((record) => record.watchRecordId === watchRecordId) ?? null;
}

export function updateWatchRecord(watchRecordId: number, patch: Partial<StoredWatchRecord>): void {
  const index = state.watchRecords.findIndex((record) => record.watchRecordId === watchRecordId);

  if (index !== -1) {
    state.watchRecords[index] = { ...state.watchRecords[index], ...patch };
  }
}

/** 영화의 가장 최근 감상 기록. 2.4가 감상 상태를 표시할 때 쓴다 */
export function findLatestWatchRecordByMovie(movieId: number): StoredWatchRecord | null {
  return [...state.watchRecords].reverse().find((record) => record.movieId === movieId) ?? null;
}

// ─────────────────────────────────────────── 평가

/**
 * 평가는 영화당 한 번만 반영된다(2.5 안내 문구). 같은 영화를 다시 평가하면 덮어쓴다.
 * 이미 평가한 영화는 2.5가 수정(API-037, `updateSessionRating`)으로 보내므로 덮어쓰는
 * 경우는 실제 흐름에 없다(업무 -306).
 */
export function saveRating(rating: Rating): void {
  const index = state.ratings.findIndex((saved) => saved.movieId === rating.movieId);

  if (index === -1) {
    state.ratings.push(rating);
  } else {
    state.ratings[index] = rating;
  }
}

export function getRatings(): Rating[] {
  return state.ratings;
}

export function findRatingByMovie(movieId: number): Rating | null {
  return state.ratings.find((rating) => rating.movieId === movieId) ?? null;
}

export function findRatingByFrame(frameId: number): Rating | null {
  return state.ratings.find((rating) => rating.frame.frameId === frameId) ?? null;
}

/**
 * 평가 수정(API-037). 이번 세션에 만든 평가면 그 항목을 고치고 true를 돌려준다.
 * 기준 목업 평가라 여기 없으면 false다 — 그때는 `saveRatingEdit`을 쓴다.
 */
export function updateSessionRating(ratingId: number, input: RatingUpdateInput): boolean {
  const index = state.ratings.findIndex((rating) => rating.ratingId === ratingId);

  if (index === -1) {
    return false;
  }

  state.ratings[index] = { ...state.ratings[index], ...input };

  return true;
}

export function saveRatingEdit(ratingId: number, input: RatingUpdateInput): void {
  state.ratingEdits = { ...state.ratingEdits, [ratingId]: { ...input } };
}

/**
 * 기준 목업 평가에 고친 값을 얹는다. 고친 적 없으면 그대로 돌려준다.
 *
 * **기준 평가를 읽는 곳은 전부 이 함수를 거친다.** 영화 상세(2.4 · 2.5) · 프레임
 * 목록과 상세(3.1 · 3.1-A) · 내 평가 목록(7.6)이다. 한 곳이라도 빠지면 고친 별점이
 * 그 화면에서만 옛 값으로 보인다.
 */
export function withRatingEdit<T extends RatingUpdateInput & { ratingId: number }>(rating: T): T {
  const edit = state.ratingEdits[rating.ratingId];

  return edit ? { ...rating, ...edit } : rating;
}
