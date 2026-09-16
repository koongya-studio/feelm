/*
 * 추천 데이터 접근 함수.
 * 대응 명세는 docs/api/recommendation.md이다.
 *
 * 화면은 src/mocks/를 직접 import하지 않고 이 파일만 호출한다.
 *
 * 업무 S15P21E106-478에서 추천 이력 조회(API-048)를 실제 호출로 바꿨다. 같은 파일의
 * 기본 추천 조회(API-045)와 관심없음(API-047)은 목업으로 남는다 — 아래 각 함수에 이유를
 * 적었다.
 */
import { findMockReplacement, mockRecommendationSet } from '../mocks/recommendations';
import { addDismissedMovie, getDismissedMovieIds } from '../mocks/sessionStore';
import { request } from './client';
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
 * 세트를 최신순으로 준다. 한 장은 **세트 단위**이며 세트 안의 영화는 추천받은 순서다.
 * `size`를 보내지 않으면 20세트이고(계약 기본값), 그보다 오래된 이력은 `nextCursor`로
 * 이어 받는다. `cursor`는 **직전 응답의 `nextCursor`를 그대로** 되돌려 줄 때만 쓴다.
 *
 * `dismissed`와 `watchStatus`는 **스냅샷이 아니라 지금 상태**다(계약 처리 규칙). 서버가
 * 관심없음 기록과 최신 감상 기록을 함께 읽어 채우므로 클라이언트가 덧칠하지 않는다.
 * 목업 시절에는 세션 저장소의 관심없음 · 평가를 여기서 얹었는데, 둘 다 실제 DB에 없는
 * 목업 영화(2.2 추천 카드)의 것이라 서버 이력과 맞춰 볼 곳이 없다.
 *
 * `[!]` **아직 이력이 쌓이지 않는다.** 이력은 API-045가 추천 세트를 만들 때 생기는데
 * 백엔드에 세트 생성(Candidate v4 소비)이 없어 `recommendation_sets`에 쓰는 곳이 없다.
 * MY의 `추천 기록` 탭은 계약대로 빈 목록을 보여준다(업무 S15P21E106-164 · -194 대기).
 *
 * `[!]` **파티 추천 세트는 오지 않는다.** 계약은 세트의 `user_id` · `party_id`로 개인 ·
 * 파티를 구분한다고 적지만 구현은 개인 세트만 조회한다. 화면의 `파티 추천` 출처 표시는
 * 계약에 남은 값이라 지우지 않았고, 실제로는 나타나지 않는다(업무 S15P21E106-501).
 */
export async function getRecommendationHistory(cursor?: string): Promise<RecommendationHistory> {
  return request<RecommendationHistory>('/recommendations/history', { query: { cursor } });
}

/*
 * 관심없음 해제(2.3-A `실행 취소`)에 대응하는 API가 없다. API-047 미정 사항에
 * `관심없음 해제 기능 제공 여부`가 열려 있고 CR-15로 결정을 요청해 두었다.
 * 결정 전까지 화면 상태로만 되돌리며 이 파일에 해제 함수를 만들지 않는다.
 */
