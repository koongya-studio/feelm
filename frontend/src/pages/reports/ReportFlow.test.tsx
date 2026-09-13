/*
 * 취향 리포트 — 이동 경로 (업무 S15P21E106-308)
 *
 * 같은 리포트를 두 길로 본다. 필름 쪽(2.1 → 3.3 → 3.4 ⇢ 3.1)과 MY 쪽(7.1 · 3.1 → 7.3 → 7.4 ⇢ 7.3)이다.
 * 둘 다 같은 상세 화면을 쓰므로 **`저장`이 어디로 돌아가는지**가 갈리는 자리다.
 *
 * 3.3은 들어오는 화살표가 없어 규칙을 정했다 — 안 본 리포트면 2.1에서 3.3을 거친다. 그 기억이
 * 세션 저장소에 남는지를 **두 번 들어가서** 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import FilmPage from '../film/FilmPage';
import HomePage from '../home/HomePage';
import MyDashboardPage from '../my/MyDashboardPage';
import ReportDetailPage from './ReportDetailPage';
import ReportIssuedPage from './ReportIssuedPage';
import ReportListPage from './ReportListPage';
import { getMyProfile } from '../../api/users';
import { resetSessionStore } from '../../mocks/sessionStore';
import { mockMyProfile } from '../../mocks/user';
import { ROUTE_PATTERN } from '../../routes/paths';

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path={ROUTE_PATTERN.home} element={<HomePage />} />
        <Route path={ROUTE_PATTERN.film} element={<FilmPage />} />
        <Route path={ROUTE_PATTERN.my} element={<MyDashboardPage />} />
        <Route path={ROUTE_PATTERN.tasteReports} element={<ReportListPage />} />
        <Route path={ROUTE_PATTERN.myReport} element={<ReportDetailPage from="my" />} />
        <Route path={ROUTE_PATTERN.reportIssued} element={<ReportIssuedPage />} />
        <Route path={ROUTE_PATTERN.report} element={<ReportDetailPage from="film" />} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

describe('2.1 → 3.3 → 3.4 ⇢ 3.1', () => {
  it('처음에는 발급 화면을 거치고, 두 번째부터는 상세로 바로 간다', async () => {
    const first = renderApp([ROUTE_PATTERN.home]);

    fireEvent.click(await screen.findByRole('button', { name: '취향 리포트 보기' }));

    await waitFor(() => expect(location()).toBe('/reports/301/new'));
    expect(await screen.findByText('2026년 상반기 취향 필름을 인화했어요')).toBeInTheDocument();
    expect(screen.getByText('지난 반기보다 스릴러 취향이 12% 증가했어요')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '리포트 보기' }));

    await waitFor(() => expect(location()).toBe('/reports/301'));
    expect(
      await screen.findByRole('heading', { name: '2026 상반기 취향 리포트' }),
    ).toBeInTheDocument();

    // 3.4 ⇢ 3.1 저장 후 취향 필름으로 돌아가기
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(location()).toBe('/film');
    first.unmount();

    renderApp([ROUTE_PATTERN.home]);
    fireEvent.click(await screen.findByRole('button', { name: '취향 리포트 보기' }));

    await waitFor(() => expect(location()).toBe('/reports/301'));
  });

  it('상세에는 계약 밖의 값까지 와이어프레임대로 나온다', async () => {
    renderApp(['/reports/301']);

    expect(await screen.findByRole('progressbar', { name: '한국' })).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    // 4컷의 감독 칸과 감독 목록에 한 번씩
    expect(screen.getAllByText('크리스토퍼 놀란')).toHaveLength(2);
    expect(screen.getByText('계획형 캐릭터 영화 12편에 ★4 이상')).toBeInTheDocument();
    expect(screen.getByText('+4% 신규')).toBeInTheDocument();
    expect(screen.getByText('−6%')).toBeInTheDocument();
  });
});

describe('3.4 → 3.5', () => {
  it('공유 메뉴를 열고, 마치면 시트를 닫는다', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });

    try {
      renderApp(['/reports/301']);

      // 헤더 아이콘과 아래 버튼 둘 다 3.5를 연다. 아래 버튼을 쓴다
      const buttons = await screen.findAllByRole('button', { name: '공유하기' });
      fireEvent.click(buttons[buttons.length - 1]);

      const sheet = within(screen.getByRole('dialog', { name: '공유하기' }));
      fireEvent.click(sheet.getByRole('button', { name: '공유하기' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(share).toHaveBeenCalledWith(
        expect.objectContaining({ title: '2026 상반기 취향 리포트' }),
      );
    } finally {
      Reflect.deleteProperty(navigator, 'share');
    }
  });

  it('공유 메뉴가 없는 브라우저면 알리고, 이미지는 API-061로 만든다', async () => {
    renderApp(['/reports/301']);
    const [headerShare] = await screen.findAllByRole('button', { name: '공유하기' });
    fireEvent.click(headerShare);

    const sheet = within(screen.getByRole('dialog', { name: '공유하기' }));
    fireEvent.click(sheet.getByRole('button', { name: '공유하기' }));
    expect(sheet.getByRole('status')).toHaveTextContent(
      '이 브라우저에서는 공유 메뉴를 열 수 없어요',
    );

    fireEvent.click(sheet.getByRole('button', { name: '이미지로 저장' }));
    await waitFor(() => expect(sheet.getByRole('status')).toHaveTextContent('이미지를 만들었어요'));
  });
});

describe('7.1 · 3.1 → 7.3 → 7.4 ⇢ 7.3', () => {
  it('MY에서 목록으로 가서 상세를 열고, 저장하면 목록으로 돌아온다', async () => {
    renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await screen.findByRole('link', { name: '취향 리포트' }));
    expect(location()).toBe('/my/reports');

    // 만드는 중인 반기는 누를 수 없다
    expect(await screen.findByText('만드는 중')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '2026 하반기 취향 리포트' })).toBeNull();

    fireEvent.click(screen.getByRole('link', { name: '2025 하반기 취향 리포트' }));
    expect(location()).toBe('/my/reports/202');
    expect(
      await screen.findByRole('heading', { name: '2025 하반기 취향 리포트' }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(location()).toBe('/my/reports'));
  });

  it('3.1의 취향 리포트 보기도 7.3으로 간다', async () => {
    renderApp([ROUTE_PATTERN.film]);

    fireEvent.click(await screen.findByRole('button', { name: '취향 리포트 보기' }));
    expect(location()).toBe('/my/reports');
  });

  it('첫 리포트에는 지난 반기 비교가 없다', async () => {
    renderApp(['/my/reports/201']);

    await screen.findByRole('heading', { name: '2025 상반기 취향 리포트' });
    expect(screen.queryByText('지난 반기와 비교')).toBeNull();
  });

  it('만드는 중인 리포트를 주소로 열면 안내한다', async () => {
    // API-060 `409 REPORT_GENERATING`
    renderApp(['/my/reports/302']);

    expect(await screen.findByText('리포트를 불러오지 못했어요')).toBeInTheDocument();
  });
});
