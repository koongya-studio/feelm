/*
 * 2.2 오늘의 추천 — 카드는 서버 영화다 (업무 S15P21E106-610)
 *
 * 목업 카드가 목업 영화 번호(101~104)로 실제 영화 상세를 열어 전혀 다른 영화가 나오던
 * 문제를 없앴다. 카드는 API-045 응답 그대로이고, 포스터와 `영화 상세 보기`는 그 서버
 * 영화의 번호로 간다. 관심없음(API-047)은 프론트 연동(S15P21E106-536) 전까지 눌리지 않는다.
 *
 * 추천 후보가 아직 없는 `503 RECOMMENDATION_UNAVAILABLE`은 장애가 아니라 준비 중으로
 * 안내한다(업무 S15P21E106-654). 그 밖의 실패만 2.2-A 문구를 쓴다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import RecommendationsPage from './index';
import { ApiError } from '../../../api/apiError';
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

describe('2.2-A 추천을 불러오지 못한 경우', () => {
  it('추천 후보가 아직 없으면(503) 오류가 아니라 준비 중으로 안내하고 다시 확인할 수 있다', async () => {
    vi.mocked(getRecommendations)
      .mockRejectedValueOnce(
        new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '추천을 사용할 수 없습니다.'),
      )
      .mockResolvedValue(set);
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    expect(await screen.findByText('취향에 맞는 추천을 준비하고 있어요')).toBeInTheDocument();
    expect(
      screen.getByText(
        /잠시 후 다시 확인해 주세요\.\s+그동안 영화를 평가하면 추천이 더 정확해져요/,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('추천을 잠시 불러오지 못했어요')).toBeNull();
    expect(screen.getByRole('link', { name: '영화 검색하기' })).toHaveAttribute('href', '/search');

    fireEvent.click(screen.getByRole('button', { name: '다시 확인하기' }));

    expect(await screen.findByRole('link', { name: '파이트 클럽 상세 보기' })).toBeInTheDocument();
    expect(getRecommendations).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['네트워크 단절', new ApiError(0, 'NETWORK_ERROR', '네트워크에 연결할 수 없습니다.')],
    ['서버 오류', new ApiError(500, 'INTERNAL_ERROR', '서버 오류가 발생했습니다.')],
  ])('%s는 2.2-A 문구를 그대로 쓴다', async (_, error) => {
    vi.mocked(getRecommendations).mockRejectedValue(error);
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    expect(await screen.findByText('추천을 잠시 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.getByText('검색과 영화 평가는 계속 이용할 수 있어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.queryByText('취향에 맞는 추천을 준비하고 있어요')).toBeNull();
  });
});
