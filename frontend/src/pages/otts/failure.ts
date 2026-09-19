/*
 * OTT 추천 실패의 두 갈래 (업무 S15P21E106-564).
 *
 * API-068은 후보가 없거나 Redis를 읽지 못하면 `503 RECOMMENDATION_UNAVAILABLE`을 준다.
 * 사용자 쪽에서 보면 장애가 아니라 **아직 추천을 만들지 못한 상태**이므로 2.2-A와 같은
 * 문구로 안내한다(`RecommendationsPage`). 나머지 실패는 원인을 `failureDescription`이
 * 가른다(`components/StatusView/failure.ts`).
 *
 * EXT-02 · EXT-03 · EXT-04가 같은 응답을 읽으므로 세 화면이 이 함수를 함께 쓴다.
 */
import { ApiError } from '../../api/apiError';
import { failureDescription } from '../../components/StatusView/failure';

export type OttFailureView = {
  title: string;
  description: string;
  /** 준비 중이면 `다시 시도`가 아니라 `다시 확인하기`다 — 고칠 것이 없는 기다림이다 */
  retryLabel: string;
};

export function isPreparing(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'RECOMMENDATION_UNAVAILABLE';
}

/** `title`은 그 화면이 무엇을 못 했는지다. 준비 중일 때는 화면과 무관하게 같은 안내를 쓴다 */
export function ottFailureView(error: unknown, title: string): OttFailureView {
  if (isPreparing(error)) {
    return {
      title: '취향에 맞는 OTT 추천을 준비하고 있어요',
      description: '잠시 후 다시 확인해 주세요.\n그동안 영화를 평가하면 추천이 더 정확해져요',
      retryLabel: '다시 확인하기',
    };
  }

  return { title, description: failureDescription(error), retryLabel: '다시 시도' };
}
