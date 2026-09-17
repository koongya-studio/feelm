/*
 * 공개 프로필과 취향 비교 목업.
 *
 * 계약은 API-062 · API-063이지만 **2차 MVP라 아직 없다.** 응답 모양도 계약과 다르다
 * (types/unspecified.ts의 `[!]` 목록).
 *
 * 7.2-A 내 공개 프로필 미리보기 · 7.2-B 다른 사용자 공개 프로필 · 7.2-C 사용자 취향 비교.
 *
 * 값의 출처
 * - 키워드 · 팝콘 분포 · 한줄평 · 일치율은 와이어프레임 7.2-A · 7.2-B · 7.2-C에서 옮겼다
 * - 무비버디(2048)는 5.8 파티원 목업에 이미 있는 사용자다. **5.8에서 이 화면으로
 *   들어오므로**(팀 결정 2026-09-11) 팝콘 분포를 `mocks/partyPopcorn.ts`와 맞췄다.
 *   두 화면이 같은 사람을 다르게 보여주면 안 된다
 * - 내 프로필(1024)의 키워드는 `mocks/taste.ts`의 `mockTasteFlow`를, 팝콘은
 *   `mocks/popcornBucket.ts`를 그대로 읽는다. 같은 값을 두 번 적지 않는다
 *
 * `[?]` 무엇이 공개되는지는 7.8-B 공개 범위 설정이 정한다. 그 화면이 아직 없으므로
 * 여기 값은 **이미 공개로 설정된 상태**를 그린 것이다. 7.2-A의 `reviews`가 빈 배열인
 * 이유도 와이어프레임이 한줄평을 비공개로 둔 상태를 그렸기 때문이다.
 */
import { mockPopcornBucket } from './popcornBucket';
import { mockPopcornFlavors } from './popcornFlavors';
import { mockFrameList } from './frames';
import { mockTasteFlow } from './taste';
import { mockMyProfile } from './user';
import type { PublicProfileView } from '../types/unspecified';

/** 공개 프로필이 필름을 몇 개까지 보여주는지. 와이어프레임 7.2-A · 7.2-B가 3개를 그렸다 */
const PUBLIC_FRAME_COUNT = 3;

/** 무비버디의 맛별 알 수. `mocks/partyPopcorn.ts`의 2048과 같은 값이다 */
const BUDDY_COUNTS = [18, 26, 14, 5, 8, 10, 3, 2];

function popcornOf(countsInOrder: number[]) {
  return mockPopcornFlavors.map((flavor, index) => ({
    flavorCode: flavor.code,
    popcornCount: countsInOrder[index],
  }));
}

/**
 * 내 공개 프로필 (7.2-A).
 *
 * 닉네임은 7.2에서 바뀔 수 있으므로 여기 값은 기준값이고, `src/api/users.ts`가 실제
 * 프로필의 값으로 덮어씌운다.
 */
export const mockMyPublicProfile: PublicProfileView = {
  userId: mockMyProfile.userId,
  nickname: mockMyProfile.nickname,
  stats: {
    frameCount: mockMyProfile.stats.frameCount,
    popcornCount: mockMyProfile.stats.popcornCount,
    // 와이어프레임 7.2-A는 한줄평을 비공개로 두었다
    publicReviewCount: 0,
  },
  // 와이어프레임 7.2-A의 키워드 4개가 3.1 `요즘 취향 흐름`과 같은 값이다
  keywords: mockTasteFlow.keywords,
  frames: mockFrameList.items.slice(0, PUBLIC_FRAME_COUNT).map((item) => ({
    frameId: item.frameId,
    movieId: item.movie.movieId,
    title: item.movie.title,
    posterUrl: item.movie.posterUrl,
  })),
  popcorn: mockPopcornBucket.flavors.map((flavor) => ({
    flavorCode: flavor.flavorCode,
    popcornCount: flavor.popcornCount,
  })),
  // 비공개로 설정한 항목은 미리보기에도 나오지 않는다
  reviews: [],
};

/** 다른 사용자 공개 프로필 (7.2-B). 지금은 무비버디 한 명이다 */
export const mockPublicProfiles: Record<number, PublicProfileView> = {
  2048: {
    userId: 2048,
    nickname: '무비버디',
    stats: {
      frameCount: 86,
      popcornCount: 86,
      publicReviewCount: 1,
    },
    // 와이어프레임 7.2-B의 키워드 3개
    keywords: [
      { code: 'TWIST_LOVER', label: '반전 선호', rationale: '반전 태그 영화 평균 ★4.4' },
      { code: 'NOIR', label: '느와르', rationale: '느와르 프레임 14개' },
      { code: 'FAST_PACED', label: '빠른 전개', rationale: '빠른 전개 태그 상위 15%' },
    ],
    frames: mockFrameList.items.slice(0, PUBLIC_FRAME_COUNT).map((item) => ({
      frameId: item.frameId,
      movieId: item.movie.movieId,
      title: item.movie.title,
      posterUrl: item.movie.posterUrl,
    })),
    popcorn: popcornOf(BUDDY_COUNTS),
    reviews: [
      {
        ratingId: 9201,
        movieTitle: '영화 제목 1',
        // 와이어프레임 7.2-B의 한줄평 그대로
        comment: '스릴러만 먹다가 드라마 맛도 늘었어요.',
        score: 4.0,
      },
    ],
  },
};

export function findMockPublicProfile(userId: number): PublicProfileView | null {
  return mockPublicProfiles[userId] ?? null;
}
