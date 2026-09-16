/*
 * 추천 데이터 접근 함수.
 * 대응 명세는 docs/api/recommendation.md이다.
 *
 * 화면은 src/mocks/를 직접 import하지 않고 이 파일만 호출한다.
 */
import { mockRecommendationHistory } from '../mocks/recommendationHistory';
import { findMockReplacement, mockRecommendationSet } from '../mocks/recommendations';
import {
  addDismissedMovie,
  findLatestWatchRecordByMovie,
  findRatingByMovie,
  getDismissedMovieIds,
} from '../mocks/sessionStore';
import { isMockError, respond, respondError } from './mockTransport';
import type { DismissResultView, RecommendationSetView } from '../types/unspecified';
import type { RecommendationHistory } from '../types/recommendation';

/**
 * API-045 기본 추천 조회 · GET /api/v1/recommendations
 *
 * `?mock=error`(또는 `?mockError=recommendations`)는 2.2-A 화면을 확인하기 위한 목업
 * 전용 입력이다. 계약의 `503 RECOMMENDATION_UNAVAILABLE`을 그대로 재현하므로 공통
 * 주입(`respond`의 도메인 인자)을 쓰지 않고 여기서 직접 확인한다. 실제 연동 시 제거한다.
 *
 * **온보딩(1.1)에서 고른 취향은 목업 추천에 반영하지 않는다**(업무 S15P21E106-442).
 * 목업 시절에는 `안 좋았어요`로 고른 영화를 빼고 `좋았어요`와 같은 팝콘 맛을 앞으로
 * 올렸지만, 온보딩이 실제 API가 되면서(업무 S15P21E106-379) 카드의 `movieId`가 서버
 * 값이라 목업 영화(301~306)와 겹치지 않아 그 계산은 아무 일도 하지 않게 되었다.
 * 되살릴 대상도 아니다 — API-045를 연동하면 서버가 만든 순서를 그대로 쓰는 것이
 * 계약이고, 클라이언트가 다시 정렬하지 않는다.
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
   * 관심없음 처리한 영화는 추천하지 않는다(API-047 처리 규칙 · 팀 결정 2026-09-08).
   * 이번 세션의 처리 내역을 반영하므로 홈으로 나갔다 돌아와도 그대로다. 자리가 빈 만큼
   * 같은 유형의 교체 후보를 채운다.
   */
  const excluded = getDismissedMovieIds();
  const items = mockRecommendationSet.items.flatMap((item) => {
    if (!excluded.includes(item.movie.movieId)) {
      return [item];
    }

    const replacement = findMockReplacement(item.type);

    return replacement && !excluded.includes(replacement.movie.movieId) ? [replacement] : [];
  });

  return respond({ ...mockRecommendationSet, items });
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

/**
 * API-048 추천 이력 조회 · GET /api/v1/recommendations/history
 *
 * 이력은 생성 시점의 스냅샷이지만 `dismissed`와 `watchStatus`는 지금 상태다. 이번 세션에
 * 2.3에서 관심없음 처리했거나 2.5에서 평가한 영화는 그 결과를 얹는다. 그래야 7.5에서
 * 영화를 눌러 평가하고 돌아왔을 때 상태가 맞는다.
 *
 * `cursor` · `size`는 구현하지 않았다 — 목업 세트가 네 개뿐이라 한 페이지로 끝난다.
 *
 * 날짜 옆 OTT는 뺐다(업무 -459). API-048 `movie`에 OTT가 없어 목업이 영화의 첫 제공처를
 * 지어내던 값이다.
 */
export async function getRecommendationHistory(): Promise<RecommendationHistory> {
  const dismissed = getDismissedMovieIds();

  const items = mockRecommendationHistory.map((set) => ({
    ...set,
    items: set.items.map((item) => {
      const { movieId } = item.movie;
      const watchStatus = findRatingByMovie(movieId)
        ? ('RATED' as const)
        : (findLatestWatchRecordByMovie(movieId)?.status ?? item.watchStatus);

      return {
        ...item,
        dismissed: item.dismissed || dismissed.includes(movieId),
        watchStatus,
      };
    }),
  }));

  return respond({ items, nextCursor: null, hasNext: false }, 'recommendations');
}

/*
 * 관심없음 해제(2.3-A `실행 취소`)에 대응하는 API가 없다. API-047 미정 사항에
 * `관심없음 해제 기능 제공 여부`가 열려 있고 CR-15로 결정을 요청해 두었다.
 * 결정 전까지 화면 상태로만 되돌리며 이 파일에 해제 함수를 만들지 않는다.
 */
