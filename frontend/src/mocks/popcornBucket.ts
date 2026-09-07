/*
 * 팝콘 버킷 목업. API-041 팝콘 버킷 조회
 *
 * 3.1은 헤더의 `팝콘 100알`에 `totalCount`만 쓴다. 맛별 분포는 3.2(업무 -261)에서
 * 쓰이므로 8개 맛을 계약대로 모두 두었다(API-041: 팝콘이 없으면 popcornCount 0,
 * averageScore null).
 *
 * 표시명 · 색상은 mocks/popcornFlavors.ts와 같은 잠정값이다(API-044 미정 사항).
 * `newFlavorThreshold`도 계약 미정 사항이라 계약 예시의 3을 그대로 썼다.
 * 맛별 알 수의 합은 와이어프레임 3.1의 `팝콘 100알`에 맞춰 100이다.
 */
import { mockPopcornFlavors } from './popcornFlavors';
import type { PopcornBucket, PopcornBucketFlavor } from '../types/taste';

const NEW_FLAVOR_THRESHOLD = 3;

/** 맛 코드별 알 수 · 평균 평점. 합계 100 */
const counts: Record<string, { popcornCount: number; averageScore: number | null }> = {
  FLAVOR_CAPER: { popcornCount: 24, averageScore: 4.4 },
  FLAVOR_THRILL: { popcornCount: 20, averageScore: 4.3 },
  FLAVOR_NOIR: { popcornCount: 15, averageScore: 4.1 },
  FLAVOR_CRIME: { popcornCount: 14, averageScore: 4.0 },
  FLAVOR_ACTION: { popcornCount: 13, averageScore: 3.9 },
  FLAVOR_DRAMA: { popcornCount: 9, averageScore: 3.8 },
  FLAVOR_COMEDY: { popcornCount: 3, averageScore: 3.5 },
  FLAVOR_SF: { popcornCount: 2, averageScore: 3.2 },
};

const flavors: PopcornBucketFlavor[] = mockPopcornFlavors.map((flavor) => {
  const count = counts[flavor.code] ?? { popcornCount: 0, averageScore: null };

  return {
    flavorCode: flavor.code,
    displayName: flavor.displayName,
    color: flavor.color,
    popcornCount: count.popcornCount,
    averageScore: count.averageScore,
    isNewFlavor: count.popcornCount < NEW_FLAVOR_THRESHOLD,
    // 확장 기능 FR-32용 예약 필드. 1차에서는 null 가능
    discoveredAt: null,
  };
});

export const mockPopcornBucket: PopcornBucket = {
  totalCount: flavors.reduce((sum, flavor) => sum + flavor.popcornCount, 0),
  newFlavorThreshold: NEW_FLAVOR_THRESHOLD,
  flavors,
};
