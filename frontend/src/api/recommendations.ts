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

let pendingRecommendationRequest: Promise<RecommendationSet> | null = null;

/**
 * API-045 기본 추천 조회 · GET /api/v1/recommendations
 *
 * 취향 맞춤 2편 + 취향 발견 1편이 기본이고, 후보가 없으면 그보다 적게 온다(처리 규칙).
 * 조회 자체가 노출을 기록하는 쓰기 트랜잭션이라 화면이 다시 부르는 것은 사용자가 재시도를
 * 눌렀을 때뿐이다. 추천 서비스가 잠시 닫혀 있으면 `503 RECOMMENDATION_UNAVAILABLE`이고
 * 2.2-A가 받는다.
 *
 * 일치도 `%`(와이어프레임의 `일치 92%`)는 계약에 없어 그리지 않는다(CR-13). 서버 값이
 * 없는 수치를 화면이 지어내지 않는다.
 *
 * 관심없음(API-047)은 백엔드에 있지만 프론트 연동은 S15P21E106-536이 맡는다. 그전까지
 * 2.3 메뉴의 `관심없음`은 눌리지 않는다(`RecommendationsPage`).
 */
export function getRecommendations(): Promise<RecommendationSet> {
  /*
   * API-045는 GET이지만 선택한 카드의 노출 시각을 기록한다. React StrictMode가 개발에서
   * effect를 다시 실행하거나 사용자가 빠르게 같은 화면에 진입해도 진행 중인 요청은 한 번만
   * 보낸다. 요청이 끝난 뒤에는 캐시하지 않아 다음 진입·재시도가 최신 generation을 확인한다
   * (S15P21E106-613).
   */
  if (!pendingRecommendationRequest) {
    const current = request<RecommendationSet>('/recommendations');
    pendingRecommendationRequest = current;

    const clear = () => {
      if (pendingRecommendationRequest === current) {
        pendingRecommendationRequest = null;
      }
    };

    void current.then(clear, clear);
  }

  return pendingRecommendationRequest;
}

/**
 * API-048 추천 이력 조회 · GET /api/v1/recommendations/history
 *
 * 세트 단위 cursor 목록이다. 첫 장은 커서 없이, 다음 장은 직전 응답의 `nextCursor`로
 * 부른다. `dismissed` · `watchStatus`는 서버가 현재 상태로 채워 주므로 화면이 덮어쓰지
 * 않는다.
 */
export async function getRecommendationHistory(cursor?: string): Promise<RecommendationHistory> {
  return request<RecommendationHistory>('/recommendations/history', { query: { cursor } });
}
