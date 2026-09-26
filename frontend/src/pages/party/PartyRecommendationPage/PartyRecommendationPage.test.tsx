/*
 * 5.5 추천 완성 — API-058 연동(업무 S15P21E106-705).
 *
 * 추천 카드가 서버 영화의 상세로 가는지, 추천을 못 받은 까닭(없는 파티 · 끝난 파티 · 준비 중 ·
 * 0편 · 그 밖의 실패)마다 문구와 다음 행동이 갈리는지 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartyRecommendationPage from './index';
import { ApiError } from '../../../api/apiError';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { submitFeedback } from '../../../api/feedback';
import { renderWithRouter } from '../../../test/render';
import { AuthContext } from '../../../contexts/authContext';
import { testAuth } from '../../../test/auth';
import { hasSeenPartyRecommendation } from '../../../utils/partyRecommendationSeen';
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

vi.mock('../../../api/feedback', () => ({ submitFeedback: vi.fn() }));

beforeEach(() => {
  vi.mocked(getParty).mockReset().mockResolvedValue(party());
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPartyRecommendations).mockReset().mockResolvedValue(partyRecommendations());
  vi.mocked(submitFeedback).mockReset().mockResolvedValue({
    id: 1,
    createdAt: '2026-09-26T00:00:00Z',
  });
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
    expect(screen.queryByRole('region', { name: '이번 파티 추천 의견' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 추천받기' }));
    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/popcorn/mix`);
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

it('shows feedback after the last party movie and remembers it per member and bundle', async () => {
  localStorage.clear();
  const userId = 1024;
  const { unmount } = renderWithRouter(
    <AuthContext.Provider value={{ ...testAuth, user: { ...testAuth.user!, userId } }}>
      <PartyRecommendationPage />
    </AuthContext.Provider>,
    {
      route: `/parties/${TEST_PARTY_ID}/recommendations`,
      path: '/parties/:partyId/recommendations',
    },
  );

  const feedback = await screen.findByRole('region', { name: '이번 파티 추천 의견' });
  const lastMovie = screen.getByRole('link', { name: /포레스트 검프/ });
  expect(lastMovie.compareDocumentPosition(feedback)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);

  fireEvent.click(screen.getByRole('button', { name: '마음에 들어요' }));
  await waitFor(() => expect(submitFeedback).toHaveBeenCalledTimes(1));
  expect(
    localStorage.getItem('feelm:party-recommendation-feedback:1024:9001:7101:3401,3402,3403'),
  ).toBe('sent');
  unmount();

  renderWithRouter(
    <AuthContext.Provider value={{ ...testAuth, user: { ...testAuth.user!, userId: 2048 } }}>
      <PartyRecommendationPage />
    </AuthContext.Provider>,
    {
      route: `/parties/${TEST_PARTY_ID}/recommendations`,
      path: '/parties/:partyId/recommendations',
    },
  );
  expect(await screen.findByRole('region', { name: '이번 파티 추천 의견' })).toHaveTextContent(
    '이번 파티 추천은 어땠나요?',
  );
});

/*
 * 업무 -802. 백엔드 -792가 API-058에 다시 받기(`refresh`)와 `hasNext`를 더했다. 이 화면은
 * 다시 받기를 직접 부르지 않고 5.9에 표시를 실어 보낸다.
 */
describe('다시 받기 (API-058 refresh · hasNext)', () => {
  it('이 화면의 본요청은 일반 조회다', async () => {
    renderPage();

    await screen.findByText('우리에게 어울리는 3편');
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
    expect(vi.mocked(getPartyRecommendations).mock.calls[0]).toEqual([TEST_PARTY_ID]);
  });

  it('다른 영화 추천받기는 refresh 표시를 들고 5.9로 간다', async () => {
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '다른 영화 추천받기' }));

    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/popcorn/mix`);
    expect(screen.getByTestId('location-state').textContent).toBe('{"refresh":true}');
  });

  it('hasNext가 거짓이면 버튼을 감추고 더 없다고 알린다', async () => {
    vi.mocked(getPartyRecommendations).mockResolvedValue({
      ...partyRecommendations(),
      hasNext: false,
    });
    renderPage();

    expect(await screen.findByText('우리에게 어울리는 3편')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다른 영화 추천받기' })).not.toBeInTheDocument();
    expect(screen.getByText(/더 보여줄 영화가 없어요/)).toBeInTheDocument();
  });

  it('hasNext가 없는 옛 응답은 있는 것으로 본다', async () => {
    const legacy: Partial<ReturnType<typeof partyRecommendations>> = partyRecommendations();
    delete legacy.hasNext;
    vi.mocked(getPartyRecommendations).mockResolvedValue(legacy as never);
    renderPage();

    expect(await screen.findByRole('button', { name: '다른 영화 추천받기' })).toBeInTheDocument();
  });
});

/*
 * 업무 -855. 5.8 · 5.1 보조 패널은 이 계정이 본 묶음이면 5.9 섞는 장면을 건너뛴다. 본 것은
 * 여기서 남긴다 — 그린 묶음만, 계정마다 따로.
 */
describe('본 묶음 남기기', () => {
  const USER_ID = 1024;

  function renderAs(userId: number) {
    return renderWithRouter(
      <AuthContext.Provider value={{ ...testAuth, user: { ...testAuth.user!, userId } }}>
        <PartyRecommendationPage />
      </AuthContext.Provider>,
      {
        route: `/parties/${TEST_PARTY_ID}/recommendations`,
        path: '/parties/:partyId/recommendations',
      },
    );
  }

  beforeEach(() => {
    localStorage.clear();
  });

  it('그린 묶음을 이 계정이 본 것으로 남긴다. 다른 계정은 본 것이 아니다', async () => {
    renderAs(USER_ID);

    expect(await screen.findByText('우리에게 어울리는 3편')).toBeInTheDocument();
    expect(hasSeenPartyRecommendation(USER_ID, TEST_PARTY_ID, partyRecommendations())).toBe(true);
    expect(hasSeenPartyRecommendation(2048, TEST_PARTY_ID, partyRecommendations())).toBe(false);
  });

  it('추천을 못 받으면 아무것도 남기지 않는다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(
      new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '준비 중'),
    );
    renderAs(USER_ID);

    expect(await screen.findByText('아직 추천을 준비하고 있어요')).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
  });
});

describe('추천 영화 페이지 (S15P21E106-961)', () => {
  function recommendations(count: number) {
    const set = partyRecommendations();
    return {
      ...set,
      items: Array.from({ length: count }, (_, index) => ({
        ...set.items[0],
        recommendationItemId: 8000 + index,
        movie: { ...set.items[0].movie, movieId: 9000 + index, title: `추천 영화 ${index + 1}` },
      })),
    };
  }

  it('9편을 3편씩 모두 넘겨보고 이전 페이지로 돌아간다. 페이지 이동은 API를 다시 부르지 않는다', async () => {
    vi.mocked(getPartyRecommendations).mockResolvedValue(recommendations(9));
    renderPage();
    await screen.findByText('우리에게 어울리는 9편');
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();
    for (let page = 0; page < 3; page++) {
      expect(screen.getByRole('status', { name: `${page + 1} / 3 페이지` })).toBeInTheDocument();
      for (let index = 0; index < 9; index++) {
        const link = screen.queryByRole('link', { name: `추천 영화 ${index + 1}` });
        if (Math.floor(index / 3) === page) {
          expect(link).toHaveAttribute('href', `/movies/${9000 + index}`);
        } else {
          expect(link).toBeNull();
        }
      }
      if (page < 2) fireEvent.click(screen.getByRole('button', { name: '다음' }));
    }
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '이전' }));
    expect(screen.getByRole('status', { name: '2 / 3 페이지' })).toBeInTheDocument();
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '다른 영화 추천받기' })).toBeInTheDocument();
  });

  it.each([1, 2, 3])('%i편이면 불필요한 페이지 이동을 보이지 않는다', async (count) => {
    vi.mocked(getPartyRecommendations).mockResolvedValue(recommendations(count));
    renderPage();
    await screen.findByText(`우리에게 어울리는 ${count}편`);
    expect(screen.queryByRole('navigation', { name: '추천 영화 페이지' })).toBeNull();
    expect(screen.getAllByRole('link', { name: /^추천 영화 / })).toHaveLength(count);
  });

  it('마지막 페이지가 1편이어도 누락하지 않고 상세로 이동한다', async () => {
    vi.mocked(getPartyRecommendations).mockResolvedValue(recommendations(4));
    renderPage();
    await screen.findByText('우리에게 어울리는 4편');
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getAllByRole('link', { name: /^추천 영화 / })).toHaveLength(1);
    fireEvent.click(screen.getByRole('link', { name: '추천 영화 4' }));
    expect(location()).toBe('/movies/9003');
  });

  it('헤더의 추천 이력은 기존 이력 화면을 연다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('link', { name: '추천 이력' }));
    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/recommendations/history`);
  });
});
