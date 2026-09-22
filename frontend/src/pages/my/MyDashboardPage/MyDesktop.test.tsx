/*
 * 7.1 MY — 데스크톱 시안 (업무 S15P21E106-786)
 *
 * 넓은 화면에서 프로필이 밝은 면의 카드가 되고, 취향 키워드 · 팝콘 분포도 · 최근 활동이
 * 우측 보조 패널로 가는지를 고정한다. 셸이 그 자리를 내놓아야 페이지가 포털로 보낼 수
 * 있으므로(업무 -782) 화면만이 아니라 셸까지 함께 그린다.
 *
 * **동작은 배치가 바뀌어도 그대로다.** 탭 전환과 `더 보기`가 넓은 화면에서도 같은 API를
 * 같은 조건으로 부르는지 함께 본다 — 이 업무는 배치만 바꾼다.
 *
 * **412px은 한 줄도 바뀌지 않는다.** 그 회귀는 `MyDashboardPage.test.tsx`가 이미 지키고
 * 있고, 여기서는 좁은 폭에서 데스크톱 전용 요소가 새지 않는지만 확인한다.
 *
 * jsdom에는 레이아웃이 없어 열 수 · 겹침은 여기서 잡히지 않는다. 그것은 브라우저 확인
 * 기록(`frontend/verification/S15P21E106-786.md`)에 있다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import MyDashboardPage from './index';
import AppLayout from '../../../layouts/AppLayout';
import { AuthContext } from '../../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import { getOtts } from '../../../api/otts';
import { getRecommendationHistory } from '../../../api/recommendations';
import { getMyReports, getReportDetail } from '../../../api/reports';
import { getPopcornBucket } from '../../../api/taste';
import { getMyProfile, getMyProfileView } from '../../../api/users';
import { getMyRatings, getWatchRecords } from '../../../api/watch';
import { getUnreadNotificationCount } from '../../../api/notifications';
import type { AuthValue } from '../../../contexts/authContext';
import type { PopcornBucket } from '../../../types/taste';
import type { ProfileView } from '../../../types/unspecified';

vi.mock('../../../api/users', () => ({
  getMyProfileView: vi.fn(),
  getMyProfile: vi.fn(),
}));

vi.mock('../../../api/taste', () => ({
  getPopcornBucket: vi.fn(),
}));

vi.mock('../../../api/reports', () => ({
  getMyReports: vi.fn(),
  getReportDetail: vi.fn(),
}));

vi.mock('../../../api/watch', () => ({
  getMyRatings: vi.fn(),
  getWatchRecords: vi.fn(),
}));

vi.mock('../../../api/recommendations', () => ({
  getRecommendationHistory: vi.fn(),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

vi.mock('../../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

const profile: ProfileView = {
  userId: 7,
  nickname: '김난다',
  avatarUrl: null,
  bio: '좋아하는 영화를 곁에 두고',
  stats: { frameCount: 73, popcornCount: 58, publicReviewCount: 4 },
  keywords: [
    { code: 'K1', label: '몰입되는', rationale: '', isPublic: true },
    { code: 'K2', label: '숨은 키워드', rationale: '', isPublic: false },
  ],
  frames: [],
  popcorn: [],
  reviews: [],
};

const bucket: PopcornBucket = {
  totalCount: 100,
  flavors: [
    {
      flavorCode: 'FLAVOR_A',
      displayName: '액션 · 모험',
      color: '#fbf1cf',
      popcornCount: 28,
      averageScore: 4.4,
      discoveredAt: null,
    },
    {
      flavorCode: 'FLAVOR_B',
      displayName: '아직 안 먹은 맛',
      color: '#fbf1cf',
      popcornCount: 0,
      averageScore: null,
      discoveredAt: null,
    },
  ],
};

const ratings = {
  items: [
    {
      itemType: 'RATED' as const,
      ratingId: 901,
      movie: { movieId: 101, title: '인 셉 션', posterUrl: null },
      score: 5,
      comment: null,
      visibility: null,
      spoiler: false,
      frameId: 501,
      createdAt: '2024-05-12T09:00:00Z',
      activityAt: '2024-05-12T09:00:00Z',
    },
  ],
  nextCursor: 'c1',
  hasNext: true,
};

function renderMy(width: number) {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/my']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/my" element={<MyDashboardPage />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

beforeEach(() => {
  vi.mocked(getMyProfileView).mockReset().mockResolvedValue(profile);
  // 데스크톱 셸의 상단 바가 부른다(업무 -782). 이 화면의 확인 대상은 아니다
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('쓰지 않는다'));
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue(bucket);
  vi.mocked(getMyReports)
    .mockReset()
    .mockResolvedValue({
      items: [
        {
          reportId: 4,
          period: { year: 2024, half: 'H1' as const },
          status: 'READY',
          title: '2024 상반기',
          createdAt: '2024-03-28T09:00:00Z',
        },
      ],
      current: null,
      preparing: false,
    });
  vi.mocked(getReportDetail).mockReset().mockRejectedValue(new Error('이 테스트는 쓰지 않는다'));
  vi.mocked(getMyRatings).mockReset().mockResolvedValue(ratings);
  vi.mocked(getWatchRecords)
    .mockReset()
    .mockResolvedValue({ items: [], nextCursor: null, hasNext: false });
  vi.mocked(getRecommendationHistory)
    .mockReset()
    .mockResolvedValue({ items: [], nextCursor: null, hasNext: false });
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
});

afterEach(() => {
  resetViewport();
});

describe('7.1 MY 데스크톱 시안', () => {
  it('화면 제목과 설정 진입점이 본문 머리에 선다', async () => {
    renderMy(1440);

    // 프로필을 받기 전에는 자리만 있다. 본문이 선 뒤에 제목을 찾는다
    await screen.findByRole('main');

    expect(await screen.findByRole('heading', { level: 1, name: '프로필' })).toBeInTheDocument();
    // 좌측 내비에도 `설정`이 있으므로(업무 -782) 본문 안으로 좁힌다
    const main = screen.getByRole('main');

    expect(within(main).getByRole('link', { name: '설정' })).toHaveAttribute(
      'href',
      '/my/settings',
    );
  });

  it('우측 보조 패널이 취향 키워드 · 팝콘 분포도 · 최근 활동을 말한다', async () => {
    renderMy(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    await waitFor(() =>
      expect(within(aside).getByRole('heading', { name: '나의 취향 키워드' })).toBeInTheDocument(),
    );
    // 공개로 둔 키워드만 나온다
    expect(within(aside).getByText('몰입되는')).toBeInTheDocument();
    expect(within(aside).queryByText('숨은 키워드')).not.toBeInTheDocument();

    expect(within(aside).getByRole('heading', { name: '팝콘 분포도' })).toBeInTheDocument();
    expect(within(aside).getByText('액션 · 모험')).toBeInTheDocument();
    // 감상량은 알 수의 비율, 선호도는 평균 별점 그대로다(업무 -783과 같은 결정)
    expect(within(aside).getByText('28%')).toBeInTheDocument();
    expect(within(aside).getByText('★ 4.4')).toBeInTheDocument();
    // 0알인 맛은 아직 발견하지 않은 맛이라 줄을 세우지 않는다
    expect(within(aside).queryByText('아직 안 먹은 맛')).not.toBeInTheDocument();
  });

  it('최근 활동을 이미 받아 둔 평가 · 리포트로 조립한다', async () => {
    renderMy(1440);

    const aside = await screen.findByRole('complementary', { name: '보조 정보' });

    await waitFor(() =>
      expect(within(aside).getByRole('heading', { name: '최근 활동' })).toBeInTheDocument(),
    );
    // 최신순이다 — 5월 평가가 3월 리포트보다 앞이다. 키워드 칩도 목록이라 카드 안으로 좁힌다
    const card = within(aside).getByRole('heading', { name: '최근 활동' }).closest('section')!;
    const rows = within(card as HTMLElement).getAllByRole('listitem');

    expect(rows[0]).toHaveTextContent('영화 평가를 남겼어요');
    expect(rows[0]).toHaveTextContent('인 셉 션');
    expect(rows[1]).toHaveTextContent('취향 리포트가 만들어졌어요');

    // 활동 로그 API는 없다. 새로 부르는 것이 없어야 한다
    expect(getMyRatings).toHaveBeenCalledTimes(1);
    expect(getMyReports).toHaveBeenCalledTimes(1);
  });

  it('피드 칸에 평가한 날과 제목을 함께 적는다', async () => {
    renderMy(1440);

    // 최근 활동 줄에도 같은 제목이 있으므로 피드 안으로 좁힌다
    const feed = await screen.findByRole('tabpanel');
    const cell = within(feed).getByRole('link', { name: /인 셉 션/ });

    // 피드는 여러 해에 걸쳐 있어 넓은 화면에서는 해까지 적는다
    expect(within(cell).getByText('2024. 5. 12')).toBeInTheDocument();
    expect(within(cell).getByText('인 셉 션')).toBeInTheDocument();
  });

  it('탭과 `더 보기`가 기존 API 페이지 상태를 그대로 쓴다', async () => {
    renderMy(1440);

    expect(await screen.findByRole('tab', { name: '평가한 영화' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(getMyRatings).toHaveBeenCalledWith({ size: 18 });

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));

    await waitFor(() => expect(getMyRatings).toHaveBeenCalledWith({ cursor: 'c1', size: 18 }));
  });

  it('412px에서는 데스크톱 시안의 요소가 나오지 않는다', async () => {
    renderMy(412);

    expect(await screen.findByRole('tab', { name: '평가한 영화' })).toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: '보조 정보' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '내 기록' })).not.toBeInTheDocument();
    expect(screen.queryByText('나의 영화 취향을 한눈에')).not.toBeInTheDocument();
    // 좁은 화면의 칸은 포스터와 별점뿐이다
    expect(screen.queryByText('2024. 5. 12')).not.toBeInTheDocument();
  });
});
