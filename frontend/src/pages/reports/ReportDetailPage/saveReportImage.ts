/*
 * 취향 4컷 PNG 저장 (API-061, 업무 -731).
 *
 * iOS는 이번 `ReportFourCut` 캡처를 내려받아 서버의 기존 불완전한 PNG 재사용을 피한다.
 * 다른 환경은 기존대로 API-061에서 받은 서명 URL의 PNG를 내려받는다.
 */
import { isApiError } from '../../../api/apiError';
import { createReportImage } from '../../../api/reports';
import {
  captureReportImage,
  downloadBlob,
  downloadReportImage,
  isIOSReportDevice,
} from '../../../utils/reportImage';

export async function saveReportImage(
  reportId: number,
  node: HTMLElement | null,
  onStage?: (stage: 'upload' | 'download') => void,
): Promise<void> {
  if (!node) throw new Error('ReportFourCut is unavailable');

  const png = await captureReportImage(node);
  onStage?.('upload');
  const image = await createReportImage(reportId, png);
  onStage?.('download');
  if (isIOSReportDevice()) downloadBlob(png, `feelm-report-${reportId}.png`);
  else await downloadReportImage(image.downloadUrl, reportId);
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
