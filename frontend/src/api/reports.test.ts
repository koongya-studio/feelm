/*
 * 취향 리포트 (업무 S15P21E106-558).
 *
 * 화면에서는 드러나지 않는 것을 고정한다.
 * - **3.1의 버튼은 `current`가 가른다.** 목업 시절에는 브라우저 세션에 `본 적 있음`을 적어
 *   두었고, 이제는 서버의 발급 시각(`issuedAt`)이 기준이다(API-059 · 080). 세션 저장이
 *   남아 있으면 다른 기기 · 재로그인에서 같은 발급을 다시 보여 준다
 * - **인화 중인 반기는 목록에 없다.** `GENERATING`은 `current`로만 오므로 `items`만 읽으면
 *   7.3의 `인화 중` 칸이 사라진다(API-059 처리 규칙)
 * - **첫 발급은 본문 없는 POST다.** 재요청도 200이고 처음 기록한 시각을 그대로 준다(API-080)
 * - 이미지 카드 API-061은 화면에서 캡처한 PNG를 그대로 본문에 싣는다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createReportImage,
  getLatestReadyReport,
  getMyReports,
  getReportDetail,
  issueReport,
} from './reports';
import { resetSessionForTest, startSession } from './session';
import { reportDetails, reportList, reportListAfterIssue } from '../test/reports';
import type { ReportList } from '../types/report';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function call(index = 0) {
  return fetchMock.mock.calls[index] as [string, RequestInit];
}

beforeEach(() => {
  resetSessionForTest();
  startSession('token');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-059 내 리포트 목록', () => {
  it('회원 토큰을 싣고 목록 · current · preparing을 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, reportList));

    expect(await getMyReports()).toEqual(reportList);
    expect(call()[0]).toBe('/api/v1/users/me/reports');
    expect((call()[1].headers as Record<string, string>).Authorization).toBe('Bearer token');
  });

  it('인화 중인 반기는 items가 아니라 current로만 온다', async () => {
    fetchMock.mockResolvedValue(json(200, reportList));

    const { items, current } = await getMyReports();

    expect(items.map((item) => item.reportId)).toEqual([301, 202, 201]);
    expect(current).toEqual({
      reportId: 302,
      period: { year: 2026, half: 'H2' },
      status: 'GENERATING',
      issuedAt: null,
    });
  });
});

describe('API-060 리포트 상세', () => {
  it('상세 경로를 부르고 4컷 · 비율 · 비교를 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, reportDetails[301]));

    const detail = await getReportDetail(301);

    expect(call()[0]).toBe('/api/v1/reports/301');
    expect(detail.fourCut.director?.name).toBe('웨스 앤더슨');
    expect(detail.countryShares).toEqual(reportDetails[301].countryShares);
    expect(detail.comparisons[1]).toEqual({ label: '블루베리', delta: 4, isNew: true });
  });

  it('만드는 중이면 계약 코드가 그대로 올라간다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'REPORT_GENERATING', message: '리포트를 만드는 중입니다.' }),
    );

    await expect(getReportDetail(302)).rejects.toMatchObject({
      status: 409,
      code: 'REPORT_GENERATING',
    });
  });
});

describe('API-080 리포트 첫 발급 기록', () => {
  it('본문 없는 POST로 부르고 발급 시각을 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, { reportId: 301, issuedAt: '2026-07-01T00:00:14Z' }));

    expect(await issueReport(301)).toEqual({
      reportId: 301,
      issuedAt: '2026-07-01T00:00:14Z',
    });
    expect(call()[0]).toBe('/api/v1/reports/301/issue');
    expect(call()[1].method).toBe('POST');
    expect(call()[1].body).toBeUndefined();
  });

  it('발급할 수 없는 리포트의 409는 그대로 올라간다', async () => {
    fetchMock.mockResolvedValue(json(409, { code: 'CONFLICT', message: '발급할 수 없습니다.' }));

    await expect(issueReport(201)).rejects.toMatchObject({ status: 409, code: 'CONFLICT' });
  });
});

describe('3.1 리포트 버튼 판정', () => {
  function serve(list: ReportList) {
    fetchMock.mockResolvedValue(json(200, list));
  }

  it('공개된 최신 반기가 READY인데 발급 기록이 없으면 인화 화면으로 보낸다', async () => {
    serve({
      ...reportList,
      current: {
        reportId: 301,
        period: { year: 2026, half: 'H1' },
        status: 'READY',
        issuedAt: null,
      },
    });

    expect(await getLatestReadyReport()).toEqual({ reportId: 301, needsIntro: true });
  });

  it('이미 뽑은 반기면 null이다. 다시 뽑기는 발급을 기록하지 않는다', async () => {
    serve(reportListAfterIssue);

    expect(await getLatestReadyReport()).toBeNull();
  });

  it('최신 반기를 아직 만드는 중이거나 리포트가 없으면 null이다', async () => {
    serve(reportList);
    expect(await getLatestReadyReport()).toBeNull();

    serve({ items: [], current: null, preparing: false });
    expect(await getLatestReadyReport()).toBeNull();
  });
});

describe('API-061 리포트 이미지 카드', () => {
  it('ReportFourCut에서 캡처한 PNG를 가공하지 않고 서버에 보낸다', async () => {
    const png = new Blob(['frontend-four-cut'], { type: 'image/png' });
    fetchMock.mockResolvedValue(
      json(201, {
        imageId: 901,
        format: 'PNG',
        downloadUrl: '/api/v1/report-images/901/download?expires=1&signature=x',
        expiresAt: '2026-07-01T00:10:00Z',
      }),
    );

    const image = await createReportImage(301, png);

    expect(image.format).toBe('PNG');
    expect(call()[0]).toBe('/api/v1/reports/301/image');
    expect(call()[1].method).toBe('POST');
    expect(call()[1].body).toBe(png);
    expect((call()[1].headers as Record<string, string>)['Content-Type']).toBe('image/png');
  });
});
