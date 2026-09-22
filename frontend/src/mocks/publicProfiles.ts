/*
 * 내 프로필 기준값. 7.1 · 7.2 · 7.2-A가 같은 모양(`ProfileView`)으로 읽는다(업무 -616).
 *
 * 닉네임 · 수치는 실제 API-014로, 사진 · 소개 · 공개 항목은 이번 세션에 바꾼 값으로
 * `src/api/users.ts`가 덮는다. 사진 · 소개 · 항목별 공개 여부는 계약에 자리가 없어서 이
 * 기준값에서 시작한다. 키워드 · 필름 목록도 여기서 온다 — 내 `publicId`를 알 길이 없어
 * 내 공개 프로필(API-062)을 부를 수 없기 때문이다.
 *
 * 다른 사용자 공개 프로필(7.2-B)의 목업(무비버디 2048)은 업무 -810에서 지웠다. 7.2-B는 실제
 * API-062를 부른다.
 *
 * 값의 출처
 * - 키워드는 와이어프레임 7.2-A의 4개다
 * - 필름은 `mocks/frames.ts`, 팝콘은 `mocks/popcornBucket.ts`를 그대로 읽는다. 같은 값을 두 번
 *   적지 않는다
 *
 * 키워드 · 필름을 공개할지는 7.2에서 하나씩 고른다(업무 -616). 여기 값은 **모두 공개인
 * 처음 상태**이고, 고른 결과는 세션 저장소가 덮는다. `reviews`가 빈 배열인 이유는
 * 와이어프레임 7.2-A가 한줄평을 비공개로 둔 상태를 그렸기 때문이다.
 */
import { mockPopcornBucket } from './popcornBucket';
import { mockFrameList } from './frames';
import { mockMyProfile } from './user';
import type { ProfileView } from '../types/unspecified';

/**
 * 내 프로필의 기준값 (7.1 · 7.2 · 7.2-A).
 *
 * **모든 키워드 · 필름을 담는다** — 무엇이 공개인지는 `isPublic`이 말하고, 공개 모양은 그중
 * 공개인 것만 남긴다.
 */
export const mockMyProfileView: ProfileView = {
  userId: mockMyProfile.userId,
  nickname: mockMyProfile.nickname,
  avatarUrl: null,
  // 사용자 시안 7.1 · 7.2 · 7.2-A의 소개 문구 그대로다
  bio: '영화의 장면과 감정을 기록해요',
  stats: {
    frameCount: mockMyProfile.stats.frameCount,
    popcornCount: mockMyProfile.stats.popcornCount,
    // 와이어프레임 7.2-A는 한줄평을 비공개로 두었다
    publicReviewCount: 0,
  },
  // 와이어프레임 7.2-A의 키워드 4개
  keywords: [
    { code: 'PLANNED_ANTIHERO', label: '계획형 안티히어로', isPublic: true },
    { code: 'FAST_PACED', label: '빠른 전개', isPublic: true },
    { code: 'TWIST_LOVER', label: '반전 선호', isPublic: true },
    { code: 'NOIR_EXPLORER', label: '느와르 탐험가', isPublic: true },
  ],
  frames: mockFrameList.items.map((item) => ({
    frameId: item.frameId,
    movieId: item.movie.movieId,
    title: item.movie.title,
    posterUrl: item.movie.posterUrl,
    isPublic: true,
  })),
  popcorn: mockPopcornBucket.flavors.map((flavor) => ({
    flavorCode: flavor.flavorCode,
    popcornCount: flavor.popcornCount,
  })),
  // 비공개로 설정한 항목은 미리보기에도 나오지 않는다
  reviews: [],
};
