/*
 * 취향 리포트 — 이동 경로 (업무 S15P21E106-308)
 *
 * 같은 리포트를 두 길로 본다. 필름 쪽(3.1 → 3.3 → 3.4)과 MY 쪽(7.1 · 3.1 → 7.3 → 7.4)이다.
 * 둘 다 같은 상세 화면을 쓰며 PNG 저장은 공유 시트에서만 제공한다.
 *
 * 3.3은 들어오는 화살표가 없어 규칙을 정했다 — 아직 뽑지 않은 리포트가 있으면 3.1의 버튼이
 * 3.3으로 간다. 홈을 없애면서(업무 -461) 그 출발점이 2.1에서 3.1로 옮겨왔다.
 *
 * 목록 · 상세 · 첫 발급은 실제 API다(업무 -558). 응답은 `test/reports.ts`에서 온다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import FilmPage from '../film/FilmPage';
import { AuthContext } from '../../contexts/authContext';
import { testAuth } from '../../test/auth';
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
  reportCharacterCard,
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

vi.mock('../../utils/reportImage', () => ({
  captureReportImage: vi.fn(),
  captureReportBackImage: vi.fn(),
  downloadReportImage: vi.fn(),
  downloadBlob: vi.fn(),
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
  createReportImage: vi.fn(),
}));

const { createReportImage, getLatestReadyReport, getMyReports, getReportDetail, issueReport } =
  await import('../../api/reports');
const { captureReportBackImage, captureReportImage, downloadBlob, downloadReportImage } =
  await import('../../utils/reportImage');

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
  window.localStorage.removeItem('feelm-report-flip-intro-v4:301');
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getMyProfileView).mockReset().mockResolvedValue(mockMyProfileView);
  vi.mocked(getMyFrames).mockReset().mockResolvedValue(frames);
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue(mockPopcornBucket);

  vi.mocked(getMyReports).mockReset();
  vi.mocked(captureReportImage)
    .mockReset()
    .mockResolvedValue(new Blob(['four-cut'], { type: 'image/png' }));
  vi.mocked(createReportImage).mockReset().mockResolvedValue({
    imageId: 901,
    format: 'PNG',
    downloadUrl: '/api/v1/report-images/901/download?expires=1&signature=x',
    expiresAt: '2026-07-01T00:10:00Z',
  });
  vi.mocked(captureReportBackImage)
    .mockReset()
    .mockResolvedValue(new Blob(['four-cut-back'], { type: 'image/png' }));
  vi.mocked(downloadReportImage).mockReset().mockResolvedValue(undefined);
  vi.mocked(downloadBlob).mockReset();
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

  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <AuthContext.Provider value={testAuth}>
      <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
        <Routes>
          <Route path={ROUTE_PATTERN.film} element={<FilmPage />} />
          <Route path={ROUTE_PATTERN.my} element={<MyDashboardPage />} />
          <Route path={ROUTE_PATTERN.tasteReports} element={<ReportListPage />} />
          <Route path={ROUTE_PATTERN.myReport} element={<ReportDetailPage />} />
          <Route path={ROUTE_PATTERN.reportIssued} element={<ReportIssuedPage />} />
          <Route path={ROUTE_PATTERN.report} element={<ReportDetailPage />} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const location = () => screen.getByTestId('location').textContent;

/*
 * 3.4 · 7.4의 설명은 4컷 칸 뒷면에 있다(업무 -812). 뒤집기 전에는 `visibility: hidden`이라
 * 역할 질의에 잡히지 않으므로, 뒷면을 보는 검사는 그 칸을 먼저 뒤집는다.
 */
function flipCut(label: string) {
  fireEvent.click(screen.getByRole('button', { name: `${label} 설명 보기` }));
}

/**
 * 휴대폰의 파일 공유 메뉴를 세운다. 기기의 공유 메뉴는 손가락이 주 입력인 기기에서만 연다
 * (업무 -767, `utils/share.ts`)
 */
function stubPhoneShare(share: ReturnType<typeof vi.fn>) {
  Object.defineProperty(navigator, 'share', { value: share, configurable: true });
  Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true });
  const coarse = vi
    .spyOn(window, 'matchMedia')
    .mockImplementation((query: string) => ({ matches: true, media: query }) as MediaQueryList);

  return {
    share,
    restore() {
      Reflect.deleteProperty(navigator, 'share');
      Reflect.deleteProperty(navigator, 'canShare');
      coarse.mockRestore();
    },
  };
}

describe('3.1 → 3.3 → 3.4 ⇢ 3.1', () => {
  it('첫 방문에는 뒷면 네 컷을 차례로 보여주고 재방문에는 반복하지 않는다', async () => {
    const view = renderApp(['/reports/301']);
    await screen.findByRole('button', { name: '01 장르 설명 보기' });

    expect(screen.getByText('각 컷을 눌러 뒷면의 설명을 확인해 보세요')).toBeInTheDocument();
    for (const label of ['01 장르', '02 감독', '03 배우', '04 캐릭터']) {
      await waitFor(
        () => {
          expect(
            screen.getByText(`${label.slice(0, 2)} / 04 · ${label.slice(3)} 뒷면 살펴보기`),
          ).toBeInTheDocument();
          expect(screen.getByRole('button', { name: `${label} 사진 보기` })).toHaveAttribute(
            'aria-expanded',
            'true',
          );
          expect(document.querySelectorAll('button[aria-expanded="true"]')).toHaveLength(1);
        },
        { timeout: 5_000 },
      );
      await waitFor(
        () => {
          expect(screen.getByRole('button', { name: `${label} 설명 보기` })).toHaveAttribute(
            'aria-expanded',
            'false',
          );
        },
        { timeout: 5_000 },
      );
    }
    expect(window.localStorage.getItem('feelm-report-flip-intro-v4:301')).toBe('1');

    view.unmount();
    renderApp(['/reports/301']);
    const revisitedFlip = await screen.findByRole('button', { name: '01 장르 설명 보기' });
    await new Promise((resolve) => window.setTimeout(resolve, 1_000));
    expect(revisitedFlip).toHaveAttribute('aria-expanded', 'false');
  });

  it('움직임 줄이기 설정에서는 자동으로 뒤집지 않고 안내 문구만 보인다', async () => {
    const media = vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches: query === '(prefers-reduced-motion: reduce)',
          media: query,
        }) as MediaQueryList,
    );
    try {
      renderApp(['/reports/301']);
      const flip = await screen.findByRole('button', { name: '01 장르 설명 보기' });
      expect(screen.getByText('각 컷을 눌러 뒷면의 설명을 확인해 보세요')).toBeInTheDocument();
      expect(window.localStorage.getItem('feelm-report-flip-intro-v4:301')).toBe('1');
      await new Promise((resolve) => window.setTimeout(resolve, 900));
      expect(flip).toHaveAttribute('aria-expanded', 'false');
    } finally {
      media.mockRestore();
    }
  });

  it('사용자가 먼저 칸을 누르면 자동 시연이 끼어들지 않는다', async () => {
    renderApp(['/reports/301']);
    const flip = await screen.findByRole('button', { name: '01 장르 설명 보기' });
    fireEvent.click(flip);

    await new Promise((resolve) => window.setTimeout(resolve, 1_000));
    expect(screen.getByRole('button', { name: '01 장르 사진 보기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('화면 밖의 다음 컷은 스크롤해서 보여준 뒤 뒤집는다', async () => {
    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView;
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect');
    rect.mockImplementation(function (this: HTMLElement) {
      if (this.querySelector('[data-cut="director"]')) {
        return { top: window.innerHeight + 10, bottom: window.innerHeight + 200 } as DOMRect;
      }
      return { top: 20, bottom: 200 } as DOMRect;
    });

    try {
      renderApp(['/reports/301']);
      await screen.findByRole('button', { name: '01 장르 설명 보기' });
      await waitFor(
        () => expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' }),
        { timeout: 6_000 },
      );
      await waitFor(() =>
        expect(screen.getByRole('button', { name: '02 감독 사진 보기' })).toHaveAttribute(
          'aria-expanded',
          'true',
        ),
      );
    } finally {
      rect.mockRestore();
      HTMLElement.prototype.scrollIntoView = originalScrollIntoView;
    }
  });

  it('마지막 컷이 앞면으로 돌아오면 페이지 맨 위로 이동한다', async () => {
    const previousScrollY = Object.getOwnPropertyDescriptor(window, 'scrollY');
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 640 });

    try {
      renderApp(['/reports/301']);
      await waitFor(
        () =>
          expect(screen.getByRole('button', { name: '04 캐릭터 사진 보기' })).toHaveAttribute(
            'aria-expanded',
            'true',
          ),
        { timeout: 12_000 },
      );
      expect(scrollTo).not.toHaveBeenCalled();
      await waitFor(() => expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' }), {
        timeout: 5_000,
      });
      expect(screen.getByRole('button', { name: '04 캐릭터 설명 보기' })).toHaveAttribute(
        'aria-expanded',
        'false',
      );
    } finally {
      scrollTo.mockRestore();
      if (previousScrollY) Object.defineProperty(window, 'scrollY', previousScrollY);
    }
  });

  it('사용자가 직접 스크롤하면 자동 시연을 멈춘다', async () => {
    renderApp(['/reports/301']);
    const flip = await screen.findByRole('button', { name: '01 장르 설명 보기' });
    fireEvent.wheel(window);
    await new Promise((resolve) => window.setTimeout(resolve, 900));
    expect(flip).toHaveAttribute('aria-expanded', 'false');
  });

  it('preview=1에서는 새로고침해도 첫 컷 시연을 다시 볼 수 있다', async () => {
    window.localStorage.setItem('feelm-report-flip-intro-v4:301', '1');
    const view = renderApp(['/reports/301?preview=1']);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '01 장르 사진 보기' })).toHaveAttribute(
        'aria-expanded',
        'true',
      ),
    );

    view.unmount();
    renderApp(['/reports/301?preview=1']);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '01 장르 사진 보기' })).toHaveAttribute(
        'aria-expanded',
        'true',
      ),
    );
  });

  it('안 본 리포트가 있으면 발급 화면을 거쳐 상세로 간다', async () => {
    renderApp([ROUTE_PATTERN.film]);

    fireEvent.click(await screen.findByRole('button', { name: '새 취향 리포트 받기' }));

    await waitFor(() => expect(location()).toBe('/reports/301/new'));
    expect(await screen.findByText('2026년 상반기 취향 필름을 인화했어요')).toBeInTheDocument();
    expect(screen.getByText('지난 반기보다 다크초코 취향이 12% 증가했어요')).toBeInTheDocument();

    // 인화 연출은 건너뛸 수 있고, 건너뛰면 버튼이 사라진다
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));
    expect(screen.queryByRole('button', { name: '건너뛰기' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '리포트 보기' }));

    await waitFor(() => expect(location()).toBe('/reports/301'));
    expect(
      await screen.findByRole('heading', { name: '2026 상반기 취향 리포트' }),
    ).toBeInTheDocument();

    expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument();
  });

  it('01 장르 뒷면에 국가 비율이, 04 캐릭터 뒷면에 지난 반기 비교가 있다', async () => {
    renderApp(['/reports/301']);

    await screen.findByText('01 장르');
    flipCut('01 장르');
    flipCut('04 캐릭터');

    expect(screen.getByRole('progressbar', { name: '한국' })).toHaveAttribute(
      'aria-valuenow',
      '42',
    );
    // 4컷의 감독 칸과 감독 목록에 한 번씩
    expect(screen.getAllByText('웨스 앤더슨')).toHaveLength(2);
    expect(screen.getByText('+4% 신규')).toBeInTheDocument();
    expect(screen.getByText('−6%')).toBeInTheDocument();
    // 이번 반기에 처음 나온 맛은 이름 옆에도 표시한다. 계약은 `isNew`만 주고 문구는 화면이 만든다
    expect(screen.getByText('블루베리 (새로운 맛)')).toBeInTheDocument();
    expect(screen.getByText('다크초코')).toBeInTheDocument();
  });

  /*
   * 인물 검색으로 가는 길은 **뒷면 목록의 이름 하나뿐이다**(업무 -812). 업무 -557은 감독 · 배우
   * 칸 전체를 검색 링크로 덮었는데, 그 자리를 뒤집기가 쓰게 되어 칸 하나가 두 동작을 가질 수
   * 없게 됐다. 누르면 뒤집히는 쪽으로 통일하고(사용자 결정 2026-09-23) 검색은 뒷면이 맡는다 —
   * 1위 한 명만이 아니라 목록의 세 명 모두로 갈 수 있어 오히려 넓어진다.
   */
  it('뒷면 목록의 감독·배우 이름을 같은 이름의 검색으로 연결한다', async () => {
    renderApp(['/reports/301']);

    await screen.findByText('02 감독');
    flipCut('02 감독');
    flipCut('03 배우');

    // 앞면을 덮던 칸 전체 검색 링크는 없다. 앞면을 누르면 뒤집힌다
    expect(screen.queryByRole('link', { name: '웨스 앤더슨 검색' })).toBeNull();

    expect(screen.getByRole('link', { name: '웨스 앤더슨' })).toHaveAttribute(
      'href',
      '/search?q=%EC%9B%A8%EC%8A%A4%20%EC%95%A4%EB%8D%94%EC%8A%A8',
    );

    const actorList = screen.getByRole('link', { name: '엠마 스톤' });
    expect(actorList).toHaveAttribute('href', '/search?q=%EC%97%A0%EB%A7%88%20%EC%8A%A4%ED%86%A4');

    fireEvent.click(actorList);
    expect(location()).toBe('/search?q=%EC%97%A0%EB%A7%88%20%EC%8A%A4%ED%86%A4');
  });

  it('취향 키워드는 근거와 함께 그리고, 비면 칸째 숨긴다', async () => {
    renderApp(['/reports/301']);

    await screen.findByText('01 장르');
    flipCut('01 장르');

    expect(screen.getByText('취향 키워드')).toBeInTheDocument();
    expect(screen.getByText('웨스 앤더슨 몰아보기')).toBeInTheDocument();
    expect(screen.getByText('이번 반기에 블루베리 프레임 4개 추가')).toBeInTheDocument();
    expect(screen.getByText('이번 반기 평균 ★4.3')).toBeInTheDocument();
  });

  it('캐릭터가 없는 리포트는 설명을 숨기고 4컷의 04 칸만 준비 중으로 둔다', async () => {
    // 기준은 업무 -618에서 확정됐지만 백엔드 구현(업무 -779) 전까지 null로 온다
    renderApp(['/reports/301']);

    expect(await screen.findByText('04 캐릭터')).toBeInTheDocument();
    expect(screen.getByText('준비 중')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '캐릭터' })).toBeNull();
    // 나머지 칸과 뒷면은 그대로 그린다
    expect(screen.getByText('01 장르')).toBeInTheDocument();
    flipCut('02 감독');
    expect(screen.getByRole('heading', { name: '감독' })).toBeInTheDocument();
  });

  /*
   * 카드가 찬 뒤(업무 -779). 상대 판정이라 반기마다 카드가 바뀔 수 있어서 **네 축을 함께
   * 보인다** — 왜 이 카드인지가 설명되는 자리다(업무 -618 결정).
   */
  it('캐릭터 카드가 오면 이름 · 코드 · 한 줄 · 네 축 · 설명문을 그린다', async () => {
    vi.mocked(getReportDetail).mockResolvedValue({
      ...reportDetails[301],
      characterCard: reportCharacterCard,
      fourCut: { ...reportDetails[301].fourCut, character: { name: '안락의자 탐정' } },
    });

    renderApp(['/reports/301']);

    await screen.findByText('04 캐릭터');
    flipCut('04 캐릭터');

    expect(screen.getByRole('heading', { name: '캐릭터' })).toBeInTheDocument();
    // 4컷의 04 칸과 뒷면의 캐릭터 카드에 한 번씩
    expect(screen.getAllByText('안락의자 탐정')).toHaveLength(2);
    expect(screen.getByText('RDMV')).toBeInTheDocument();
    expect(screen.getByText('익숙한 자리에서 잔뜩 봤고, 별점은 냉정했어요')).toBeInTheDocument();

    // 고른 쪽은 계약이 준 label, 반대쪽은 화면이 가진 표에서 온다
    ['익숙함', '새로움', '어두움', '밝음', '박함', '후함', '많음', '적음'].forEach((pole) =>
      expect(screen.getByText(pole)).toBeInTheDocument(),
    );

    // 프레임 10편 미만이라 기준 편수를 함께 알린다
    expect(screen.getByText('이번 반기 7편을 기준으로 뽑았어요')).toBeInTheDocument();
  });

  it('프레임이 10편 이상이면 기준 편수 문구를 보이지 않는다', async () => {
    vi.mocked(getReportDetail).mockResolvedValue({
      ...reportDetails[301],
      characterCard: {
        ...reportCharacterCard,
        basis: { frameCount: 38, lowSample: false },
      },
    });

    renderApp(['/reports/301']);

    await screen.findByText('04 캐릭터');
    flipCut('04 캐릭터');

    expect(screen.getByRole('heading', { name: '캐릭터' })).toBeInTheDocument();
    expect(screen.queryByText(/기준으로 뽑았어요/)).toBeNull();
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

describe('리포트 상세 저장과 공유', () => {
  it('자동 시연 전에 저장하면 안내는 PNG 밖에 있고 뒤집기가 시작되지 않는다', async () => {
    renderApp(['/reports/301']);
    const save = await screen.findByRole('button', { name: '저장' });
    fireEvent.click(save);

    await waitFor(() => expect(captureReportImage).toHaveBeenCalledOnce());
    const captured = vi.mocked(captureReportImage).mock.calls[0][0];
    expect(captured).not.toHaveTextContent('각 컷을 눌러 뒷면의 설명을 확인해 보세요');
    await new Promise((resolve) => window.setTimeout(resolve, 900));
    expect(screen.getByRole('button', { name: '01 장르 설명 보기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });

  it('공유 버튼 한 번으로 두 이미지를 공유하고 중간 시트가 없다', async () => {
    const phone = stubPhoneShare(vi.fn().mockResolvedValue(undefined));
    try {
      renderApp(['/reports/301']);
      const share = await screen.findByRole('button', { name: '공유하기' });
      expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument();
      expect(screen.getByText('공유 이미지에는 닉네임과 취향 정보가 담겨요')).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '이미지로 저장' })).not.toBeInTheDocument();
      fireEvent.click(share);
      await waitFor(() => expect(phone.share).toHaveBeenCalledOnce());
      expect((phone.share.mock.calls[0][0] as ShareData).files?.map((file) => file.name)).toEqual([
        'feelm-report-301-front.png',
        'feelm-report-301-back.png',
      ]);
      expect(createReportImage).not.toHaveBeenCalled();
    } finally {
      phone.restore();
    }
  });

  it('공유 메뉴를 쓸 수 없으면 저장 없이 지원 불가를 알린다', async () => {
    renderApp(['/reports/301']);
    fireEvent.click(await screen.findByRole('button', { name: '공유하기' }));
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('이미지 공유를 지원하지 않아요'),
    );
    expect(downloadBlob).not.toHaveBeenCalled();
    expect(createReportImage).not.toHaveBeenCalled();
    expect(captureReportImage).not.toHaveBeenCalled();
  });

  it('공유 메뉴가 클릭 효력 만료로 거절되면 저장 없이 안내한다', async () => {
    const phone = stubPhoneShare(
      vi.fn().mockRejectedValue(new DOMException('expired', 'NotAllowedError')),
    );
    try {
      renderApp(['/reports/301']);
      fireEvent.click(await screen.findByRole('button', { name: '공유하기' }));
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('공유 메뉴를 열지 못했어요'),
      );
      expect(phone.share).toHaveBeenCalledOnce();
      expect(downloadBlob).not.toHaveBeenCalled();
    } finally {
      phone.restore();
    }
  });

  it('뒤집힌 칸을 복원하고 앞면과 뒷면을 만든다', async () => {
    const phone = stubPhoneShare(vi.fn().mockResolvedValue(undefined));
    try {
      renderApp(['/reports/301']);
      await screen.findByText('01 장르');
      flipCut('01 장르');
      fireEvent.click(screen.getByRole('button', { name: '공유하기' }));
      await waitFor(() => expect(phone.share).toHaveBeenCalledOnce());
      expect(captureReportImage).toHaveBeenCalledOnce();
      expect(captureReportBackImage).toHaveBeenCalledOnce();
      expect(screen.getByRole('button', { name: '01 장르 사진 보기' })).toHaveAttribute(
        'aria-expanded',
        'true',
      );
    } finally {
      phone.restore();
    }
  });

  it('이미지 캡처 실패를 알리고 다시 공유할 수 있다', async () => {
    const phone = stubPhoneShare(vi.fn().mockResolvedValue(undefined));
    vi.mocked(captureReportImage).mockRejectedValueOnce(new Error('image failed'));
    try {
      renderApp(['/reports/301']);
      const share = await screen.findByRole('button', { name: '공유하기' });
      fireEvent.click(share);
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent('이미지를 만들지 못했어요'),
      );
      expect(share).toBeEnabled();
      fireEvent.click(share);
      await waitFor(() => expect(phone.share).toHaveBeenCalledOnce());
      expect(downloadBlob).not.toHaveBeenCalled();
    } finally {
      phone.restore();
    }
  });

  it('저장 버튼은 PNG를 내려받고 화면에 머문다', async () => {
    renderApp(['/reports/301']);
    fireEvent.click(await screen.findByRole('button', { name: '저장' }));
    await waitFor(() => expect(downloadReportImage).toHaveBeenCalledOnce());
    expect(createReportImage).toHaveBeenCalledWith(301, expect.any(Blob));
    expect(location()).toBe('/reports/301');
  });

  it('저장 실패 시 화면에 남아 이유를 알린다', async () => {
    vi.mocked(downloadReportImage).mockRejectedValueOnce(new Error('expired'));
    renderApp(['/reports/301']);
    fireEvent.click(await screen.findByRole('button', { name: '저장' }));
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('이미지를 만들지 못했어요'),
    );
    expect(location()).toBe('/reports/301');
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
  it('MY에서 목록으로 가서 상세를 열면 저장과 공유를 제공한다', async () => {
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

    expect(screen.getByRole('button', { name: '저장' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '공유하기' })).toBeInTheDocument();
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
    // 뒷면이 없는 칸은 뒤집는 버튼도 없다
    expect(screen.queryByRole('button', { name: '04 캐릭터 설명 보기' })).toBeNull();
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

  it('첫 미발급 리포트가 준비됐으면 빈 목록 대신 확인하러 가기를 제공한다', async () => {
    serveList({ items: [], current: reportListBeforeIssue.current, preparing: false });
    renderApp([ROUTE_PATTERN.tasteReports]);

    expect(await screen.findByText('새 취향 4컷이 도착했어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '확인하러 가기' }));

    expect(location()).toBe('/reports/301/new');
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
