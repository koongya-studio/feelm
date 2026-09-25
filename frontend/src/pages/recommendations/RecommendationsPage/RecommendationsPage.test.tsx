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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import RecommendationsPage from './index';
import { ApiError } from '../../../api/apiError';
import { renderWithRouter } from '../../../test/render';
import { AuthContext } from '../../../contexts/authContext';
import { RECOMMENDATIONS_GUIDE, hasSeenGuide, restartGuide } from '../../../utils/userGuides';
import type { AuthValue } from '../../../contexts/authContext';
import type { RecommendationSet } from '../../../types/recommendation';

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
  vi.mocked(getRecommendations).mockReset().mockResolvedValue(set);
  vi.mocked(dismissMovie).mockReset().mockResolvedValue(undefined);
  vi.mocked(getOtts)
    .mockReset()
    .mockResolvedValue([{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }]);
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

  it('추천을 준비 중이면 없는 카드를 가리키지 않고 검색으로 보낸다', async () => {
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

    expect(await screen.findByText('추천을 준비하고 있어요')).toBeInTheDocument();
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
    const feedback = screen.getByRole('region', { name: '오늘의 추천 의견' });
    expect(lastCard.compareDocumentPosition(feedback)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('빈 세트는 목업으로 채우지 않고 빈 상태를 말한다', async () => {
    vi.mocked(getRecommendations).mockResolvedValue({ ...set, items: [] });
    renderWithRouter(<RecommendationsPage />, { route: '/recommendations' });

    expect(await screen.findByText('추천할 영화를 아직 찾지 못했어요')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /상세 보기/ })).toBeNull();
    expect(screen.queryByRole('region', { name: '오늘의 추천 의견' })).toBeNull();
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
