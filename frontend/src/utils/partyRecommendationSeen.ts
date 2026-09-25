/*
 * 파티 추천 묶음을 본 적이 있는지 (업무 S15P21E106-855)
 *
 * 5.9 모으기 · 섞기 장면은 **그 파티원이 아직 못 본 묶음**일 때만 보여 준다. 파티원 A가 이미
 * 본 묶음이면 A에게는 장면 없이 5.5를 열고, 같은 묶음을 처음 보는 B에게는 장면을 보여 준다.
 *
 * 계약에는 파티원별로 무엇을 봤는지 남기는 자리가 없다. API-083 이력과 노출 커서는 파티 단위라
 * 누가 봤는지 가리지 못한다. 그래서 **이 기기에 계정별로** 남긴다. 다른 기기에서 처음 열면 한 번
 * 더 장면을 보는데, 모르면 보여 주는 쪽이 안전하다.
 *
 * 기준은 세트가 아니라 **지금 묶음**이다(세트 번호 + 영화 번호). 다른 파티원이 `다른 영화
 * 추천받기`로 다음 묶음을 받으면 세트는 같아도 영화가 바뀌고, 나는 그 영화를 본 적이 없다.
 * 파티마다 마지막으로 본 묶음 하나만 남긴다 — 비교할 것은 지금 묶음뿐이다.
 */
import type { PartyRecommendationSet } from '../types/party';

function key(userId: number, partyId: number) {
  return `feelm:party-recommendation-seen:${userId}:${partyId}`;
}

function bundleOf(set: PartyRecommendationSet): string {
  const movies = set.items.map((item) => item.movie.movieId).sort((a, b) => a - b);

  return `${set.recommendationSetId}:${movies.join(',')}`;
}

export function hasSeenPartyRecommendation(
  userId: number,
  partyId: number,
  set: PartyRecommendationSet,
): boolean {
  if (set.items.length === 0) return false;

  try {
    return localStorage.getItem(key(userId, partyId)) === bundleOf(set);
  } catch {
    return false;
  }
}

/** 5.5가 묶음을 그렸을 때 부른다 */
export function markPartyRecommendationSeen(
  userId: number,
  partyId: number,
  set: PartyRecommendationSet,
): void {
  if (set.items.length === 0) return;

  try {
    localStorage.setItem(key(userId, partyId), bundleOf(set));
  } catch {
    // 저장소가 막히면 다음에 한 번 더 섞는 장면을 볼 뿐이다
  }
}
