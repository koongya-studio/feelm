/*
 * 파티 영화 추천 목업. API-058 파티 영화 추천
 *
 * 와이어프레임 5.5의 세 편과 이유 문장을 그대로 옮겼다. 제목이 `영화 제목 1`인 것도
 * 와이어프레임 그대로다(`mocks/movies.ts` 머리말과 같은 이유).
 *
 * 목업은 계약대로 `reason.memberFit[]`에 **사용자별** 점수(0~1)를 담는다. 화면은 이 값을
 * 쓰지 않는다 — 카드마다 퍼센트 하나를 보여주려면 대표값이 필요한데 계약에 없어, 사용자
 * 시안(업무 -462)에 따라 5.5에서 퍼센트를 걷어냈다. 계약이 대표값을 정하면 그때 넣는다.
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
