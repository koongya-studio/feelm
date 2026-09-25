import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toBlob } from 'html-to-image';
import {
  captureReportBackImage,
  captureReportImage,
  downloadBlob,
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

  it('캡처에서는 칸 뒤집기 버튼을 제외한다', async () => {
    const node = document.createElement('div');
    const flipButton = document.createElement('button');
    flipButton.dataset.reportFlipControl = 'true';
    node.append(flipButton);
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    vi.mocked(toBlob).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));

    await captureReportImage(node);

    const options = vi.mocked(toBlob).mock.calls[0][1];
    expect(options?.filter?.(flipButton)).toBe(false);
    expect(options?.filter?.(document.createElement('div'))).toBe(true);
  });

  it('iPhone에서는 웹폰트 재삽입을 생략하고 PNG를 만든다', async () => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)',
    );
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    vi.mocked(toBlob).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));

    await captureReportImage(node);

    expect(toBlob).toHaveBeenCalledWith(node, expect.objectContaining({ skipFonts: true }));
  });

  it('iPhone의 첫 SVG 로딩이 실패하면 사진이 있는 DOM을 다시 캡처한다', async () => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)',
    );
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    const png = new Blob(['png'], { type: 'image/png' });
    vi.mocked(toBlob).mockRejectedValueOnce(new Error('Load failed')).mockResolvedValueOnce(png);

    await expect(captureReportImage(node)).resolves.toBe(png);
    expect(toBlob).toHaveBeenCalledTimes(2);
  });

  it('뒷면은 폭만 1080으로 맞추고 높이는 화면 비율을 따른다', async () => {
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1540 },
    });
    const png = new Blob(['png'], { type: 'image/png' });
    vi.mocked(toBlob).mockResolvedValue(png);

    await expect(captureReportBackImage(node)).resolves.toBe(png);
    expect(toBlob).toHaveBeenCalledWith(
      node,
      expect.objectContaining({
        width: 385,
        height: 1540,
        canvasWidth: REPORT_IMAGE_WIDTH,
        canvasHeight: 4320,
      }),
    );
  });

  it('사진을 데이터 URL로 포함한 DOM만 PNG로 변환한다', async () => {
    const parent = document.createElement('div');
    const node = document.createElement('div');
    const image = document.createElement('img');
    image.src = '/photo.webp';
    image.decode = vi.fn().mockResolvedValue(undefined);
    Object.defineProperties(image, {
      complete: { value: true },
      naturalWidth: { value: 100 },
    });
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    node.append(image);
    parent.append(node);
    // 브라우저처럼 응답 Blob이 FileReader와 같은 환경의 것이어야 한다. Node의 `Response`를 그대로
    // 쓰면 Node의 Blob이 와서 jsdom의 FileReader가 거절한다(업무 S15P21E106-916)
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['image'], { type: 'image/webp' }),
    } as Response);
    const png = new Blob(['png'], { type: 'image/png' });
    vi.mocked(toBlob).mockImplementation(async (captureNode) => {
      expect(captureNode).toBe(node);
      expect(captureNode.querySelector('img')?.src).toMatch(/^data:image\/webp;base64,/);
      return png;
    });

    await expect(captureReportImage(node)).resolves.toBe(png);
    expect(parent.childElementCount).toBe(1);
    expect(image.getAttribute('src')).toBe('/photo.webp');
  });

  /*
   * 업무 S15P21E106-916. 다른 실행 환경에서 만든 오류는 `instanceof Error`가 거짓이라, 예전에는
   * 시간 초과 문구로 바뀌어 원인이 가려졌다. 시간 초과는 실제로 시간이 다 됐을 때만이다.
   */
  it('다른 환경에서 만든 오류도 시간 초과로 바꾸지 않고 원래 원인을 드러낸다', async () => {
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    // iframe은 제 전역을 따로 가진다. 브라우저에서 다른 창의 오류가 그렇듯 `instanceof Error`가 거짓이다
    const frame = document.createElement('iframe');
    document.body.append(frame);
    const ForeignTypeError = (frame.contentWindow as unknown as { TypeError: TypeErrorConstructor })
      .TypeError;
    const foreign = new ForeignTypeError("parameter 1 is not of type 'Blob'.");
    frame.remove();
    expect(foreign instanceof Error).toBe(false);
    vi.mocked(toBlob).mockRejectedValue(foreign);

    await expect(captureReportImage(node)).rejects.toThrow("parameter 1 is not of type 'Blob'.");
  });

  it('오류가 아닌 값으로 실패하면 캡처 실패로 감싸고 원래 값을 원인으로 남긴다', async () => {
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    vi.mocked(toBlob).mockRejectedValue('Load failed');

    const failure = (await captureReportImage(node).then(
      () => null,
      (error: unknown) => error,
    )) as Error;

    expect(failure.message).toBe('ReportFourCut capture failed');
    expect(failure.cause).toBe('Load failed');
  });

  it('사진을 다시 읽지 못하면 불완전한 PNG를 만들지 않는다', async () => {
    const parent = document.createElement('div');
    const node = document.createElement('div');
    const image = document.createElement('img');
    image.src = 'https://example.com/photo.jpg';
    image.decode = vi.fn().mockResolvedValue(undefined);
    Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 100 } });
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    node.append(image);
    parent.append(node);
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(captureReportImage(node)).rejects.toThrow('Failed to fetch');
    expect(toBlob).not.toHaveBeenCalled();
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
    const rejection = expect(capture).rejects.toThrow('render timed out');
    await vi.advanceTimersByTimeAsync(15_000);

    await rejection;
  });

  it('서명 URL의 실제 PNG 응답을 확인한 뒤 파일 다운로드로 연결한다', async () => {
    vi.useFakeTimers();
    /*
     * 응답 본문은 jsdom `Blob`이 아니라 바이트로 준다. CI의 Node 20은 `Response` 본문에
     * `.stream()`을 요구하는데 jsdom `Blob`에는 그게 없어 여기서 `TypeError`가 났다(Node 22+는
     * 통과해 로컬에서는 드러나지 않았다). 실제 fetch 응답도 바이트이고, `blob().type`은 두 경우
     * 모두 `Content-Type` 헤더에서 오므로 구현의 검사는 그대로 시험된다.
     */
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(new TextEncoder().encode('png'), {
        status: 200,
        headers: { 'Content-Type': 'image/png' },
      }),
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

  it('손에 든 파일을 그 이름으로 내려받는다', () => {
    const createObjectURL = vi.fn().mockReturnValue('blob:share-image');
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
    let downloaded = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloaded = this.download;
    });
    const file = new File(['png'], 'feelm-report-301-back.png', { type: 'image/png' });

    downloadBlob(file, file.name);

    expect(createObjectURL).toHaveBeenCalledWith(file);
    expect(downloaded).toBe('feelm-report-301-back.png');
  });

  it('서명 URL 다운로드가 실패하면 성공으로 처리하지 않는다', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 404 }));

    await expect(
      downloadReportImage('/api/v1/report-images/901/download?signature=x', 301),
    ).rejects.toThrow('download failed');
  });
});
