/*
 * 파티원 팝콘 목업. API-057 파티원 팝콘 조회
 *
 * 와이어프레임 5.8은 멤버 2명(`김난다 (나)` 100알 · `무비버디` 86알)을 보여주지만
 * 2.1은 같은 파티를 `멤버 4명`으로 적고 있습니다. 목업 파티는 하나뿐이라 이미 병합된
 * 2.1 기준인 4명을 쓰기로 정했습니다(팀 결정 2026-09-07). 화면은 멤버 수에 무관하게
 * 렌더하므로 screen-spec.md 5.8의 `3~4명 배치 미확정`도 함께 해소됩니다.
 *
 * 김난다의 분포는 와이어프레임 5.8의 28 · 21 · 15 · 8 · 10 · 9 · 4 · 5 그대로이며
 * mocks/popcornBucket.ts(내 팝콘 버킷)와 같은 값입니다. 같은 사람의 팝콘이므로
 * 3.2와 5.8이 어긋나면 안 됩니다.
 *
 * 무비버디의 분포는 와이어프레임 막대 비율(30.2% · 20.9% · 16.3% · 11.6% · 9.3% ·
 * 5.8% · 3.5% · 2.3%)을 86알로 환산한 값입니다.
 *
 * 나머지 두 명은 와이어프레임에 없어 자리표시자이지만, `서로 겹치는 맛`이 파티원 모두의
 * 상위 3개 맛 교집합이므로 네 명 모두 상위 3개에 같은 맛 두 개가 들어가도록 두었습니다.
 * 그러지 않으면 교집합이 비어 와이어프레임의 태그 2개가 나오지 않습니다.
 */
import { mockPopcornBucket } from './popcornBucket';
import { mockPopcornFlavors } from './popcornFlavors';
import type { PartyMemberFlavor, PartyMemberPopcorn, PartyPopcorn } from '../types/party';
import type { PartyMixView } from '../types/unspecified';

/** 맛 순서대로 알 수를 붙인다. 평균 평점은 알이 없으면 null이다(API-057) */
function flavors(countsInOrder: number[], scoresInOrder: (number | null)[]): PartyMemberFlavor[] {
  return mockPopcornFlavors.map((flavor, index) => ({
    flavorCode: flavor.code,
    popcornCount: countsInOrder[index],
    averageScore: countsInOrder[index] === 0 ? null : scoresInOrder[index],
  }));
}

function member(
  userId: number,
  nickname: string,
  countsInOrder: number[],
  scoresInOrder: (number | null)[],
): PartyMemberPopcorn {
  return {
    user: { userId, nickname },
    totalCount: countsInOrder.reduce((sum, count) => sum + count, 0),
    flavors: flavors(countsInOrder, scoresInOrder),
  };
}

/** 내 팝콘 버킷(3.2)과 같은 값을 쓴다 */
const myCounts = mockPopcornBucket.flavors.map((flavor) => flavor.popcornCount);
const myScores = mockPopcornBucket.flavors.map((flavor) => flavor.averageScore);

export const mockPartyPopcorn: Record<number, PartyPopcorn> = {
  9001: {
    partyId: 9001,
    members: [
      member(1024, '김난다', myCounts, myScores),
      member(
        2048,
        '무비버디',
        [18, 26, 14, 5, 8, 10, 3, 2],
        [4.0, 4.5, 4.2, 3.6, 3.9, 4.1, 3.4, 3.0],
      ),
      member(2049, '멤버3', [16, 14, 8, 12, 6, 5, 3, 2], [3.8, 3.9, 3.7, 4.2, 4.3, 4.0, 3.5, 3.1]),
      member(2050, '멤버4', [12, 10, 5, 4, 6, 4, 2, 1], [3.6, 3.7, 3.5, 3.8, 4.0, 4.2, 3.3, 3.0]),
    ],
  },
};

export function findMockPartyPopcorn(partyId: number): PartyPopcorn | null {
  return mockPartyPopcorn[partyId] ?? null;
}

/*
 * 5.9 파티 팝콘 섞기 목업. **계약에 대응이 없습니다(CR-17).**
 *
 * `46%` · `38%` · `공통 취향 일치 72%`와 태그 4종은 와이어프레임 값을 그대로 옮긴
 * 것입니다. 산출 규칙이 없고 API-057의 맛별 집계로도 재현되지 않습니다.
 * 나머지 두 명의 값은 와이어프레임에 없어 자리표시자입니다.
 */
export const mockPartyMix: Record<number, PartyMixView> = {
  9001: {
    partyId: 9001,
    members: [
      { userId: 1024, tasteShare: 46, tags: ['케이퍼', '빠른 전개'] },
      { userId: 2048, tasteShare: 38, tags: ['반전', '느와르'] },
      { userId: 2049, tasteShare: 31, tags: ['성장', '관계'] },
      { userId: 2050, tasteShare: 27, tags: ['말맛', '소동'] },
    ],
    commonRate: 72,
    tags: ['스릴러', '빠른 전개'],
  },
};

export function findMockPartyMix(partyId: number): PartyMixView | null {
  return mockPartyMix[partyId] ?? null;
}
