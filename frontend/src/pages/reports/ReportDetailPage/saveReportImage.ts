/*
 * 취향 4컷 PNG 저장 (API-061, 업무 -731).
 *
 * 3.4 · 7.4의 `저장`과 3.5의 `이미지로 저장`이 같이 쓴다. 화면의 `ReportFourCut`을 캡처해
 * API-061에 올리고, 받은 서명 URL의 PNG를 실제로 내려받은 뒤에야 끝난다. URL 발급만으로
 * 성공을 알리지 않는다(`docs/api/report-trend.md` API-061 처리 규칙).
 */
import { isApiError } from '../../../api/apiError';
import { createReportImage } from '../../../api/reports';
import { captureReportImage, downloadReportImage } from '../../../utils/reportImage';

export async function saveReportImage(reportId: number, node: HTMLElement | null): Promise<void> {
  if (!node) throw new Error('ReportFourCut is unavailable');

  const png = await captureReportImage(node);
  const image = await createReportImage(reportId, png);
  await downloadReportImage(image.downloadUrl, reportId);
}

/** 저장 실패 안내. 기다리면 풀리는 409 · 429만 따로 알리고 나머지는 다시 시도로 묶는다 */
export function reportImageFailure(error: unknown): string {
  if (isApiError(error)) {
    if (error.code === 'REPORT_GENERATING') {
      return '리포트를 아직 만드는 중이에요. 잠시 후 다시 시도해 주세요';
    }

    // 파일 유실 복구의 1분 간격이나 동시 처리 상한에 걸렸다
    if (error.code === 'RATE_LIMITED') {
      return '이미지 요청이 몰렸어요. 1분쯤 뒤에 다시 시도해 주세요';
    }
  }

  return '이미지를 만들지 못했어요. 잠시 후 다시 시도해 주세요';
}
