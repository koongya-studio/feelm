import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createReportImage } from '../../../api/reports';
import {
  captureReportImage,
  downloadBlob,
  downloadReportImage,
  isIOSReportDevice,
} from '../../../utils/reportImage';
import { saveReportImage } from './saveReportImage';

vi.mock('../../../api/reports', () => ({ createReportImage: vi.fn() }));
vi.mock('../../../utils/reportImage', () => ({
  captureReportImage: vi.fn(),
  downloadBlob: vi.fn(),
  downloadReportImage: vi.fn(),
  isIOSReportDevice: vi.fn(),
}));

describe('현재 화면의 리포트 PNG 저장', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(isIOSReportDevice).mockReturnValue(true);
  });

  it('서버가 기존 이미지 URL을 반환해도 방금 캡처한 정상 PNG를 내려받는다', async () => {
    const png = new Blob(['current-complete-report'], { type: 'image/png' });
    vi.mocked(captureReportImage).mockResolvedValue(png);
    vi.mocked(createReportImage).mockResolvedValue({
      imageId: 901,
      format: 'PNG',
      downloadUrl: '/api/v1/report-images/901/download?old-file',
      expiresAt: '2026-09-27T02:00:00Z',
    });
    const onStage = vi.fn();
    await saveReportImage(301, document.createElement('div'), onStage);
    expect(createReportImage).toHaveBeenCalledWith(301, png);
    expect(downloadBlob).toHaveBeenCalledWith(png, 'feelm-report-301.png');
    expect(downloadReportImage).not.toHaveBeenCalled();
    expect(onStage.mock.calls).toEqual([['upload'], ['download']]);
  });

  it.each([false, true])(
    '캡처가 실패하면 서버에 보내거나 파일을 내려받지 않는다 (iOS=%s)',
    async (ios) => {
      vi.mocked(isIOSReportDevice).mockReturnValue(ios);
      vi.mocked(captureReportImage).mockRejectedValue(new Error('font fetch failed'));
      await expect(saveReportImage(301, document.createElement('div'))).rejects.toThrow(
        'font fetch failed',
      );
      expect(createReportImage).not.toHaveBeenCalled();
      expect(downloadBlob).not.toHaveBeenCalled();
      expect(downloadReportImage).not.toHaveBeenCalled();
    },
  );

  it.each([false, true])(
    '서버 인가·업로드 확인이 실패하면 성공 처리하지 않는다 (iOS=%s)',
    async (ios) => {
      vi.mocked(isIOSReportDevice).mockReturnValue(ios);
      vi.mocked(captureReportImage).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));
      vi.mocked(createReportImage).mockRejectedValue(new Error('forbidden'));
      await expect(saveReportImage(301, document.createElement('div'))).rejects.toThrow(
        'forbidden',
      );
      expect(downloadBlob).not.toHaveBeenCalled();
      expect(downloadReportImage).not.toHaveBeenCalled();
    },
  );

  it('갤럭시·웹은 기존처럼 API-061이 반환한 서버 PNG를 내려받는다', async () => {
    vi.mocked(isIOSReportDevice).mockReturnValue(false);
    const png = new Blob(['current'], { type: 'image/png' });
    vi.mocked(captureReportImage).mockResolvedValue(png);
    vi.mocked(createReportImage).mockResolvedValue({
      imageId: 901,
      format: 'PNG',
      downloadUrl: '/signed-original.png',
      expiresAt: '2026-09-27T02:00:00Z',
    });
    vi.mocked(downloadReportImage).mockResolvedValue(undefined);
    await saveReportImage(301, document.createElement('div'));
    expect(createReportImage).toHaveBeenCalledWith(301, png);
    expect(downloadReportImage).toHaveBeenCalledWith('/signed-original.png', 301);
    expect(downloadBlob).not.toHaveBeenCalled();
  });

  it('갤럭시·웹의 서명 PNG 다운로드 실패도 그대로 오류로 처리한다', async () => {
    vi.mocked(isIOSReportDevice).mockReturnValue(false);
    vi.mocked(captureReportImage).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));
    vi.mocked(createReportImage).mockResolvedValue({
      imageId: 901,
      format: 'PNG',
      downloadUrl: '/signed-original.png',
      expiresAt: '2026-09-27T02:00:00Z',
    });
    vi.mocked(downloadReportImage).mockRejectedValue(new Error('download failed'));
    await expect(saveReportImage(301, document.createElement('div'))).rejects.toThrow(
      'download failed',
    );
    expect(downloadBlob).not.toHaveBeenCalled();
  });
});
