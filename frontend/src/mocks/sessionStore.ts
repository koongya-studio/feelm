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
 * 최근 검색어도 여기 있다. 사용자가 쌓고 지우는 값이라 저장소 안에 있어야 변화가 남는다.
 * **빈 목록으로 시작한다**(업무 -641). 한때 와이어프레임 4.1이 그린 세 개(`케이퍼 무비` ·
 * `크리스토퍼 놀란` · `SF 명작`)로 시작했는데, 검색한 적 없는 말이 `최근 검색어`로 보이는
 * 것은 화면이 거짓을 말하는 것이다. 와이어프레임의 세 개는 목록이 찼을 때의 모양을 그린
 * 것이지 초기값이 아니다.
 *
 * 실제 API가 된 값은 여기서 빠진다. 차단 목록(API-020 ~ API-022)이 그렇게 나갔고, 알림
 * 설정과 공개 범위는 계약에 없는 값을 저장된 것처럼 두지 않기로 해 빠졌다(업무 -610).
 * 인앱 알림 읽음도 API-070~074가 서버에 저장하므로 빠졌다(업무 -537). 감상 기록(API-033 ~
 * API-035)도 서버로 옮겨 가 이 파일에서 사라졌다(업무 -477). 새 평가(API-036)도 서버가 저장하므로
 * 여기 쌓지 않는다(업무 -491). 관심없음과 평가 수정의 고친 값도 지웠다(업무 -610) — 추천이
 * 실제 API가 됐고 내 평가 목록은 프레임(API-042)으로 그린다.
 */
import type { ProfileExtrasUpdate } from '../types/unspecified';

type SessionState = {
  /**
   * API-027로 설정한 구독 OTT. `null`이면 아직 설정하지 않은 상태이며
   * 기준 목업의 구독 정보를 쓴다.
   */
  subscribedOttIds: number[] | null;
  /**
   * 4.1 · 4.2의 최근 검색어. 계약이 없다.
   *
   * 서버가 보관하는 값이 아니라 이 기기에서 무엇을 찾았는지이므로 API가 없는 것이
   * 자연스럽다. 실제 앱이라면 `localStorage`가 맞지만, 목업은 매번 같은 상태에서
   * 시작해야 하므로(이 파일 머리말) 다른 세션 상태와 같이 메모리에 둔다.
   */
  recentSearchQueries: string[];
  /**
   * 3.3 새 리포트 발급 화면을 이미 본 리포트. 2.1 `취향 리포트 보기`가 안 본 리포트면
   * 3.3을 거쳐 3.4로, 본 리포트면 3.4로 바로 간다(업무 -308).
   */
  seenReportIds: number[];
  /**
   * 7.2에서 바꾼 프로필 사진 · 소개 · 공개 항목(업무 -616). **계약에 자리가 없다**
   * (`types/unspecified.ts` 프로필 머리말). 비어 있는 키는 기준 목업 값을 쓴다는 뜻이다.
   */
  profileExtras: ProfileExtrasUpdate;
};

function emptyState(): SessionState {
  return {
    subscribedOttIds: null,
    // 검색한 적이 없으면 최근 검색어도 없다(업무 -641)
    recentSearchQueries: [],
    seenReportIds: [],
    profileExtras: {},
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

// ─────────────────────────────────────────── 프로필 사진 · 소개 · 공개 항목 (업무 -616)

export function getProfileExtras(): ProfileExtrasUpdate {
  return state.profileExtras;
}

/** 넘긴 키만 바꾼다. `avatarUrl: null`은 사진을 지운다는 뜻이라 그대로 남긴다 */
export function saveProfileExtras(update: ProfileExtrasUpdate): void {
  state.profileExtras = { ...state.profileExtras, ...update };
}

// ─────────────────────────────────────────── 리포트 (업무 -308)

export function markReportSeen(reportId: number): void {
  if (!state.seenReportIds.includes(reportId)) {
    state.seenReportIds = [...state.seenReportIds, reportId];
  }
}

export function isReportSeen(reportId: number): boolean {
  return state.seenReportIds.includes(reportId);
}
