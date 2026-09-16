/*
 * 파티 영화 추천 목업. API-058 파티 영화 추천
 *
 * 와이어프레임 5.5의 세 편과 이유 문장을 그대로 옮겼다. 제목이 `영화 제목 1`인 것도
 * 와이어프레임 그대로다(`mocks/movies.ts` 머리말과 같은 이유).
 *
 * `[!]` 5.5는 카드마다 `일치 94%`라는 **한 개의 퍼센트**를 보여주는데, 계약이 주는 것은
 * `reason.memberFit[]` — **사용자별** 점수(0~1)다. 화면이 쓰는 하나의 값은 계약에 없다.
 * 목업은 계약대로 사용자별 점수를 담고, 화면에서 평균을 내어 보여준다. 계약이 대표값을
 * 정해 주면 화면의 계산이 사라진다.
 *
 * `[?]` 5.5 상단의 공통 키워드(`스릴러` · `빠른 전개` · `반전` · `범죄`)에 대응하는
 * 필드가 API-058에 없다. 5.9 팝콘 섞기가 쓰는 `PartyMixView`(CR-17, 계약 없음)에 같은
 * 성격의 값이 있어 그쪽을 쓴다.
 */
import type { PartyRecommendationSet } from '../types/party';

/** 와이어프레임 5.5의 참여자 셋. 추천이 목업으로 남아 있어 사용자 번호도 자리표시자다 */
const MEMBER_IDS = [1024, 2048, 2049];

export const mockPartyRecommendations: Record<number, PartyRecommendationSet> = {
  9001: {
    recommendationSetId: 7101,
    partyId: 9001,
    generatedAt: '2026-09-10T09:00:00Z',
    items: [
      {
        recommendationItemId: 3401,
        type: 'PARTY',
        movie: {
          movieId: 101,
          title: '영화 제목 1',
          posterUrl: null,
          ottProviders: ['NETFLIX', 'TVING'],
        },
        reason: {
          summary: '세 사람 모두 좋아할 케이퍼 스릴러',
          // 평균 0.94 — 와이어프레임의 `일치 94%`
          memberFit: [
            { userId: MEMBER_IDS[0], score: 0.96 },
            { userId: MEMBER_IDS[1], score: 0.94 },
            { userId: MEMBER_IDS[2], score: 0.92 },
          ],
        },
      },
      {
        recommendationItemId: 3402,
        type: 'PARTY',
        movie: {
          movieId: 102,
          title: '영화 제목 2',
          posterUrl: null,
          ottProviders: ['WAVVE'],
        },
        reason: {
          summary: '빠른 전개의 범죄 스릴러',
          // 평균 0.87
          memberFit: [
            { userId: MEMBER_IDS[0], score: 0.9 },
            { userId: MEMBER_IDS[1], score: 0.88 },
            { userId: MEMBER_IDS[2], score: 0.83 },
          ],
        },
      },
      {
        recommendationItemId: 3403,
        type: 'PARTY',
        movie: {
          movieId: 103,
          title: '영화 제목 3',
          posterUrl: null,
          ottProviders: ['TVING'],
        },
        reason: {
          summary: '반전이 강한 미스터리',
          // 평균 0.81
          memberFit: [
            { userId: MEMBER_IDS[0], score: 0.85 },
            { userId: MEMBER_IDS[1], score: 0.8 },
            { userId: MEMBER_IDS[2], score: 0.78 },
          ],
        },
      },
    ],
  },
};

/**
 * 와이어프레임 5.5의 `왜 같이 추천됐나요?` 문장 셋.
 *
 * `[?]` API-058 `reason`에는 카드별 `summary` 하나뿐이고, 추천 묶음 전체를 설명하는
 * 이 문장들에 대응하는 필드가 없다. 화면이 만들어 낼 수 없는 값이라 목업에 둔다.
 */
export const mockPartyRecommendationReasons: Record<number, string[]> = {
  9001: [
    '세 사람 모두 스릴러를 선호해요',
    '빠른 전개 영화에 높은 별점을 줬어요',
    '모두 아직 보지 않은 작품이에요',
  ],
};
