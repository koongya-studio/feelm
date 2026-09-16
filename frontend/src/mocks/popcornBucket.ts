/*
 * 팝콘 버킷 목업. API-041 팝콘 버킷 조회
 *
 * **3.1 · 3.2의 팝콘 버킷은 실제 API를 부른다**(S15P21E106-392). 여기 남은 값은 평가
 * API가 없어 목업으로 남은 흐름이 쓴다 — 2.6 · 2.7의 팝콘 알 수, 5.8 · 5.9 파티 팝콘,
 * 7.2-A · 7.2-C 공개 프로필의 맛 분포다.
 *
 * 표시명 · 색상은 mocks/popcornFlavors.ts와 같은 잠정값이다(API-044 미정 사항).
 *
 * 맛별 알 수는 와이어프레임 3.2의 분포(28 · 21 · 15 · 8 · 10 · 9 · 4 · 5)를 맛
 * 순서대로 옮긴 값이며 합이 100이다. 3.1의 `팝콘 100알`과 3.2의 `본 영화 100편`이
 * 모두 이 합을 쓴다. 상위 두 맛의 합 49%가 3.2의 요약 문장에 그대로 나온다.
 *
 * 와이어프레임 3.2는 맛 이름에 장르명을 하드코딩했지만 계약은 API-044의 `displayName`을
 * 쓰라고 정하고 있다(screen-spec.md 8.1 #2). 그래서 분포만 옮기고 이름은 옮기지 않았다.
 */
import { mockPopcornFlavors } from './popcornFlavors';
import type { PopcornBucket, PopcornBucketFlavor } from '../types/taste';

/** 맛 코드별 알 수 · 평균 평점. 합계 100 */
const counts: Record<string, { popcornCount: number; averageScore: number | null }> = {
  FLAVOR_CAPER: { popcornCount: 28, averageScore: 4.4 },
  FLAVOR_THRILL: { popcornCount: 21, averageScore: 4.3 },
  FLAVOR_NOIR: { popcornCount: 15, averageScore: 4.1 },
  FLAVOR_CRIME: { popcornCount: 8, averageScore: 4.0 },
  FLAVOR_ACTION: { popcornCount: 10, averageScore: 3.9 },
  FLAVOR_DRAMA: { popcornCount: 9, averageScore: 3.8 },
  FLAVOR_COMEDY: { popcornCount: 4, averageScore: 3.5 },
  FLAVOR_SF: { popcornCount: 5, averageScore: 3.2 },
};

const flavors: PopcornBucketFlavor[] = mockPopcornFlavors.map((flavor) => {
  const count = counts[flavor.code] ?? { popcornCount: 0, averageScore: null };

  return {
    flavorCode: flavor.code,
    displayName: flavor.displayName,
    color: flavor.color,
    popcornCount: count.popcornCount,
    averageScore: count.averageScore,
    // 확장 기능 FR-32용 예약 필드. 1차에서는 null 가능
    discoveredAt: null,
  };
});

export const mockPopcornBucket: PopcornBucket = {
  totalCount: flavors.reduce((sum, flavor) => sum + flavor.popcornCount, 0),
  flavors,
};
