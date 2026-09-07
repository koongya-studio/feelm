/*
 * 공개 한줄평 목업. API-032 공개 한줄평 목록
 *
 * 닉네임 · 별점 · 내용은 와이어프레임 2.4에 적힌 값을 그대로 옮겼다.
 * 별점 4.5 · 4.0 · 3.5는 0.5점 단위 규칙과 일치한다.
 */
import type { PublicReview } from '../types/watch';

export const mockReviews: Record<number, PublicReview[]> = {
  101: [
    {
      ratingId: 9101,
      user: { userId: 3001, nickname: 'film_daily' },
      score: 4.5,
      comment: '마술 트릭 하나하나가 복선이라 두 번 보게 돼요.',
      mine: false,
      createdAt: '2026-09-01T00:00:00Z',
    },
    {
      ratingId: 9102,
      user: { userId: 3002, nickname: 'moviegoer_k' },
      score: 4.0,
      comment: '속도감 최고. 생각 없이 봐도, 생각하며 봐도 즐거움.',
      mine: false,
      createdAt: '2026-08-30T00:00:00Z',
    },
    {
      ratingId: 9103,
      user: { userId: 3003, nickname: 'cine_note' },
      score: 3.5,
      comment: '반전은 예상 가능하지만 과정이 충분히 매력적.',
      mine: false,
      createdAt: '2026-08-28T00:00:00Z',
    },
  ],
};
