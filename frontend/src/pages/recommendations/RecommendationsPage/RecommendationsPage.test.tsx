/*
 * 2.2 오늘의 추천 — 카드는 서버 영화다 (업무 S15P21E106-610)
 *
 * 목업 카드가 목업 영화 번호(101~104)로 실제 영화 상세를 열어 전혀 다른 영화가 나오던
 * 문제를 없앴다. 카드는 API-045 응답 그대로이고, 포스터와 `영화 상세 보기`는 그 서버
 * 영화의 번호로 간다. 관심없음(API-047)은 프론트 연동(S15P21E106-536) 전까지 눌리지 않는다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import RecommendationsPage from './index';
import { renderWithRouter } from '../../../test/render';
import type { RecommendationSet } from '../../../types/recommendation';

vi.mock('../../../api/recommendations', () => ({
  getRecommendations: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

const { getRecommendations } = await import('../../../api/recommendations');
const { getOtts } = await import('../../../api/otts');

/** 서버가 준 세트. 영화 번호 550은 목업 어디에도 없는 실제 번호다 */
const set: RecommendationSet = {
  recommendationSetId: 7001,
  generatedAt: '2026-09-18T00:00:00Z',
  items: [
    {
      recommendationItemId: 3301,
      type: 'TASTE',
      movie: {
        movieId: 550,
        title: '파이트 클럽',
        posterUrl: null,
        releaseYear: 1999,
        genres: ['드라마'],
        averageRating: 4.3,
        ottProviders: ['NETFLIX'],
      },
      flavorCode: 'FLAVOR_A',
      reason: { summary: '스릴러를 높게 평가하셨어요', factors: [] },
    },
    {
      recommendationItemId: 3302,
      type: 'DISCOVERY',
      movie: {
        movieId: 807,
        title: '세븐',
        posterUrl: null,
        releaseYear: 1995,
        genres: ['스릴러'],
        averageRating: 4.5,
        ottProviders: [],
      },
      flavorCode: 'FLAVOR_C',
      reason: { summary: '좋아하는 스릴러와 연결되는 느와르예요', factors: [] },
    },
  ],
};

beforeEach(() => {
  vi.mocked(getRecommendations).mockReset().mockResolvedValue(set);
  vi.mocked(getOtts)
    .mockReset()
    .mockResolvedValue([{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }]);
});

describe('2.2 오늘의 추천', () => {
  it('서버가 준 카드를 그리고 포스터는 그 영화의 상세로 간다', async () => {
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    const poster = await screen.findByRole('link', { name: '파이트 클럽 상세 보기' });
    expect(poster).toHaveAttribute('href', '/movies/550');
    expect(getRecommendations).toHaveBeenCalledTimes(1);

    // 일치도 %는 계약에 없어 그리지 않는다(CR-13)
    expect(screen.queryByText(/일치 \d+%/)).toBeNull();
    expect(screen.getByText('안 봄')).toBeInTheDocument();
    expect(screen.getByText('새로운 맛')).toBeInTheDocument();
  });

  it('⋯ 메뉴의 영화 상세 보기도 서버 영화로 가고, 관심없음은 아직 눌리지 않는다', async () => {
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    fireEvent.click(await screen.findByRole('button', { name: /세븐 OTT 메뉴/ }));

    expect(screen.getByRole('link', { name: /영화 상세 보기/ })).toHaveAttribute(
      'href',
      '/movies/807',
    );
    expect(screen.getByRole('button', { name: /관심없음/ })).toBeDisabled();
  });

  it('빈 세트는 목업으로 채우지 않고 빈 상태를 말한다', async () => {
    vi.mocked(getRecommendations).mockResolvedValue({ ...set, items: [] });
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    expect(await screen.findByText('추천할 영화를 아직 찾지 못했어요')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /상세 보기/ })).toBeNull();
  });
});
