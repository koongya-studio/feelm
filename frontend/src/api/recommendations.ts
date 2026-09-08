/*
 * 추천 데이터 접근 함수.
 * 대응 명세는 docs/api/recommendation.md이다.
 *
 * 화면은 src/mocks/를 직접 import하지 않고 이 파일만 호출한다.
 */
import { findMockReplacement, mockRecommendationSet } from '../mocks/recommendations';
import { addDismissedMovie, getDismissedMovieIds } from '../mocks/sessionStore';
import { respond, respondError } from './mockTransport';
import type { MockScenario } from './mockTransport';
import type { DismissResultView, RecommendationSetView } from '../types/unspecified';

/**
 * API-045 기본 추천 조회 · GET /api/v1/recommendations
 *
 * `scenario: 'error'`는 2.2-A 화면을 확인하기 위한 목업 전용 입력이다. 계약의
 * `503 RECOMMENDATION_UNAVAILABLE`을 그대로 재현한다. 실제 연동 시 제거한다.
 */
export async function getRecommendations(
  scenario: MockScenario = 'success',
): Promise<RecommendationSetView> {
  if (scenario === 'error') {
    return respondError(
      503,
      'RECOMMENDATION_UNAVAILABLE',
      '추천 서비스를 일시적으로 사용할 수 없습니다.',
    );
  }

  /*
   * 관심없음 처리한 영화는 다시 추천하지 않는다(API-047 처리 규칙). 이번 세션의
   * 처리 내역을 반영하므로 홈으로 나갔다 돌아와도 그대로다.
   * 자리가 빈 만큼 같은 유형의 교체 후보를 채운다.
   */
  const dismissed = getDismissedMovieIds();
  const items = mockRecommendationSet.items.flatMap((item) => {
    if (!dismissed.includes(item.movie.movieId)) {
      return [item];
    }

    const replacement = findMockReplacement(item.type);

    return replacement && !dismissed.includes(replacement.movie.movieId) ? [replacement] : [];
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

  return respond({
    movieId,
    replacement: item ? findMockReplacement(item.type) : null,
  });
}

/*
 * 관심없음 해제(2.3-A `실행 취소`)에 대응하는 API가 없다. API-047 미정 사항에
 * `관심없음 해제 기능 제공 여부`가 열려 있고 CR-15로 결정을 요청해 두었다.
 * 결정 전까지 화면 상태로만 되돌리며 이 파일에 해제 함수를 만들지 않는다.
 */
