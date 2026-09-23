import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toBlob } from 'html-to-image';
import {
  captureReportImage,
  downloadReportImage,
  REPORT_IMAGE_HEIGHT,
  REPORT_IMAGE_WIDTH,
} from './reportImage';

vi.mock('html-to-image', () => ({ toBlob: vi.fn() }));

describe('ReportFourCut PNG 저장', () => {
  const originalCreateObjectURL = URL.createObjectURL;
  const originalRevokeObjectURL = URL.revokeObjectURL;

  beforeEach(() => {
    vi.mocked(toBlob).mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: originalCreateObjectURL,
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: originalRevokeObjectURL,
    });
  });

  it('화면의 같은 DOM을 1080×3268 PNG로 캡처한다', async () => {
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    const png = new Blob(['png'], { type: 'image/png' });
    vi.mocked(toBlob).mockResolvedValue(png);

    await expect(captureReportImage(node)).resolves.toBe(png);
    expect(toBlob).toHaveBeenCalledWith(
      node,
      expect.objectContaining({
        width: 385,
        height: 1165,
        canvasWidth: REPORT_IMAGE_WIDTH,
        canvasHeight: REPORT_IMAGE_HEIGHT,
      }),
    );
  });

  it('사진 실패가 표시된 DOM은 업로드용 PNG를 만들지 않는다', async () => {
    const node = document.createElement('div');
    node.innerHTML = '<div data-image-error="true"></div>';

    await expect(captureReportImage(node)).rejects.toThrow('image loading failed');
    expect(toBlob).not.toHaveBeenCalled();
  });

  it('폰트·사진·paint·PNG 변환을 합쳐 15초를 넘기지 않는다', async () => {
    vi.useFakeTimers();
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    vi.mocked(toBlob).mockReturnValue(new Promise(() => undefined));

    const capture = captureReportImage(node);
    const rejection = expect(capture).rejects.toThrow('capture timed out');
    await vi.advanceTimersByTimeAsync(15_000);

    await rejection;
  });

  it('서명 URL의 실제 PNG 응답을 확인한 뒤 파일 다운로드로 연결한다', async () => {
    vi.useFakeTimers();
    const png = new Blob(['png'], { type: 'image/png' });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(png, { status: 200, headers: { 'Content-Type': 'image/png' } }),
    );
    const createObjectURL = vi.fn().mockReturnValue('blob:report-image');
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);

    await downloadReportImage('/api/v1/report-images/901/download?signature=x', 301);

    expect(click).toHaveBeenCalledOnce();
    expect(createObjectURL).toHaveBeenCalledWith(expect.objectContaining({ type: 'image/png' }));
    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/report-images/901/download?signature=x',
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(revokeObjectURL).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:report-image');
  });

  it('서명 URL 다운로드가 실패하면 성공으로 처리하지 않는다', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }));

    await expect(
      downloadReportImage('/api/v1/report-images/901/download?signature=x', 301),
    ).rejects.toThrow('download failed');
  });
});
