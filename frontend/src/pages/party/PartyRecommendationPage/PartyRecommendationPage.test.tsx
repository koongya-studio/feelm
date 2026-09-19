/*
 * 5.5 추천 완성 — API-058 연동(업무 S15P21E106-705).
 *
 * 추천 카드가 서버 영화의 상세로 가는지, 추천을 못 받은 까닭(없는 파티 · 끝난 파티 · 준비 중 ·
 * 0편 · 그 밖의 실패)마다 문구와 다음 행동이 갈리는지 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyRecommendationPage from './index';
import { ApiError } from '../../../api/apiError';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { renderWithRouter } from '../../../test/render';
import { party, partyPopcorn, partyRecommendations, TEST_PARTY_ID } from '../../../test/party';

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    getParty: vi.fn(),
    getPartyPopcorn: vi.fn(),
    getPartyRecommendations: vi.fn(),
  };
});

beforeEach(() => {
  vi.mocked(getParty).mockReset().mockResolvedValue(party());
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPartyRecommendations).mockReset().mockResolvedValue(partyRecommendations());
});

function renderPage() {
  return renderWithRouter(<PartyRecommendationPage />, {
    route: `/parties/${TEST_PARTY_ID}/recommendations`,
    path: '/parties/:partyId/recommendations',
  });
}

const location = () => screen.getByTestId('location').textContent;

describe('5.5 추천 완성', () => {
  it('이 파티의 추천을 받아 세 편을 보이고, 카드는 영화 상세로 간다', async () => {
    renderPage();

    expect(await screen.findByText('우리에게 어울리는 3편')).toBeInTheDocument();
    expect(getPartyRecommendations).toHaveBeenCalledWith(TEST_PARTY_ID);

    const card = screen.getByRole('link', { name: /펄프 픽션/ });

    expect(card).toHaveAttribute('href', '/movies/551');
    fireEvent.click(card);
    expect(location()).toBe('/movies/551');
  });

  it('추천이 0편이면 다시 추천받도록 안내한다', async () => {
    vi.mocked(getPartyRecommendations).mockResolvedValue({
      ...partyRecommendations(),
      items: [],
    });
    renderPage();

    expect(await screen.findByText('지금은 함께 볼 영화를 찾지 못했어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 추천받기' }));
    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/analyzing`);
  });

  it.each([
    [new ApiError(404, 'RESOURCE_NOT_FOUND', '없음'), '파티를 찾을 수 없어요'],
    [new ApiError(409, 'PARTY_CLOSED', '종료'), '끝난 파티예요'],
  ])('다시 받아도 같은 실패(%s)는 내 파티로 돌려보낸다', async (error, title) => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(error);
    renderPage();

    expect(await screen.findByText(title)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 추천받기' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '내 파티로 돌아가기' }));
    expect(location()).toBe('/party');
  });

  it('파티원 취향이 준비 중(503)이면 기다렸다 다시 받도록 안내한다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(
      new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '준비 중'),
    );
    renderPage();

    expect(await screen.findByText('아직 추천을 준비하고 있어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 추천받기' })).toBeInTheDocument();
  });

  it('까닭을 모르는 실패는 일반 문구로 알린다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(new TypeError('Failed to fetch'));
    renderPage();

    expect(await screen.findByText('추천을 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 추천받기' })).toBeInTheDocument();
  });
});
