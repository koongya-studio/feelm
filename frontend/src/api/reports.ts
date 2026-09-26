/*
 * 취향 리포트 데이터 접근 함수. 대응 명세는 docs/api/report-trend.md(API-059 · 060 · 080 ·
 * 061)이다.
 *
 * 목록 · 상세 · 첫 발급은 실제 백엔드를 부른다(업무 -558, 백엔드 -556). 이미지 카드도
 * 화면의 `ReportFourCut`을 PNG로 캡처해 API-061에 저장한다(업무 -557).
 */
import { request } from './client';
import type {
  ReportDetail,
  ReportGenerated,
  ReportImage,
  ReportIssue,
  ReportList,
} from '../types/report';

/** 현재 전체 필름으로 생성한다. 변화가 없으면 마지막 결과를 반환한다. */
export async function generateReport(): Promise<ReportGenerated> {
  return request<ReportGenerated>('/users/me/reports', { method: 'POST' });
}

/** API-059 내 리포트 목록 · GET /api/v1/users/me/reports — 최신 반기가 앞이다 */
export async function getMyReports(): Promise<ReportList> {
  return request<ReportList>('/users/me/reports');
}

/**
 * API-060 리포트 상세 조회 · GET /api/v1/reports/{reportId}
 * 없거나 남의 것이거나 공개 시각 전이면 404, 아직 만드는 중이면 `409 REPORT_GENERATING`이다.
 */
export async function getReportDetail(reportId: number): Promise<ReportDetail> {
  return request<ReportDetail>(`/reports/${reportId}`);
}

/**
 * API-080 리포트 첫 발급 기록 · POST /api/v1/reports/{reportId}/issue
 *
 * 3.3 인화가 끝난 뒤 한 번 부른다. 재요청도 200이고 처음 기록한 시각을 그대로 돌려주므로
 * 같은 리포트를 다시 열어도 값이 바뀌지 않는다. 반기 분석을 시작하거나 다시 하지 않는다.
 */
export async function issueReport(reportId: number): Promise<ReportIssue> {
  return request<ReportIssue>(`/reports/${reportId}/issue`, { method: 'POST' });
}

/**
 * API-061 리포트 이미지 카드 생성 · POST /api/v1/reports/{reportId}/image
 *
 * 본문은 화면의 `ReportFourCut` DOM을 캡처한 PNG다. 백엔드는 디자인을 다시 그리지 않고
 * 파일을 검증·비공개 저장한 뒤 10분 서명 URL을 돌려준다.
 */
export async function createReportImage(reportId: number, png: Blob): Promise<ReportImage> {
  return request<ReportImage>(`/reports/${reportId}/image`, { method: 'POST', body: png });
}

/**
 * 2.1 · 3.1 `취향 리포트 보기`가 가는 곳. **계약에 없는 조합 함수다.**
 *
 * API-059의 `current`로 판정한다. 공개된 최신 반기가 `READY`이고 아직 발급되지 않았으면
 * (`issuedAt`이 null) 3.3 인화 화면을 거치고, 그 밖에는 null이라 7.3 목록으로 간다. 3.3으로
 * 들어오는 화살표가 이동표에 없어 정한 규칙이고(업무 -308), 목업 시절 브라우저 세션에
 * 두었던 `본 적 있음` 표시를 서버의 발급 시각으로 바꿨다.
 */
export async function getLatestReadyReport(): Promise<{
  reportId: number;
  needsIntro: boolean;
} | null> {
  const { current } = await getMyReports();

  if (!current || current.status !== 'READY' || current.issuedAt !== null) {
    return null;
  }

  return { reportId: current.reportId, needsIntro: true };
}
