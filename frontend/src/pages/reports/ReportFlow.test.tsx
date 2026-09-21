/*
 * 취향 리포트 — 이동 경로 (업무 S15P21E106-308)
 *
 * 같은 리포트를 두 길로 본다. 필름 쪽(3.1 → 3.3 → 3.4 ⇢ 3.1)과 MY 쪽(7.1 · 3.1 → 7.3 → 7.4 ⇢ 7.3)이다.
 * 둘 다 같은 상세 화면을 쓰므로 **`저장`이 어디로 돌아가는지**가 갈리는 자리다.
 *
 * 3.3은 들어오는 화살표가 없어 규칙을 정했다 — 아직 뽑지 않은 리포트가 있으면 3.1의 버튼이
 * 3.3으로 간다. 홈을 없애면서(업무 -461) 그 출발점이 2.1에서 3.1로 옮겨왔다.
 *
 * 목록 · 상세 · 첫 발급은 실제 API다(업무 -558). 응답은 `test/reports.ts`에서 온다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import FilmPage from '../film/FilmPage';
import MyDashboardPage from '../my/MyDashboardPage';
import ReportDetailPage from './ReportDetailPage';
import ReportIssuedPage from './ReportIssuedPage';
import ReportListPage from './ReportListPage';
import { getMyProfile, getMyProfileView } from '../../api/users';
import { mockPopcornBucket } from '../../mocks/popcornBucket';
import { resetSessionStore } from '../../mocks/sessionStore';
import { mockMyProfileView } from '../../mocks/publicProfiles';
import { mockMyProfile } from '../../mocks/user';
import { ROUTE_PATTERN } from '../../routes/paths';
import {
  reportDetails,
  reportList,
  reportListAfterIssue,
  reportListBeforeIssue,
} from '../../test/reports';
import { ApiError } from '../../api/apiError';
import type { ReportList } from '../../types/report';
import type { FrameList } from '../../types/taste';

/*
 * 이 파일은 필름·MY·리포트 화면을 한 흐름으로 렌더하고 비동기 이동을 실제 순서로 기다린다.
 * 가짜 타이머로 화면 사이의 Promise와 이벤트 순서를 바꾸지 않고, 전체 실행 부하만 견디도록
 * 이 흐름 파일에 한해 Vitest의 기본 5초 상한을 늘린다.
 */
vi.setConfig({ testTimeout: 15000 });

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
  // 7.1 프로필 머리(업무 -616)
  getMyProfileView: vi.fn(),
}));

/*
 * 3.1과 7.1이 부르는 필름 · 팝콘은 실제 API다(업무 -392). 가로채지 않으면 화면이
 * 실패 상태로 떨어져 리포트 버튼 자체가 나오지 않는다.
 */
vi.mock('../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/taste')>()),
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
}));

const { getMyFrames, getPopcornBucket } = await import('../../api/taste');

/*
 * 리포트는 실제 API다(API-059 · 060 · 080). 화면 흐름만 보는 파일이므로 HTTP 대신 도메인
 * 함수를 가로챈다. 계약 경로 · 본문과 `getLatestReadyReport`의 발급 판정은
 * `api/reports.test.ts`가 응답으로 고정한다.
 */
vi.mock('../../api/reports', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/reports')>()),
  getLatestReadyReport: vi.fn(),
  getMyReports: vi.fn(),
  getReportDetail: vi.fn(),
  issueReport: vi.fn(),
}));

const { getLatestReadyReport, getMyReports, getReportDetail, issueReport } =
  await import('../../api/reports');

/** 7.3 목록이 읽는 API-059 응답을 바꿔 끼운다 */
function serveList(list: ReportList) {
  vi.mocked(getMyReports).mockResolvedValue(list);
}

/** 3.1의 본문이 나오려면 프레임이 하나는 있어야 한다. 0개면 빈 상태 화면이다 */
const frames: FrameList = {
  totalCount: 1,
  items: [
    {
      frameId: 501,
      movie: { movieId: 401, title: '영화 제목 1', posterUrl: null },
      score: 4.5,
      flavorCode: 'FLAVOR_D',
      createdAt: '2026-09-06T12:30:00Z',
    },
  ],
  nextCursor: null,
  hasNext: false,
};

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getMyProfileView).mockReset().mockResolvedValue(mockMyProfileView);
  vi.mocked(getMyFrames).mockReset().mockResolvedValue(frames);
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue(mockPopcornBucket);

  vi.mocked(getMyReports).mockReset();
  serveList(reportListBeforeIssue);
  /*
   * 3.1의 버튼은 `getLatestReadyReport`가 가른다. 이 시험은 두 상태를 직접 세우고, 발급
   * 시각으로 상태를 가르는 규칙 자체는 `api/reports.test.ts`가 고정한다.
   */
  vi.mocked(getLatestReadyReport)
    .mockReset()
    .mockResolvedValue({ reportId: 301, needsIntro: true });
  vi.mocked(issueReport)
    .mockReset()
    .mockImplementation((reportId: number) =>
      Promise.resolve({ reportId, issuedAt: '2026-07-01T00:00:14Z' }),
    );
  vi.mocked(getReportDetail)
    .mockReset()
    .mockImplementation((reportId: number) => {
      const detail = reportDetails[reportId];

      // 아직 만드는 중인 반기는 상세가 409다(API-060)
      return detail
        ? Promise.resolve(detail)
        : Promise.reject(new ApiError(409, 'REPORT_GENERATING', '리포트를 만드는 중입니다.'));
    });
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
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

describe('3.1 → 3.3 → 3.4 ⇢ 3.1', () => {
  it('안 본 리포트가 있으면 발급 화면을 거쳐 상세로 간다', async () => {
    renderApp([ROUTE_PATTERN.film]);

    fireEvent.click(await screen.findByRole('button', { name: '새 취향 리포트 받기' }));

    await waitFor(() => expect(location()).toBe('/reports/301/new'));
    expect(await screen.findByText('2026년 상반기 취향 필름을 인화했어요')).toBeInTheDocument();
    expect(screen.getByText('지난 반기보다 스릴러 취향이 12% 증가했어요')).toBeInTheDocument();

    // 인화 연출은 건너뛸 수 있고, 건너뛰면 버튼이 사라진다
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));
    expect(screen.queryByRole('button', { name: '건너뛰기' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '리포트 보기' }));

    await waitFor(() => expect(location()).toBe('/reports/301'));
    expect(
      await screen.findByRole('heading', { name: '2026 상반기 취향 리포트' }),
    ).toBeInTheDocument();

    // 3.4 ⇢ 3.1 저장 후 취향 필름으로 돌아가기
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(location()).toBe('/film');
  });

  it('상세에 국가 비율과 지난 반기 비교가 나오고, 키워드 칸은 숨는다', async () => {
    renderApp(['/reports/301']);

    expect(await screen.findByRole('progressbar', { name: '한국' })).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    // 4컷의 감독 칸과 감독 목록에 한 번씩
    expect(screen.getAllByText('웨스 앤더슨')).toHaveLength(2);
    expect(screen.getByText('+4% 신규')).toBeInTheDocument();
    expect(screen.getByText('−6%')).toBeInTheDocument();
    // 취향 키워드는 계약이 빈 배열을 주는 후속 범위다(API-060)
    expect(screen.queryByText('취향 키워드')).toBeNull();
  });

  it('캐릭터가 없는 리포트는 설명을 숨기고 4컷의 04 칸만 준비 중으로 둔다', async () => {
    // 선정 기준이 정해질 때까지 characterCard · fourCut.character가 null로 온다(업무 -618)
    renderApp(['/reports/301']);

    expect(await screen.findByText('04 캐릭터')).toBeInTheDocument();
    expect(screen.getByText('준비 중')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '캐릭터' })).toBeNull();
    // 나머지 칸과 상세는 그대로 그린다
    expect(screen.getByText('01 장르')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '감독' })).toBeInTheDocument();
  });

  /*
   * API-080 실패는 성공으로 처리하지 않는다(계약 처리 규칙). 3.3에 머물러 문구를 보이고
   * 같은 버튼이 `다시 시도`가 된다. 오류마다 문구가 다르다.
   */
  it.each([
    [
      '아직 만드는 중',
      new ApiError(409, 'REPORT_GENERATING', '리포트를 만드는 중입니다.'),
      '리포트를 아직 만드는 중이에요. 잠시 후 다시 시도해 주세요.',
    ],
    [
      '발급 창이 지남',
      new ApiError(409, 'CONFLICT', '발급할 수 없습니다.'),
      '지금은 이 리포트를 뽑을 수 없어요. 취향 4컷 목록에서 확인해 주세요.',
    ],
    [
      '네트워크 오류',
      new ApiError(0, 'NETWORK_ERROR', '네트워크 오류'),
      '발급을 기록하지 못했어요. 잠시 후 다시 시도해 주세요.',
    ],
  ])(
    '발급 기록이 실패하면(%s) 넘어가지 않고 안내와 다시 시도를 보인다',
    async (_, error, message) => {
      vi.mocked(issueReport).mockRejectedValueOnce(error);
      renderApp(['/reports/301/new']);

      fireEvent.click(await screen.findByRole('button', { name: '리포트 보기' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(message);
      expect(location()).toBe('/reports/301/new');

      // 같은 자리에서 다시 누르면 이번엔 기록되어 3.4로 간다
      const retry = screen.getByRole('button', { name: '다시 시도' });
      expect(retry).toBeEnabled();
      fireEvent.click(retry);

      await waitFor(() => expect(location()).toBe('/reports/301'));
      expect(issueReport).toHaveBeenCalledTimes(2);
    },
  );
});

describe('3.4 → 3.5', () => {
  it('휴대폰에서는 공유 메뉴를 열고, 마치면 시트를 닫는다', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    // 기기의 공유 메뉴는 손가락이 주 입력인 기기에서만 연다(업무 -767, `utils/share.ts`)
    const coarse = vi
      .spyOn(window, 'matchMedia')
      .mockImplementation((query: string) => ({ matches: true, media: query }) as MediaQueryList);

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
      coarse.mockRestore();
    }
  });

  it('공유도 복사도 못 하는 브라우저면 알리고, 이미지는 API-061로 만든다', async () => {
    renderApp(['/reports/301']);
    const [headerShare] = await screen.findAllByRole('button', { name: '공유하기' });
    fireEvent.click(headerShare);

    const sheet = within(screen.getByRole('dialog', { name: '공유하기' }));
    fireEvent.click(sheet.getByRole('button', { name: '공유하기' }));
    // jsdom에는 클립보드가 없다. 조용히 끝내지 않고 알린다
    await waitFor(() =>
      expect(sheet.getByRole('status')).toHaveTextContent('이 브라우저에서는 공유할 수 없어요'),
    );

    fireEvent.click(sheet.getByRole('button', { name: '이미지로 저장' }));
    await waitFor(() => expect(sheet.getByRole('status')).toHaveTextContent('이미지를 만들었어요'));
  });
});

describe('7.1 · 3.1 → 7.3 → 7.4 ⇢ 7.3', () => {
  /*
   * 7.1 MY의 취향 리포트 입구는 **지난 반기 리포트를 찾아보러 가는 유일한 길이다**
   * (사용자 결정 2026-09-16, 업무 -459). 홈을 없앤 뒤(업무 -461) 3.1은 상태에 따라
   * 3.3 발급으로 가므로 목록의 입구로 대신할 수 없다.
   *
   * 같은 날 시안으로 배치가 바뀌면서 메뉴 줄이 아니라 **`4컷 리포트` 그림**이 됐다.
   * 이름만 바뀌었고 목적지는 그대로다.
   */
  it('MY에서 목록으로 가서 상세를 열고, 저장하면 목록으로 돌아온다', async () => {
    // 인화 중인 반기는 `items`가 아니라 `current`로 온다(API-059)
    serveList(reportList);
    renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await screen.findByRole('link', { name: '4컷 리포트' }));
    expect(location()).toBe('/my/reports');

    // 연도마다 반기 4컷을 붙인다. 만드는 중인 반기는 빈 인화지이고 누를 수 없다
    expect(await screen.findByRole('heading', { name: '2025' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '취향 4컷' })).toBeInTheDocument();
    expect(screen.getByText('인화 중')).toBeInTheDocument();
    expect(screen.getByText('만드는 중')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '2026 하반기 취향 리포트' })).toBeNull();

    fireEvent.click(screen.getByRole('link', { name: '2025 하반기 취향 리포트' }));
    expect(location()).toBe('/my/reports/202');
    expect(
      await screen.findByRole('heading', { name: '2025 하반기 취향 리포트' }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(location()).toBe('/my/reports'));
  });

  it('3.1의 리포트 버튼은 하나다. 새 리포트를 받기 전에는 3.3, 받은 뒤에는 7.3으로 간다', async () => {
    const first = renderApp([ROUTE_PATTERN.film]);

    fireEvent.click(await screen.findByRole('button', { name: '새 취향 리포트 받기' }));
    expect(screen.queryByRole('button', { name: '취향 리포트 보기' })).toBeNull();
    await waitFor(() => expect(location()).toBe('/reports/301/new'));

    fireEvent.click(await screen.findByRole('button', { name: '리포트 보기' }));
    await waitFor(() => expect(location()).toBe('/reports/301'));
    expect(issueReport).toHaveBeenCalledWith(301);
    first.unmount();

    // 발급을 기록한 뒤에는 `current.issuedAt`이 차서 3.1이 목록으로 보낸다
    serveList(reportListAfterIssue);
    vi.mocked(getLatestReadyReport).mockResolvedValue(null);
    renderApp([ROUTE_PATTERN.film]);

    fireEvent.click(await screen.findByRole('button', { name: '취향 리포트 보기' }));
    expect(screen.queryByRole('button', { name: '새 취향 리포트 받기' })).toBeNull();
    expect(location()).toBe('/my/reports');
  });

  it('첫 리포트에는 지난 반기 비교가 없다', async () => {
    renderApp(['/my/reports/201']);

    await screen.findByRole('heading', { name: '2025 상반기 취향 리포트' });
    expect(screen.queryByText('지난 반기와 비교')).toBeNull();
  });

  it('최신 반기를 준비하는 중이고 공개된 리포트가 없으면 인화 중이라고 알린다', async () => {
    // API-059 `preparing`. 첫 반기라 목록이 비어 있다
    serveList({ items: [], current: null, preparing: true });
    renderApp([ROUTE_PATTERN.tasteReports]);

    expect(await screen.findByText('취향 4컷을 인화하는 중이에요')).toBeInTheDocument();
    expect(screen.getByText('반기 집계가 끝나면 알려 드려요')).toBeInTheDocument();
  });

  it('준비 중도 아니고 리포트도 없으면 반기마다 인화한다고 알린다', async () => {
    serveList({ items: [], current: null, preparing: false });
    renderApp([ROUTE_PATTERN.tasteReports]);

    expect(await screen.findByText('아직 인화된 취향 4컷이 없어요')).toBeInTheDocument();
  });

  /*
   * 업무 -767. 한때 7.3의 `공유하기`는 공유 메뉴가 없는 브라우저에서 숨었고, 데스크톱 Chrome처럼
   * 메뉴가 있다고 답하는 곳에서는 눌러도 아무 일이 없었다.
   */
  it('인화된 4컷이 없으면 공유 버튼이 잠겨 있다', async () => {
    serveList({ items: [], current: null, preparing: true });
    renderApp([ROUTE_PATTERN.tasteReports]);

    await screen.findByText('취향 4컷을 인화하는 중이에요');
    expect(screen.getByRole('button', { name: '공유하기' })).toBeDisabled();
  });

  // 인화 중인 반기는 목록에 한 장으로 그려지지만 공유할 4컷은 아니다(!539 리뷰)
  it('인화 중인 반기만 있어도 공유 버튼이 잠겨 있다', async () => {
    serveList({ items: [], current: reportList.current, preparing: false });
    renderApp([ROUTE_PATTERN.tasteReports]);

    expect(await screen.findByText('인화 중')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '공유하기' })).toBeDisabled();
  });

  it('데스크톱에서 공유하면 주소를 복사하고 알린다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    try {
      serveList(reportList);
      renderApp([ROUTE_PATTERN.tasteReports]);

      await screen.findByRole('heading', { name: '2025' });
      fireEvent.click(screen.getByRole('button', { name: '공유하기' }));

      expect(await screen.findByRole('status')).toHaveTextContent('취향 4컷 주소를 복사했어요');
      expect(writeText).toHaveBeenCalledWith(window.location.href);
    } finally {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
  });

  it('만드는 중인 리포트를 주소로 열면 안내한다', async () => {
    // API-060 `409 REPORT_GENERATING`
    renderApp(['/my/reports/302']);

    expect(await screen.findByText('리포트를 불러오지 못했어요')).toBeInTheDocument();
  });
});
