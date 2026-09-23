/*
 * 추천 데이터 접근 함수.
 * 대응 명세는 docs/api/recommendation.md이다.
 *
 * 업무 S15P21E106-478에서 추천 이력 조회(API-048)를, S15P21E106-610에서 기본 추천 조회
 * (API-045)를 실제 호출로 바꿨다. **이 파일에 목업은 없다.**
 *
 * 2.2가 목업 카드를 그리던 동안 목업 영화 번호(101~104)가 실제 영화 상세(API-030)로
 * 흘러 들어가 **전혀 다른 영화**가 열렸다. 서버가 준 카드는 서버 영화이므로 그 문제가
 * 구조적으로 사라진다. 목업 세트 · 교체 후보 · 세션 관심없음 기록은 함께 지웠다.
 */
import { request } from './client';
import type { RecommendationHistory, RecommendationSet } from '../types/recommendation';

/**
 * 화면이 요청하는 노출 편수(API-045 `size`). 계약이 받는 값은 둘뿐이다 — 모바일 3편,
 * PC 6편(업무 S15P21E106-793).
 */
export type RecommendationSize = 3 | 6;

/** 생략하면 서버가 쓰는 값. 모바일과 하위 호환을 위한 것이라 보낼 때도 적지 않는다 */
const DEFAULT_SIZE: RecommendationSize = 3;

/**
 * 진행 중인 조회. **편수마다 따로 둔다** — 3편과 6편은 서로 다른 요청이고, 하나로 합치면
 * 폭이 넓어진 직후의 6편 요청이 앞서 나간 3편 응답을 받는다.
 */
const pendingRecommendationRequests = new Map<RecommendationSize, Promise<RecommendationSet>>();

/**
 * API-045 기본 추천 조회 · GET /api/v1/recommendations
 *
 * `size`는 화면이 정한다 — 모바일은 3편(취향 맞춤 2 + 취향 발견 1), PC는 6편(맞춤 3 +
 * 발견 3)이다. 기본값이 3이라 3편은 값을 싣지 않는다. 취향 발견 후보가 모자라면 그 자리를
 * 맞춤이 채우므로(업무 -793) 유형별 수는 요청한 대로 오지 않을 수 있고, 후보가 없으면
 * 요청한 편수보다 적게 온다(처리 규칙).
 * 조회 자체가 노출을 기록하는 쓰기 트랜잭션이라 화면이 다시 부르는 것은 사용자가 재시도를
 * 눌렀을 때뿐이다. 추천 서비스가 잠시 닫혀 있으면 `503 RECOMMENDATION_UNAVAILABLE`이고
 * 2.2-A가 받는다.
 *
 * 일치도 `%`(와이어프레임의 `일치 92%`)는 계약에 없어 그리지 않는다(CR-13). 서버 값이
 * 없는 수치를 화면이 지어내지 않는다.
 *
 * 관심없음(API-047)은 카드 오른쪽 위 버튼이 부른다(업무 -672, `dismissMovie`).
 */
export function getRecommendations(
  size: RecommendationSize = DEFAULT_SIZE,
): Promise<RecommendationSet> {
  /*
   * API-045는 GET이지만 선택한 카드의 노출 시각을 기록한다. React StrictMode가 개발에서
   * effect를 다시 실행하거나 사용자가 빠르게 같은 화면에 진입해도 진행 중인 요청은 한 번만
   * 보낸다. 요청이 끝난 뒤에는 캐시하지 않아 다음 진입·재시도가 최신 generation을 확인한다
   * (S15P21E106-613).
   */
  const pending = pendingRecommendationRequests.get(size);

  if (pending) return pending;

  const current = request<RecommendationSet>('/recommendations', {
    query: size === DEFAULT_SIZE ? undefined : { size },
  });

  pendingRecommendationRequests.set(size, current);

  const clear = () => {
    if (pendingRecommendationRequests.get(size) === current) {
      pendingRecommendationRequests.delete(size);
    }
  };

  void current.then(clear, clear);

  return current;
}

/**
 * API-047 관심없음 표시 · POST /api/v1/movies/{movieId}/dismiss
 *
 * 관심없음 영화는 이후 모든 개인 추천에서 빠진다. 추천 카드에서 부를 때만
 * `recommendationItemId`를 싣는다(선택값). 응답은 `{ movieId }`뿐이고 빈 자리는 다음
 * API-045 조회가 보충한다(처리 규칙) — 화면이 다시 부른다. 이미 관심없음인 영화는
 * `409 ALREADY_DISMISSED`다.
 */
export async function dismissMovie(movieId: number, recommendationItemId?: number): Promise<void> {
  await request<{ movieId: number }>(`/movies/${movieId}/dismiss`, {
    method: 'POST',
    body: recommendationItemId === undefined ? {} : { recommendationItemId },
  });
}

/**
 * API-048 추천 이력 조회 · GET /api/v1/recommendations/history
 *
 * 세트 단위 cursor 목록이다. 첫 장은 커서 없이, 다음 장은 직전 응답의 `nextCursor`로
 * 부른다. `size`도 **세트 수**다(편 수가 아니다). 생략하면 서버 기본값을 쓴다.
 * `dismissed` · `watchStatus`는 서버가 현재 상태로 채워 주므로 화면이 덮어쓰지 않는다.
 */
export async function getRecommendationHistory(
  cursor?: string,
  size?: number,
): Promise<RecommendationHistory> {
  return request<RecommendationHistory>('/recommendations/history', { query: { cursor, size } });
}
