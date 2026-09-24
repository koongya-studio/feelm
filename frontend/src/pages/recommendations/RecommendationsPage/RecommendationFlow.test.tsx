/*
 * 3.1 취향 필름 → 2.2 오늘의 추천 실제 연결 흐름.
 *
 * 필름 화면의 버튼과 추천 화면의 API 호출을 따로 검증하면 둘 사이 라우트가 끊겨도 각
 * 단위 테스트는 통과한다. 사용자가 `영화 추천받기`를 누른 순간 API-045를 요청하고 서버가
 * 선택한 최신 추천 세트를 그리는 데까지 한 흐름으로 고정한다(S15P21E106-613).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import FilmPage from '../../film/FilmPage';
import { AuthContext } from '../../../contexts/authContext';
import { testAuth } from '../../../test/auth';
import RecommendationsPage from './index';
import type { RecommendationSet } from '../../../types/recommendation';

vi.mock('../../../api/taste', () => ({
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
}));

vi.mock('../../../api/reports', () => ({
  getLatestReadyReport: vi.fn(),
}));

vi.mock('../../../api/recommendations', () => ({
  getRecommendations: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

const { getMyFrames, getPopcornBucket } = await import('../../../api/taste');
const { getLatestReadyReport } = await import('../../../api/reports');
const { getRecommendations } = await import('../../../api/recommendations');
const { getOtts } = await import('../../../api/otts');

const latestSet: RecommendationSet = {
  recommendationSetId: 7002,
  generatedAt: '2026-09-18T00:01:00Z',
  items: [
    {
      recommendationItemId: 4401,
      type: 'TASTE',
      movie: {
        movieId: 807,
        title: '세븐',
        posterUrl: null,
        releaseYear: 1995,
        genres: ['스릴러'],
        averageRating: 4.5,
        ottProviders: ['NETFLIX'],
      },
      flavorCode: 'FLAVOR_A',
      reason: { summary: '새로 반영된 취향과 잘 맞아요', factors: [] },
    },
  ],
};

beforeEach(() => {
  vi.mocked(getMyFrames)
    .mockReset()
    .mockResolvedValue({
      totalCount: 1,
      items: [
        {
          frameId: 1,
          movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
          score: 4.5,
          flavorCode: 'FLAVOR_A',
          createdAt: '2026-09-18T00:00:00Z',
        },
      ],
      nextCursor: null,
      hasNext: false,
    });
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue({ totalCount: 0, flavors: [] });
  vi.mocked(getLatestReadyReport).mockReset().mockResolvedValue(null);
  vi.mocked(getRecommendations).mockReset().mockResolvedValue(latestSet);
  vi.mocked(getOtts)
    .mockReset()
    .mockResolvedValue([{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }]);
});

describe('영화 추천받기 연결', () => {
  it('영화 추천받기를 누르면 API-045의 최신 추천 세트를 조회해 그린다', async () => {
    render(
      <AuthContext.Provider value={testAuth}>
        <MemoryRouter initialEntries={['/film']}>
          <Routes>
            <Route path="/film" element={<FilmPage />} />
            <Route path="/recommendations" element={<RecommendationsPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '영화 추천받기' }));

    expect(await screen.findByRole('link', { name: '세븐 상세 보기' })).toHaveAttribute(
      'href',
      '/movies/807',
    );
    expect(screen.getByText('새로 반영된 취향과 잘 맞아요')).toBeInTheDocument();
    expect(getRecommendations).toHaveBeenCalledTimes(1);
  });
});
