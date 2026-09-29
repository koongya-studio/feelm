import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toBlob } from 'html-to-image';
import { reportFontEmbedCSS } from './reportImageFonts';
import { renderIOSReportImage } from './reportImageIOS';
import {
  captureReportImage,
  captureReportBackImage,
  downloadBlob,
  downloadReportImage,
  REPORT_IMAGE_HEIGHT,
  REPORT_IMAGE_WIDTH,
} from './reportImage';
import reportStyles from '../components/ReportFourCut/ReportFourCut.module.css';

vi.mock('html-to-image', () => ({ toBlob: vi.fn() }));
vi.mock('./reportImageIOS', () => ({ renderIOSReportImage: vi.fn() }));
vi.mock('./reportImageFonts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./reportImageFonts')>()),
  reportFontEmbedCSS: vi.fn(),
}));

describe('ReportFourCut PNG 저장', () => {
  const originalCreateObjectURL = URL.createObjectURL;
  const originalRevokeObjectURL = URL.revokeObjectURL;
  beforeEach(() => {
    vi.mocked(toBlob).mockReset();
    vi.mocked(renderIOSReportImage).mockReset();
    vi.mocked(reportFontEmbedCSS)
      .mockReset()
      .mockResolvedValue('@font-face { font-family: Anton; }');
    Object.defineProperty(HTMLImageElement.prototype, 'decode', {
      configurable: true,
      writable: true,
      value: vi.fn().mockResolvedValue(undefined),
    });
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(100);
    vi.spyOn(HTMLImageElement.prototype, 'naturalHeight', 'get').mockReturnValue(100);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage: vi.fn(),
    } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue(
      'data:image/png;base64,cG5n',
    );
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
      this: HTMLElement,
    ) {
      return new DOMRect(0, 0, this.scrollWidth, this.scrollHeight);
    });
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

  it('뒷면이 남아 있는 DOM은 앞면 저장본으로 캡처하지 않는다', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    const node = document.createElement('div');
    node.innerHTML = '<li data-flipped="true"></li>';
    await expect(captureReportImage(node)).rejects.toThrow('capture requires the front');
    expect(toBlob).not.toHaveBeenCalled();
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
    expect(reportFontEmbedCSS).not.toHaveBeenCalled();
    expect(renderIOSReportImage).not.toHaveBeenCalled();
    expect(vi.mocked(toBlob).mock.calls[0][1]?.fontEmbedCSS).toBeUndefined();
  });

  it.each(['Mozilla/5.0 (Linux; Android 10; SM-G965F)', 'Mozilla/5.0 (Windows NT 10.0)'])(
    '긴 뒷면이 있어도 갤럭시·웹 앞면은 화면 경계 비율로 캡처한다 (%s)',
    async (userAgent) => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(userAgent);
      const node = document.createElement('div');
      Object.defineProperties(node, {
        scrollWidth: { value: 328 },
        scrollHeight: { value: 1104 },
      });
      vi.spyOn(node, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 328, 992.49));
      vi.mocked(toBlob).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));

      await captureReportImage(node);

      expect(toBlob).toHaveBeenCalledWith(
        node,
        expect.objectContaining({
          width: 328,
          height: 992.49,
          canvasWidth: 1080,
          canvasHeight: 3268,
        }),
      );
      expect(renderIOSReportImage).not.toHaveBeenCalled();
    },
  );

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

  it('iPhone은 웹폰트를 포함해 사진을 직접 그리는 렌더러를 사용한다', async () => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)',
    );
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    const complete = new Blob(['with-photos'], { type: 'image/png' });
    vi.mocked(renderIOSReportImage).mockResolvedValue(complete);

    await expect(captureReportImage(node)).resolves.toBe(complete);

    expect(toBlob).not.toHaveBeenCalled();
    expect(renderIOSReportImage).toHaveBeenCalledOnce();
    expect(renderIOSReportImage).toHaveBeenCalledWith(
      node,
      [],
      [],
      expect.objectContaining({ fontEmbedCSS: '@font-face { font-family: Anton; }' }),
      expect.any(AbortSignal),
    );
    expect(vi.mocked(renderIOSReportImage).mock.calls[0][3]?.skipFonts).not.toBe(true);
  });

  it('iPhone은 숨겨진 긴 뒷면을 제외한 앞면 경계로 사진과 글자를 배치한다', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1400 },
    });
    vi.spyOn(node, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 385, 1165.25));
    vi.mocked(renderIOSReportImage).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));

    await captureReportImage(node);

    expect(renderIOSReportImage).toHaveBeenCalledWith(
      node,
      [],
      [],
      expect.objectContaining({
        width: 385,
        height: 1165.25,
        canvasWidth: REPORT_IMAGE_WIDTH,
        canvasHeight: REPORT_IMAGE_HEIGHT,
      }),
      expect.any(AbortSignal),
    );
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
    vi.mocked(renderIOSReportImage)
      .mockRejectedValueOnce(new Error('Load failed'))
      .mockResolvedValue(png);

    await expect(captureReportImage(node)).resolves.toBe(png);
    expect(renderIOSReportImage).toHaveBeenCalledTimes(2);
    expect(toBlob).not.toHaveBeenCalled();
  });

  it('데스크톱 모드 iPad도 iOS 사진 렌더러를 사용한다', async () => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Macintosh)');
    vi.spyOn(window.navigator, 'platform', 'get').mockReturnValue('MacIntel');
    Object.defineProperty(window.navigator, 'maxTouchPoints', { configurable: true, value: 5 });
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    vi.mocked(renderIOSReportImage).mockResolvedValue(new Blob(['png'], { type: 'image/png' }));
    try {
      await captureReportImage(node);
      expect(renderIOSReportImage).toHaveBeenCalledOnce();
      expect(toBlob).not.toHaveBeenCalled();
    } finally {
      delete (window.navigator as unknown as { maxTouchPoints?: number }).maxTouchPoints;
    }
  });

  it.each([false, true])(
    'iOS 사진 원본을 유지하고 평면화 상태는 캡처 성공·실패 뒤 복원한다 (%s)',
    async (fails) => {
      vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue('iPhone');
      const node = document.createElement('div');
      const layer = document.createElement('li');
      layer.className = reportStyles.cell;
      layer.style.perspective = '1400px';
      const originalLayerStyle = layer.getAttribute('style');
      node.append(layer);
      const image = document.createElement('img');
      image.src = '/photo.webp';
      image.srcset = '/photo-large.webp 2x';
      image.style.cssText = 'object-fit: contain; object-position: 25% 75%;';
      const originalStyle = image.getAttribute('style');
      Object.defineProperties(image, { complete: { value: true }, naturalWidth: { value: 100 } });
      Object.defineProperties(node, {
        scrollWidth: { value: 385 },
        scrollHeight: { value: 1165 },
      });
      node.append(image);
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({
        ok: true,
        blob: async () => new Blob(['photo'], { type: 'image/webp' }),
      } as Response);
      vi.mocked(renderIOSReportImage).mockImplementation(async (_node, photos, data) => {
        expect(layer.style.perspective).toBe('none');
        expect(layer.style.transition).toBe('none');
        expect(photos).toEqual([image]);
        expect(data).toEqual(['data:image/png;base64,cG5n']);
        expect(image.getAttribute('style')).toBe(originalStyle);
        expect(image.getAttribute('src')).toBe('/photo.webp');
        expect(image.srcset).toBe('/photo-large.webp 2x');
        if (fails) throw new Error('render failed');
        return new Blob(['png'], { type: 'image/png' });
      });
      if (fails) await expect(captureReportImage(node)).rejects.toThrow('render failed');
      else await captureReportImage(node);
      expect(image.getAttribute('src')).toBe('/photo.webp');
      expect(image.getAttribute('srcset')).toBe('/photo-large.webp 2x');
      expect(image.getAttribute('style')).toBe(originalStyle);
      expect(layer.getAttribute('style')).toBe(originalLayerStyle);
    },
  );

  it('폰트를 포함하지 못하면 PNG 렌더링 전에 중단한다', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    vi.mocked(reportFontEmbedCSS).mockRejectedValue(new Error('font fetch failed'));
    await expect(captureReportImage(node)).rejects.toThrow('font fetch failed');
    expect(toBlob).not.toHaveBeenCalled();
  });

  function timeoutFixture() {
    vi.useFakeTimers();
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1165 },
    });
    const layer = document.createElement('li');
    layer.className = reportStyles.cell;
    layer.style.perspective = '1400px';
    node.append(layer);
    return { node, layer, originalStyle: layer.getAttribute('style') };
  }

  it('iOS 폰트 대기가 시간 초과되면 늦은 응답도 화면 변경·렌더링을 시작하지 않는다', async () => {
    const { node, layer, originalStyle } = timeoutFixture();
    let finishFont!: (css: string) => void;
    vi.mocked(reportFontEmbedCSS).mockReturnValue(
      new Promise((resolve) => {
        finishFont = resolve;
      }),
    );
    const capture = captureReportImage(node);
    const rejection = expect(capture).rejects.toThrow('capture timed out');
    await vi.advanceTimersByTimeAsync(30_000);
    await rejection;
    expect(layer.getAttribute('style')).toBe(originalStyle);
    layer.style.transform = 'rotateY(180deg)';
    const updatedStyle = layer.getAttribute('style');
    finishFont('@font-face { font-family: Anton; }');
    await vi.advanceTimersByTimeAsync(100);
    expect(layer.getAttribute('style')).toBe(updatedStyle);
    expect(renderIOSReportImage).not.toHaveBeenCalled();
  });

  it('iOS 전체 시간 초과는 즉시 스타일을 복원하고 늦은 렌더러가 다음 캡처를 덮어쓰지 않는다', async () => {
    const { node, layer, originalStyle } = timeoutFixture();
    let finishFont!: (css: string) => void;
    let finishOldRender!: (png: Blob) => void;
    vi.mocked(reportFontEmbedCSS).mockReturnValueOnce(
      new Promise((resolve) => {
        finishFont = resolve;
      }),
    );
    vi.mocked(renderIOSReportImage).mockReturnValueOnce(
      new Promise((resolve) => {
        finishOldRender = resolve;
      }),
    );
    const capture = captureReportImage(node);
    const rejection = expect(capture).rejects.toThrow('capture timed out');
    await vi.advanceTimersByTimeAsync(25_000);
    finishFont('@font-face { font-family: Anton; }');
    await vi.advanceTimersByTimeAsync(100);
    expect(layer.style.perspective).toBe('none');
    expect(renderIOSReportImage).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(4_900);
    await rejection;
    expect(layer.getAttribute('style')).toBe(originalStyle);
    expect(vi.mocked(renderIOSReportImage).mock.calls[0][4]?.aborted).toBe(true);
    expect(renderIOSReportImage).toHaveBeenCalledOnce();

    layer.style.perspective = '900px';
    const newStyle = layer.getAttribute('style');
    const png = new Blob(['new capture'], { type: 'image/png' });
    vi.mocked(renderIOSReportImage).mockResolvedValueOnce(png);
    const nextCapture = captureReportImage(node);
    await vi.advanceTimersByTimeAsync(100);
    await expect(nextCapture).resolves.toBe(png);
    finishOldRender(new Blob(['old capture'], { type: 'image/png' }));
    await vi.advanceTimersByTimeAsync(100);
    expect(layer.getAttribute('style')).toBe(newStyle);
    expect(renderIOSReportImage).toHaveBeenCalledTimes(2);
  });

  it('iOS 렌더링 재시도 전에 이전 시도를 취소하고 늦은 결과는 사용하지 않는다', async () => {
    const { node, layer, originalStyle } = timeoutFixture();
    let finishOldRender!: (png: Blob) => void;
    const png = new Blob(['retry'], { type: 'image/png' });
    vi.mocked(renderIOSReportImage)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finishOldRender = resolve;
        }),
      )
      .mockResolvedValueOnce(png);
    const capture = captureReportImage(node);
    await vi.advanceTimersByTimeAsync(10_200);
    await expect(capture).resolves.toBe(png);
    const attempts = vi.mocked(renderIOSReportImage).mock.calls;
    expect(attempts).toHaveLength(2);
    expect(attempts[0][4]?.aborted).toBe(true);
    expect(attempts[1][4]?.aborted).toBe(false);
    expect(attempts[0][4]).not.toBe(attempts[1][4]);
    layer.style.perspective = '800px';
    const newStyle = layer.getAttribute('style');
    finishOldRender(new Blob(['late'], { type: 'image/png' }));
    await vi.advanceTimersByTimeAsync(100);
    expect(layer.getAttribute('style')).toBe(newStyle);
    expect(originalStyle).not.toBe(newStyle);
  });

  it('iOS 사진 요청도 전체 시간 초과에 취소하고 빈 사진으로 진행하지 않는다', async () => {
    const { node, layer, originalStyle } = timeoutFixture();
    const image = document.createElement('img');
    image.src = '/photo.webp';
    Object.defineProperty(image, 'complete', { value: true });
    node.append(image);
    const fetchPhoto = vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => undefined));
    const capture = captureReportImage(node);
    const rejection = expect(capture).rejects.toThrow('capture timed out');
    await vi.advanceTimersByTimeAsync(30_000);
    await rejection;
    expect(fetchPhoto.mock.calls[0][1]?.signal?.aborted).toBe(true);
    expect(layer.getAttribute('style')).toBe(originalStyle);
    expect(reportFontEmbedCSS).not.toHaveBeenCalled();
    expect(renderIOSReportImage).not.toHaveBeenCalled();
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

  it.each([
    [
      'https://image.tmdb.org/t/p/w1280/tiN6K0LnkK6jG20M39H3AfnXIgQ.jpg',
      '/tmdb-images/w1280/tiN6K0LnkK6jG20M39H3AfnXIgQ.jpg',
    ],
    [
      'https://image.tmdb.org/t/p/original/xuAIuYSmsUzKlUMBFGVZaWsY3DZ.jpg',
      '/tmdb-images/original/xuAIuYSmsUzKlUMBFGVZaWsY3DZ.jpg',
    ],
    ['/photo.webp', 'http://localhost:3000/photo.webp'],
    ['data:image/png;base64,aW1hZ2U=', 'data:image/png;base64,aW1hZ2U='],
    [
      'https://image.tmdb.org.evil.example/t/p/original/photo.jpg',
      'https://image.tmdb.org.evil.example/t/p/original/photo.jpg',
    ],
  ])('사진 %s를 안전한 캡처 경로로 읽고 원본을 복원한다', async (src, expected) => {
    const node = document.createElement('div');
    const image = document.createElement('img');
    image.src = src;
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
    const fetchImage = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['image'], { type: 'image/png' }),
    } as Response);
    const png = new Blob(['png'], { type: 'image/png' });
    vi.mocked(toBlob).mockImplementation(async () => {
      expect(image.src).toMatch(/^data:image\/png;base64,/);
      return png;
    });

    await expect(captureReportImage(node)).resolves.toBe(png);
    expect(fetchImage).toHaveBeenCalledWith(expected, { mode: 'cors', credentials: 'omit' });
    expect(image.getAttribute('src')).toBe(src);
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
  it('뒷면은 폭만 1080으로 맞추고 높이는 화면 비율을 따른다', async () => {
    const node = document.createElement('div');
    Object.defineProperties(node, {
      scrollWidth: { value: 385 },
      scrollHeight: { value: 1540 },
    });
    vi.spyOn(node, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 385, 1165));
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

  it('기존 iPhone 뒷면 캡처는 폰트 생략과 한 번 렌더링을 그대로 유지한다', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    const node = document.createElement('div');
    Object.defineProperties(node, { scrollWidth: { value: 385 }, scrollHeight: { value: 1540 } });
    vi.mocked(toBlob).mockResolvedValue(new Blob(['back'], { type: 'image/png' }));
    await captureReportBackImage(node);
    expect(toBlob).toHaveBeenCalledOnce();
    expect(toBlob).toHaveBeenCalledWith(
      node,
      expect.objectContaining({ skipFonts: true, canvasHeight: 4320 }),
    );
    expect(reportFontEmbedCSS).not.toHaveBeenCalled();
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
