/*
 * 취향 리포트 데이터 접근 함수. 대응 명세는 docs/api/report-trend.md(API-059 ~ API-061)이다.
 *
 * **2차 MVP라 아직 없다.** 목업으로 응답한다. 와이어프레임에만 있는 값은
 * `types/unspecified.ts`의 `ReportDetailView`에 있다(팀 결정 2026-09-13, 업무 -308).
 */
import { mockReportDetails, mockReportList } from '../mocks/reports';
import { isReportSeen, markReportSeen } from '../mocks/sessionStore';
import { respond, respondError } from './mockTransport';
import type { ReportImage, ReportList } from '../types/report';
import type { ReportDetailView } from '../types/unspecified';

/** API-059 내 리포트 목록 · GET /api/v1/users/me/reports — 최신 반기가 앞이다 */
export async function getMyReports(): Promise<ReportList> {
  return respond({ items: mockReportList }, 'reports');
}

/**
 * API-060 리포트 상세 조회 · GET /api/v1/reports/{reportId}
 * 없으면 404, 아직 만드는 중이면 `409 REPORT_GENERATING`이다.
 */
export async function getReportDetail(reportId: number): Promise<ReportDetailView> {
  const summary = mockReportList.find((item) => item.reportId === reportId);

  if (summary?.status === 'GENERATING') {
    return respondError(409, 'REPORT_GENERATING', '리포트를 만드는 중입니다.');
  }

  const detail = mockReportDetails[reportId];

  if (!detail) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '리포트를 찾을 수 없습니다.');
  }

  return respond(detail, 'reports');
}

/**
 * API-061 리포트 이미지 카드 생성 · POST /api/v1/reports/{reportId}/image
 *
 * 목업에는 실제 이미지가 없다. 주소 모양만 계약대로 돌려준다. 3.5가 이 주소로 이미지를
 * 받아 저장하거나 공유 시트에 넘기는 부분은 연동 때 만든다.
 */
export async function createReportImage(reportId: number): Promise<ReportImage> {
  if (!mockReportDetails[reportId]) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '리포트를 찾을 수 없습니다.');
  }

  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();

  return respond(
    {
      imageId: 800 + reportId,
      format: 'PNG' as const,
      downloadUrl: `https://storage.example.com/reports/${reportId}.png`,
      expiresAt,
    },
    'reports',
  );
}

/**
 * 2.1 `취향 리포트 보기`가 가는 곳. **계약에 없다.**
 *
 * 가장 최근의 `READY` 리포트를 고른다. 3.3 발급 화면을 본 적 없으면 3.3을 거친다
 * (`needsIntro`). 3.3으로 들어오는 화살표가 이동표에 없어 정한 규칙이다(업무 -308).
 * 만든 리포트가 하나도 없으면 null이다.
 */
export async function getLatestReadyReport(): Promise<{
  reportId: number;
  needsIntro: boolean;
} | null> {
  const latest = mockReportList.find((item) => item.status === 'READY');

  return respond(
    latest ? { reportId: latest.reportId, needsIntro: !isReportSeen(latest.reportId) } : null,
    'reports',
  );
}

/** 3.3 `리포트 보기`를 누른 뒤. 다시 3.3을 보여주지 않는다. 계약에 없다 */
export async function markReportIntroSeen(reportId: number): Promise<void> {
  markReportSeen(reportId);

  return respond(undefined);
}
