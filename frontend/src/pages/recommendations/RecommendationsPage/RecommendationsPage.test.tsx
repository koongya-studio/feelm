/*
 * 2.2 오늘의 추천 — 카드는 서버 영화다 (업무 S15P21E106-610)
 *
 * 목업 카드가 목업 영화 번호(101~104)로 실제 영화 상세를 열어 전혀 다른 영화가 나오던
 * 문제를 없앴다. 카드는 API-045 응답 그대로이고, 포스터와 `영화 상세 보기`는 그 서버
 * 영화의 번호로 간다.
 *
 * 관심없음(API-047)은 카드 오른쪽 위 버튼이다(업무 S15P21E106-672). 서버가
 * 받은 뒤에만 카드를 빼고 API-045를 다시 불러 보충한다. 섹션 이름표와 `안 봄` 배지는 없고,
 * 취향 발견 카드에만 `새로운 맛` 배지가 있다(같은 업무).
 *
 * 추천 후보가 아직 없는 `503 RECOMMENDATION_UNAVAILABLE`은 장애가 아니라 준비 중으로
 * 안내한다(업무 S15P21E106-654). 그 밖의 실패만 2.2-A 문구를 쓴다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import RecommendationsPage from './index';
import { ApiError } from '../../../api/apiError';
import { renderWithRouter, withRouter } from '../../../test/render';
import { AuthContext } from '../../../contexts/authContext';
import { RECOMMENDATIONS_GUIDE, hasSeenGuide, restartGuide } from '../../../utils/userGuides';
import type { AuthValue } from '../../../contexts/authContext';
import { useShellSize } from '../../../hooks/useShellSize';
import type { RecommendationSet } from '../../../types/recommendation';

vi.mock('../../../hooks/useShellSize', () => ({ useShellSize: vi.fn(() => 'mobile') }));

vi.mock('../../../api/recommendations', () => ({
  getRecommendations: vi.fn(),
  dismissMovie: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

const { dismissMovie, getRecommendations } = await import('../../../api/recommendations');
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
  localStorage.clear();
  vi.mocked(useShellSize).mockReturnValue('mobile');
  vi.mocked(getRecommendations).mockReset().mockResolvedValue(set);
  vi.mocked(dismissMovie).mockReset().mockResolvedValue(undefined);
  vi.mocked(getOtts)
    .mockReset()
    .mockResolvedValue([{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }]);
});

afterEach(() => {
  vi.useRealTimers();
});

const guideAuth: AuthValue = {
  status: 'authenticated',
  user: { userId: 42, nickname: '새 사용자', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

describe('첫 이용 안내', () => {
  it('추천 카드가 있을 때 실제 포스터를 안내하고 건너뛰기를 저장한다', async () => {
    restartGuide(42, RECOMMENDATIONS_GUIDE);
    renderWithRouter(
      <AuthContext.Provider value={guideAuth}>
        <RecommendationsPage />
      </AuthContext.Provider>,
      { route: '/recommendations' },
    );

    expect(await screen.findByText('추천 영화가 도착했어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '파이트 클럽 상세 보기' })).toHaveAttribute(
      'data-guide-target',
      'recommendation',
    );
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));
    expect(hasSeenGuide(42, RECOMMENDATIONS_GUIDE)).toBe(true);
  });

  it('추천이 지연되면 없는 카드를 가리키지 않고 검색으로 보낸다', async () => {
    vi.useFakeTimers();
    restartGuide(42, RECOMMENDATIONS_GUIDE);
    vi.mocked(getRecommendations).mockRejectedValue(
      new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '준비 중'),
    );
    renderWithRouter(
      <AuthContext.Provider value={guideAuth}>
        <RecommendationsPage />
      </AuthContext.Provider>,
      { route: '/recommendations' },
    );

    await act(async () => {});
    for (let i = 0; i < 15; i += 1)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000);
      });
    expect(screen.getByText('추천을 준비하고 있어요')).toBeInTheDocument();
    expect(screen.queryByText('추천 영화가 도착했어요')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '영화 검색하기' }));
    expect(hasSeenGuide(42, RECOMMENDATIONS_GUIDE)).toBe(true);
    expect(screen.getByTestId('location')).toHaveTextContent('/search');
  });
});

describe('2.2 오늘의 추천', () => {
  it('서버가 준 카드를 그리고 포스터는 그 영화의 상세로 간다', async () => {
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    const poster = await screen.findByRole('link', { name: '파이트 클럽 상세 보기' });
    expect(poster).toHaveAttribute('href', '/movies/550');
    expect(screen.getByText('파이트 클럽')).toBeInTheDocument();
    expect(screen.getByText('스릴러를 높게 평가하셨어요')).toBeInTheDocument();
    const ottLink = screen.getByRole('link', {
      name: '넷플릭스에서 파이트 클럽 보기 (새 창)',
    });
    expect(ottLink).toHaveAttribute(
      'href',
      'https://www.netflix.com/search?q=%ED%8C%8C%EC%9D%B4%ED%8A%B8%20%ED%81%B4%EB%9F%BD',
    );
    expect(ottLink).toHaveAttribute('target', '_blank');
    expect(poster).not.toContainElement(ottLink);
    // 어두운 정보 영역에서 22px 로고가 묻혀 한 단계 키웠다(업무 -917)
    expect(ottLink.firstElementChild).toHaveStyle('--ott-badge-size: 26px');
    // 로고는 제목 옆이 아니라 이유 아래 자기 줄에 선다. OTT가 없으면 그 자리를 글로 채운다
    expect(screen.getByText('파이트 클럽')).not.toContainElement(ottLink);
    expect(screen.getByRole('link', { name: '세븐 상세 보기' }).parentElement).toHaveTextContent(
      '제공 중인 OTT가 없어요',
    );
    // 제목 옆에 연도 · 대표 장르를 글로 적는다(업무 -874). 포스터 그림만으로 알아보지 않게 한다
    expect(screen.getByText('파이트 클럽').parentElement).toHaveTextContent(
      '파이트 클럽1999 · 드라마',
    );
    expect(getRecommendations).toHaveBeenCalledTimes(1);

    // 일치도 %는 계약에 없어 그리지 않는다(CR-13)
    expect(screen.queryByText(/일치 \d+%/)).toBeNull();
    // 섹션 이름표와 `안 봄`은 없고, 취향 발견 카드(세븐)에만 `새로운 맛`이 있다(업무 -672)
    expect(screen.queryByText('취향 맞춤 추천')).toBeNull();
    expect(screen.queryByText('취향 발견 추천')).toBeNull();
    expect(screen.queryByText('안 봄')).toBeNull();
    expect(screen.getAllByText('새로운 맛')).toHaveLength(1);
    expect(screen.getByRole('link', { name: '세븐 상세 보기' }).parentElement).toHaveTextContent(
      '새로운 맛',
    );
    // 고정 라벨 없이 API-045의 reason.summary를 그대로 보여 준다(업무 -822)
    expect(screen.queryByText('추천 이유:')).toBeNull();
    expect(screen.getByText('스릴러를 높게 평가하셨어요')).toBeInTheDocument();
    expect(screen.getByText('좋아하는 스릴러와 연결되는 느와르예요')).toBeInTheDocument();
    // 취향 맞춤이 먼저, 취향 발견이 뒤다
    const titles = screen
      .getAllByRole('link', { name: / 상세 보기$/ })
      .map((link) => link.getAttribute('aria-label'));
    expect(titles).toEqual(['파이트 클럽 상세 보기', '세븐 상세 보기']);
  });

  it('모바일 카드에는 OTT 메뉴 없이 관심없음과 상세 이동만 표시한다', async () => {
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    expect(await screen.findByRole('button', { name: '세븐 관심없음' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /OTT 메뉴/ })).toBeNull();
    expect(screen.getByRole('link', { name: '세븐 상세 보기' })).toHaveAttribute(
      'href',
      '/movies/807',
    );
    expect(screen.getByText('카드를 탭하면 영화 상세 화면으로 이동해요')).toBeInTheDocument();
    const lastCard = screen.getByRole('link', { name: '세븐 상세 보기' });
    const feedback = screen.getByRole('region', { name: '나를 위한 영화 추천 의견' });
    expect(lastCard.compareDocumentPosition(feedback)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('빈 세트는 목업으로 채우지 않고 빈 상태를 말한다', async () => {
    vi.mocked(getRecommendations).mockResolvedValue({ ...set, items: [] });
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    expect(await screen.findByText('추천할 영화를 아직 찾지 못했어요')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /상세 보기/ })).toBeNull();
    expect(screen.queryByRole('region', { name: '나를 위한 영화 추천 의견' })).toBeNull();
  });
});

describe('2.2-A 추천을 불러오지 못한 경우', () => {
  it.each(['RECOMMENDATION_UNAVAILABLE', 'RECOMMENDATION_PREPARING'])(
    '%s는 오류 아이콘 없이 기다렸다가 자동으로 추천을 보여준다',
    async (code) => {
      vi.useFakeTimers();
      vi.mocked(getRecommendations)
        .mockRejectedValueOnce(new ApiError(503, code, '준비 중'))
        .mockResolvedValue(set);
      renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });
      await act(async () => {});
      expect(screen.getByText('추천 영화를 준비하고 있어요')).toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'empty');
      expect(screen.queryByRole('button', { name: '다시 확인하기' })).toBeNull();
      expect(screen.getByRole('link', { name: '영화 검색하기' })).toHaveAttribute(
        'href',
        '/search',
      );
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000);
      });
      expect(screen.getByRole('link', { name: '파이트 클럽 상세 보기' })).toBeInTheDocument();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000);
      });
      expect(getRecommendations).toHaveBeenCalledTimes(2);
    },
  );

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

/*
 * 관심없음 (업무 -672 · -536)
 *
 * 서버가 받은 뒤에만 카드를 뺀다 — 화면에서만 지운 척하면 다음 조회에서 되살아난다.
 */
describe('2.2 관심없음', () => {
  /** 관심없음 뒤 API-045가 보충한 세트. 파이트 클럽이 빠지고 새 카드가 들어왔다 */
  const refilled: RecommendationSet = {
    ...set,
    items: [
      {
        ...set.items[0],
        recommendationItemId: 3303,
        movie: { ...set.items[0].movie, movieId: 680, title: '펄프 픽션' },
      },
      set.items[1],
    ],
  };

  it('카드 오른쪽 위 버튼이 API-047을 부르고, 그 카드를 빼 API-045가 보충한 카드로 채운다', async () => {
    vi.mocked(getRecommendations).mockResolvedValueOnce(set).mockResolvedValueOnce(refilled);
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    fireEvent.click(await screen.findByRole('button', { name: '파이트 클럽 관심없음' }));

    expect(dismissMovie).not.toHaveBeenCalled();
    expect(screen.getByRole('group', { name: '파이트 클럽 관심없음 확인' })).toHaveTextContent(
      '추천에서 제외할까요?',
    );
    fireEvent.click(screen.getByRole('button', { name: '예' }));

    expect(dismissMovie).toHaveBeenCalledWith(550, 3301);
    expect(await screen.findByRole('link', { name: '펄프 픽션 상세 보기' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '파이트 클럽 상세 보기' })).toBeNull();
    expect(getRecommendations).toHaveBeenCalledTimes(2);
  });

  it('확인 창에서 취소하면 추천을 변경하지 않는다', async () => {
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    fireEvent.click(await screen.findByRole('button', { name: '파이트 클럽 관심없음' }));
    fireEvent.click(screen.getByRole('button', { name: '아니요' }));

    expect(screen.queryByRole('group', { name: '파이트 클럽 관심없음 확인' })).toBeNull();
    expect(dismissMovie).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: '파이트 클럽 상세 보기' })).toBeInTheDocument();
  });

  it('이미 관심없음인 영화(409)도 처리된 것으로 보고 카드를 뺀다', async () => {
    vi.mocked(dismissMovie).mockRejectedValue(
      new ApiError(409, 'ALREADY_DISMISSED', '이미 관심없음 처리한 영화입니다.'),
    );
    vi.mocked(getRecommendations).mockResolvedValueOnce(set).mockResolvedValueOnce(refilled);
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    fireEvent.click(await screen.findByRole('button', { name: '파이트 클럽 관심없음' }));
    fireEvent.click(screen.getByRole('button', { name: '예' }));

    expect(await screen.findByRole('link', { name: '펄프 픽션 상세 보기' })).toBeInTheDocument();
    expect(screen.queryByText(/관심없음으로 처리하지 못했어요/)).toBeNull();
  });

  it('보충 조회가 실패해도 뺀 카드는 되살리지 않는다', async () => {
    vi.mocked(getRecommendations)
      .mockResolvedValueOnce(set)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '네트워크에 연결할 수 없습니다.'));
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    fireEvent.click(await screen.findByRole('button', { name: '파이트 클럽 관심없음' }));
    fireEvent.click(screen.getByRole('button', { name: '예' }));

    await waitFor(() =>
      expect(screen.queryByRole('link', { name: '파이트 클럽 상세 보기' })).toBeNull(),
    );
    expect(screen.getByRole('link', { name: '세븐 상세 보기' })).toBeInTheDocument();
  });

  it('관심없음을 처리하지 못하면 카드를 그대로 두고 알린다', async () => {
    vi.mocked(dismissMovie).mockRejectedValue(
      new ApiError(0, 'NETWORK_ERROR', '네트워크에 연결할 수 없습니다.'),
    );
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    fireEvent.click(await screen.findByRole('button', { name: '파이트 클럽 관심없음' }));
    fireEvent.click(screen.getByRole('button', { name: '예' }));

    expect(await screen.findByText(/관심없음으로 처리하지 못했어요/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '파이트 클럽 상세 보기' })).toBeInTheDocument();
    // 다시 누를 수 있다
    expect(screen.getByRole('button', { name: '파이트 클럽 관심없음' })).toBeEnabled();
    expect(getRecommendations).toHaveBeenCalledTimes(1);
  });
});

describe('첫 추천 자동 조회 수명', () => {
  function preparing() {
    vi.useFakeTimers();
    vi.mocked(getRecommendations).mockRejectedValue(
      new ApiError(503, 'RECOMMENDATION_PREPARING', '준비 중'),
    );
  }
  async function advanceRetries(count: number) {
    for (let i = 0; i < count; i += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000);
      });
    }
  }

  it('15회 후 자동 조회를 멈추고 수동 재시도에서 다시 시작한다', async () => {
    preparing();
    renderWithRouter(<RecommendationsPage />);
    await act(async () => {});
    await advanceRetries(15);
    expect(getRecommendations).toHaveBeenCalledTimes(16);
    expect(
      screen.getByText('추천 준비가 조금 늦어지고 있어요. 잠시 후 다시 확인해 주세요.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'empty');
    await advanceRetries(2);
    expect(getRecommendations).toHaveBeenCalledTimes(16);
    fireEvent.click(screen.getByRole('button', { name: '다시 확인하기' }));
    await act(async () => {});
    expect(getRecommendations).toHaveBeenCalledTimes(17);
    expect(screen.queryByRole('button', { name: '다시 확인하기' })).toBeNull();
    await advanceRetries(1);
    expect(getRecommendations).toHaveBeenCalledTimes(18);
  });

  it('일반 오류에서는 자동 조회하지 않는다', async () => {
    vi.useFakeTimers();
    vi.mocked(getRecommendations).mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', '오류'));
    renderWithRouter(<RecommendationsPage />);
    await act(async () => {});
    await advanceRetries(20);
    expect(getRecommendations).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'error');
  });

  it('화면 이탈 후 예약한 요청을 실행하지 않는다', async () => {
    preparing();
    const view = renderWithRouter(<RecommendationsPage />);
    await act(async () => {});
    view.unmount();
    await advanceRetries(2);
    expect(getRecommendations).toHaveBeenCalledTimes(1);
  });

  it('계정 변경 후 이전 응답을 버리고 새 계정의 준비 상태를 유지한다', async () => {
    preparing();
    let resolveOld!: (value: RecommendationSet) => void;
    vi.mocked(getRecommendations).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    const renderPage = (id: number) => (
      <AuthContext.Provider value={{ ...guideAuth, user: { ...guideAuth.user!, userId: id } }}>
        <RecommendationsPage />
      </AuthContext.Provider>
    );
    const view = renderWithRouter(renderPage(42));
    view.rerender(withRouter(renderPage(43)));
    await act(async () => {
      resolveOld(set);
    });
    expect(getRecommendations).toHaveBeenCalledTimes(2);
    expect(screen.queryByText('파이트 클럽')).toBeNull();
    await advanceRetries(1);
    expect(getRecommendations).toHaveBeenCalledTimes(3);
  });

  it('화면 편수 변경 시 이전 타이머를 정리하고 새 편수만 재조회한다', async () => {
    preparing();
    const view = renderWithRouter(<RecommendationsPage />);
    await act(async () => {});
    vi.mocked(useShellSize).mockReturnValue('desktop');
    view.rerender(withRouter(<RecommendationsPage />));
    await act(async () => {});
    expect(vi.mocked(getRecommendations).mock.calls.map(([size]) => size)).toEqual([3, 6]);
    await advanceRetries(1);
    expect(vi.mocked(getRecommendations).mock.calls.map(([size]) => size)).toEqual([3, 6, 6]);
  });
});
