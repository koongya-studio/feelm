/*
 * 팝콘 버킷(API-041)의 맛 분포를 통에 담을 형태로 옮긴다.
 *
 * 3.2 취향 팝콘이 쓰던 것을 7.1 MY의 `취향 팝콘` 바로가기가 같이 쓰면서 컴포넌트 옆으로
 * 옮겼다(업무 -459).
 *
 * **알 목록이 아니라 맛별 알 수를 넘긴다**(업무 -577). 전에는 한 알이 영화 한 편이라
 * 맛 코드를 개수만큼 펼친 배열을 넘겼는데, 통이 100알을 30알로 요약해 그리게 되면서
 * 펼칠 이유가 없어졌다. 요약은 `PopcornBucket`이 한다.
 */
/** 한 맛과 그 알 수. 통은 이것만 알면 더미를 그린다 */
export type FlavorCount = {
  code: string;
  count: number;
};

/**
 * 맛별 집계를 통이 읽는 형태로 옮긴다.
 *
 * 받는 모양을 계약 타입이 아니라 필요한 두 값으로 적는다. API-041의
 * `PopcornBucketFlavor`와 API-057의 `PartyMemberFlavor`가 둘 다 들어오는데, 후자에는
 * 표시명과 색이 없다.
 */
export function kernelCounts(
  flavors: { flavorCode: string; popcornCount: number }[],
): FlavorCount[] {
  return flavors.map((flavor) => ({ code: flavor.flavorCode, count: flavor.popcornCount }));
}
