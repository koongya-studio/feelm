/*
 * 추천 데이터 접근 함수.
 * 대응 명세는 docs/api/recommendation.md이다.
 *
 * 화면은 src/mocks/를 직접 import하지 않고 이 파일만 호출한다.
 */
import { mockOnboardingCards } from '../mocks/onboarding';
import { findMockReplacement, mockRecommendationSet } from '../mocks/recommendations';
import {
  addDismissedMovie,
  getDismissedMovieIds,
  getOnboardingPreferences,
} from '../mocks/sessionStore';
import { isMockError, respond, respondError } from './mockTransport';
import type { DismissResultView, RecommendationSetView } from '../types/unspecified';

/**
 * 온보딩(1.1)에서 고른 취향을 추천에 반영하는 규칙 (팀 결정 2026-09-08).
 *
 * - `안 좋았어요`(DISLIKE)로 고른 영화는 추천하지 않는다
 * - `좋았어요`(LIKE)로 고른 영화와 같은 팝콘 맛의 추천을 앞에 둔다
 *
 * 계약에는 이 규칙이 없다. API-045는 서버가 만든 순서를 그대로 내려주고 클라이언트가
 * 다시 정렬하지 않는 것이 맞다. 여기서 계산하는 것은 **목업이 서버 역할을 대신하기
 * 때문**이며, 실제 연동 시에는 이 함수에서 지운다.
 */
function onboardingTaste() {
  const preferences = getOnboardingPreferences();
  const flavorOf = (movieId: number) =>
    mockOnboardingCards.find((card) => card.movieId === movieId)?.flavorCode;

  return {
    dislikedMovieIds: preferences
      .filter((preference) => preference.preference === 'DISLIKE')
      .map((preference) => preference.movieId),
    likedFlavorCodes: preferences
      .filter((preference) => preference.preference === 'LIKE')
      .map((preference) => flavorOf(preference.movieId))
      .filter((code): code is string => code !== undefined),
  };
}

/**
 * API-045 기본 추천 조회 · GET /api/v1/recommendations
 *
 * `?mock=error`(또는 `?mockError=recommendations`)는 2.2-A 화면을 확인하기 위한 목업
 * 전용 입력이다. 계약의 `503 RECOMMENDATION_UNAVAILABLE`을 그대로 재현하므로 공통
 * 주입(`respond`의 도메인 인자)을 쓰지 않고 여기서 직접 확인한다. 실제 연동 시 제거한다.
 */
export async function getRecommendations(): Promise<RecommendationSetView> {
  if (isMockError('recommendations')) {
    return respondError(
      503,
      'RECOMMENDATION_UNAVAILABLE',
      '추천 서비스를 일시적으로 사용할 수 없습니다.',
    );
  }

  /*
   * 관심없음 처리한 영화와 온보딩에서 `안 좋았어요`로 고른 영화는 추천하지 않는다
   * (API-047 처리 규칙 · 팀 결정 2026-09-08). 이번 세션의 처리 내역을 반영하므로
   * 홈으로 나갔다 돌아와도 그대로다. 자리가 빈 만큼 같은 유형의 교체 후보를 채운다.
   */
  const { dislikedMovieIds, likedFlavorCodes } = onboardingTaste();
  const excluded = [...getDismissedMovieIds(), ...dislikedMovieIds];
  const items = mockRecommendationSet.items.flatMap((item) => {
    if (!excluded.includes(item.movie.movieId)) {
      return [item];
    }

    const replacement = findMockReplacement(item.type);

    return replacement && !excluded.includes(replacement.movie.movieId) ? [replacement] : [];
  });

  /*
   * `좋았어요`로 고른 맛과 같은 추천을 앞으로 올린다. 같은 묶음 안의 순서는 그대로다
   * (Array.prototype.sort는 안정 정렬이다).
   */
  const sorted = [...items].sort(
    (a, b) =>
      Number(likedFlavorCodes.includes(b.flavorCode)) -
      Number(likedFlavorCodes.includes(a.flavorCode)),
  );

  return respond({ ...mockRecommendationSet, items: sorted });
}

/**
 * API-047 관심없음 표시 · POST /api/v1/movies/{movieId}/dismiss
 *
 * `recommendationItemId`는 추천 카드에서 호출할 때만 보내는 선택값이다. 같은 유형의
 * 다음 후보 1편으로 교체하며, 후보가 없으면 `replacement: null`을 반환한다.
 */
export async function dismissMovie(
  movieId: number,
  recommendationItemId?: number,
): Promise<DismissResultView> {
  const item = mockRecommendationSet.items.find(
    (candidate) => candidate.recommendationItemId === recommendationItemId,
  );

  addDismissedMovie(movieId);

  /*
   * 이미 관심없음 처리한 영화를 교체 후보로 다시 올리지 않는다. `getRecommendations`와
   * 같은 규칙이다. 교체된 카드를 또 관심없음으로 넘기면 같은 영화가 되돌아오는 문제가
   * 있었다(업무 -264 검증에서 확인).
   */
  const candidate = item ? findMockReplacement(item.type) : null;
  const dismissed = getDismissedMovieIds();

  return respond({
    movieId,
    replacement: candidate && !dismissed.includes(candidate.movie.movieId) ? candidate : null,
  });
}

/*
 * 관심없음 해제(2.3-A `실행 취소`)에 대응하는 API가 없다. API-047 미정 사항에
 * `관심없음 해제 기능 제공 여부`가 열려 있고 CR-15로 결정을 요청해 두었다.
 * 결정 전까지 화면 상태로만 되돌리며 이 파일에 해제 함수를 만들지 않는다.
 */
