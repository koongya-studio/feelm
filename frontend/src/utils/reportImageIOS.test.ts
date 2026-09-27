import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toSvg } from 'html-to-image';
import { applyPhotoFilter, fittedRect, renderIOSReportImage } from './reportImageIOS';
import styles from '../components/ReportFourCut/ReportFourCut.module.css';

vi.mock('html-to-image', () => ({ toSvg: vi.fn() }));

describe('iOS 사진 배치와 픽셀 처리', () => {
  it('cover 사진은 가운데를 기준으로 잘라 배치한다', () => {
    expect(
      fittedRect({ x: 10, y: 20, width: 100, height: 100 }, 200, 100, 'cover', '50% 50%'),
    ).toEqual({ x: -40, y: 20, width: 200, height: 100 });
  });

  it('contain 누끼는 가로 위치와 하단 정렬을 유지한다', () => {
    expect(
      fittedRect({ x: 10, y: 20, width: 100, height: 100 }, 200, 100, 'contain', '25% 100%'),
    ).toEqual({ x: 10, y: 70, width: 100, height: 50 });
  });

  it('확대된 이미지 상자에서도 지정한 크롭 위치를 유지한다', () => {
    expect(
      fittedRect({ x: -20, y: -30, width: 160, height: 160 }, 100, 200, 'cover', '50% 75%'),
    ).toEqual({ x: -20, y: -150, width: 160, height: 320 });
  });

  it('흑백·대비를 적용해도 누끼의 알파 채널을 보존한다', () => {
    const pixels = new Uint8ClampedArray([200, 100, 50, 17, 0, 0, 0, 0, 255, 255, 255, 255]);
    applyPhotoFilter(pixels, true, 1.08);
    expect(Array.from(pixels)).toEqual([117, 117, 117, 17, 0, 0, 0, 0, 255, 255, 255, 255]);
  });
});

describe('iOS 앞면 Canvas 합성', () => {
  const photo = 'data:image/png;base64,cGhvdG8=';
  let contexts: CanvasRenderingContext2D[];
  let addColorStop: ReturnType<typeof vi.fn>;
  let report: HTMLDivElement;
  let image: HTMLImageElement;

  beforeEach(() => {
    contexts = [];
    addColorStop = vi.fn();
    vi.mocked(toSvg).mockReset();
    report = document.createElement('div');
    report.innerHTML =
      '<section data-cut="actor"><div style="z-index: 1; mask-image: linear-gradient(90deg, transparent 0%, #000 34%)"><img style="object-fit: cover; object-position: 50% 50%; filter: grayscale(1) contrast(1.06)" /></div><p>Stan LEE</p><button data-report-flip-control>뒤집기</button></section>';
    image = report.querySelector('img')!;
    Object.defineProperties(image, { naturalWidth: { value: 100 }, naturalHeight: { value: 200 } });
    for (const element of [report, image.closest('section')!, image.parentElement!, image]) {
      vi.spyOn(element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 100));
    }
    vi.mocked(toSvg).mockImplementation(async (node, options) => {
      expect(options?.filter?.(image)).toBe(false);
      expect(options?.filter?.(report.querySelector('button')!)).toBe(false);
      expect(options?.fontEmbedCSS).toContain('@font-face');
      const clone = node.cloneNode(true) as HTMLElement;
      clone.querySelectorAll('img, button').forEach((element) => element.remove());
      return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(
        `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><foreignObject width="100" height="100"><div xmlns="http://www.w3.org/1999/xhtml" style="background:#0c0c0d">${clone.innerHTML}<style>@font-face { font-family: Anton; }</style></div></foreignObject></svg>`,
      )}`;
    });
    Object.defineProperty(HTMLImageElement.prototype, 'decode', {
      configurable: true,
      writable: true,
      value: vi.fn().mockResolvedValue(undefined),
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => {
      const context = {
        fillRect: vi.fn(),
        drawImage: vi.fn(),
        save: vi.fn(),
        restore: vi.fn(),
        getImageData: vi.fn(() => ({ data: new Uint8ClampedArray([200, 100, 50, 17]) })),
        putImageData: vi.fn(),
        createLinearGradient: vi.fn(() => ({ addColorStop })),
        beginPath: vi.fn(),
        rect: vi.fn(),
        clip: vi.fn(),
      } as unknown as CanvasRenderingContext2D;
      contexts.push(context);
      return context;
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => {
      callback(new Blob(['complete'], { type: 'image/png' }));
    });
  });

  afterEach(() => vi.restoreAllMocks());

  const options = {
    width: 100,
    height: 100,
    canvasWidth: 1080,
    canvasHeight: 3268,
    fontEmbedCSS: '@font-face { font-family: Anton; }',
    filter: (child: HTMLElement) => !child.hasAttribute('data-report-flip-control'),
  };

  it('사진을 SVG에 넣지 않고 직접 그리고 DOM 글자층은 투명하게 올린다', async () => {
    await expect(renderIOSReportImage(report, [image], [photo], options)).resolves.toEqual(
      expect.objectContaining({ type: 'image/png' }),
    );
    const patch = contexts[1];
    expect(patch.drawImage).toHaveBeenCalledWith(
      expect.objectContaining({ src: photo }),
      0,
      -1634,
      1080,
      6536,
    );
    expect(patch.globalCompositeOperation).toBe('destination-in');
    expect(addColorStop.mock.calls).toEqual([
      [0, 'rgba(0, 0, 0, 0)'],
      [0.34, 'rgba(0, 0, 0, 1)'],
    ]);
    expect(patch.putImageData).toHaveBeenCalled();
    const layers = vi
      .mocked(contexts[0].drawImage)
      .mock.calls.filter(([source]) => source instanceof HTMLImageElement)
      .map(([source]) => decodeURIComponent((source as HTMLImageElement).src.split(',')[1]));
    expect(layers).toHaveLength(3);
    layers.forEach((xml) => {
      expect(xml).not.toContain('<img');
      expect(xml).not.toContain(photo);
    });
    const foreground = new DOMParser().parseFromString(layers[2], 'image/svg+xml');
    expect(
      (foreground.querySelector('foreignObject')!.firstElementChild as HTMLElement).style
        .background,
    ).toBe('none');
    expect(foreground.querySelector('p')?.textContent).toBe('Stan LEE');
    expect(contexts[0].drawImage).toHaveBeenCalledTimes(4);
  });

  it('지원하지 않는 마스크는 사진 없는 성공 PNG로 넘기지 않는다', async () => {
    image.parentElement!.style.maskImage = 'linear-gradient(180deg, transparent 0%, #000 100%)';
    await expect(renderIOSReportImage(report, [image], [photo], options)).rejects.toThrow(
      'image mask is unsupported',
    );
    expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled();
  });

  it('장르 누끼는 큰 이름 앞에 그리고 자식의 visible 스타일도 숨긴다', async () => {
    image.closest('section')!.dataset.cut = 'genre';
    image.parentElement!.className = styles.figure;
    image.parentElement!.style.zIndex = '3';
    image.parentElement!.style.maskImage = 'none';
    const name = document.createElement('div');
    name.className = styles.nameArea;
    const text = report.querySelector('p')!;
    text.style.visibility = 'visible';
    name.append(text);
    image.closest('section')!.append(name);

    await renderIOSReportImage(report, [image], [photo], options);

    const calls = vi.mocked(contexts[0].drawImage).mock.calls;
    expect(calls).toHaveLength(5);
    expect(calls[2][0]).toBeInstanceOf(HTMLImageElement);
    expect(calls[3][0]).toBeInstanceOf(HTMLCanvasElement);
    const source = decodeURIComponent((calls[4][0] as HTMLImageElement).src.split(',')[1]);
    const foreground = new DOMParser().parseFromString(source, 'image/svg+xml');
    const title = foreground.querySelector('p') as HTMLElement;
    expect(title.style.visibility).toBe('hidden');
    expect(title.style.getPropertyPriority('visibility')).toBe('important');
  });

  it('잘못된 SVG는 이미지 파일로 저장하지 않는다', async () => {
    vi.mocked(toSvg).mockResolvedValue('data:image/svg+xml;charset=utf-8,%3Cbroken');
    await expect(renderIOSReportImage(report, [image], [photo], options)).rejects.toThrow(
      'SVG parsing failed',
    );
  });

  it('취소 후 늦게 끝난 SVG 복제로 사진 합성을 시작하지 않는다', async () => {
    let finishSvg!: (svg: string) => void;
    vi.mocked(toSvg).mockReturnValue(
      new Promise((resolve) => {
        finishSvg = resolve;
      }),
    );
    const controller = new AbortController();
    const capture = renderIOSReportImage(report, [image], [photo], options, controller.signal);
    const rejection = expect(capture).rejects.toThrow('capture timed out');
    await vi.waitFor(() => expect(toSvg).toHaveBeenCalledOnce());
    controller.abort(new Error('capture timed out'));
    await rejection;
    finishSvg('data:image/svg+xml;charset=utf-8,%3Cbroken');
    await Promise.resolve();
    expect(HTMLCanvasElement.prototype.getContext).not.toHaveBeenCalled();
    expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled();
  });

  it('이미지 디코딩 대기를 취소하면 늦은 완료 후에도 Canvas에 그리지 않는다', async () => {
    let finishDecode!: () => void;
    vi.mocked(HTMLImageElement.prototype.decode).mockReturnValue(
      new Promise((resolve) => {
        finishDecode = resolve;
      }),
    );
    const controller = new AbortController();
    const capture = renderIOSReportImage(report, [image], [photo], options, controller.signal);
    const rejection = expect(capture).rejects.toThrow('render timed out');
    await vi.waitFor(() => expect(HTMLImageElement.prototype.decode).toHaveBeenCalledOnce());
    controller.abort(new Error('render timed out'));
    await rejection;
    finishDecode();
    await Promise.resolve();
    expect(contexts[0].drawImage).not.toHaveBeenCalled();
    expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled();
  });

  it('PNG 인코딩 중 취소되면 늦게 만들어진 파일도 성공으로 반환하지 않는다', async () => {
    let finishPng!: BlobCallback;
    vi.mocked(HTMLCanvasElement.prototype.toBlob).mockImplementation((callback) => {
      finishPng = callback;
    });
    const controller = new AbortController();
    const capture = renderIOSReportImage(report, [image], [photo], options, controller.signal);
    const rejection = expect(capture).rejects.toThrow('render timed out');
    await vi.waitFor(() => expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledOnce());
    controller.abort(new Error('render timed out'));
    await rejection;
    finishPng(new Blob(['late PNG'], { type: 'image/png' }));
    await Promise.resolve();
    expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledOnce();
  });
});
