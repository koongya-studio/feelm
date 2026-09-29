/*
 * 저장용 취향 4컷.
 *
 * 화면의 `ReportFourCut` DOM에서 PNG를 만든다. iOS 앞면은 사진을 Canvas에 직접 그리고
 * DOM의 배경·글자·로고를 합성해 Safari의 SVG 사진 누락을 피한다.
 * 사진 위치와 효과는 같은 DOM의 레이아웃과 계산된 CSS에서 읽는다.
 *
 * - 앞면(`captureReportImage`)은 API-061 규격인 1080×3268로 찍는다
 * - 뒷면(`captureReportBackImage`)은 칸 넷을 모두 뒤집은 같은 DOM이다. 뒷면 높이가 내용만큼
 *   늘어나므로 폭만 1080으로 맞추고 높이는 화면 비율을 따른다. 공유 메뉴로만 넘기고 서버에
 *   올리지 않는다(업무 -849)
 */
import { toBlob } from 'html-to-image';
import { readDataUrl, reportFontEmbedCSS } from './reportImageFonts';
import reportStyles from '../components/ReportFourCut/ReportFourCut.module.css';
import { renderIOSReportImage } from './reportImageIOS';
import {
  assertCaptureActive,
  waitForCapture,
  withCaptureDeadline,
} from './reportImageCancellation';

export const REPORT_IMAGE_WIDTH = 1080;
export const REPORT_IMAGE_HEIGHT = 3268;
const CAPTURE_TIMEOUT_MS = 15_000;
const IOS_CAPTURE_TIMEOUT_MS = 30_000;
const DOWNLOAD_TIMEOUT_MS = 30_000;

/** 웹폰트와 사진 배치가 끝난 뒤 화면의 4컷 DOM을 고해상도 PNG로 캡처한다. */
export function captureReportImage(node: HTMLElement): Promise<Blob> {
  if (isIOSReportDevice()) {
    return withCaptureDeadline(
      (signal) => captureReadyReportFrontImage(node, signal),
      IOS_CAPTURE_TIMEOUT_MS,
      'ReportFourCut PNG capture timed out',
    ).catch((error: unknown) => {
      throw asError(error);
    });
  }
  return withTimeout(
    captureReadyReportImage(node, () => REPORT_IMAGE_HEIGHT, true),
    'ReportFourCut PNG capture timed out',
    CAPTURE_TIMEOUT_MS,
  );
}

/** 칸을 뒤집어 둔 4컷 DOM을 폭 1080, 화면과 같은 비율의 PNG로 캡처한다. */
export function captureReportBackImage(node: HTMLElement): Promise<Blob> {
  return withTimeout(
    captureReadyReportImage(node, (width, height) =>
      Math.round((REPORT_IMAGE_WIDTH * height) / width),
    ),
    'ReportFourCut back PNG capture timed out',
    isIOS() ? IOS_CAPTURE_TIMEOUT_MS : CAPTURE_TIMEOUT_MS,
  );
}

/** 앞면 캡처·저장에 iPhone/iPad 보정이 필요한 환경인지 판별한다. */
export function isIOSReportDevice(): boolean {
  return (
    /iP(hone|ad|od)/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  );
}

async function captureReadyReportFrontImage(node: HTMLElement, signal: AbortSignal): Promise<Blob> {
  if (node.querySelector('[data-flipped]')) {
    throw new Error('ReportFourCut capture requires the front');
  }
  assertImagesAvailable(node);
  if (document.fonts) {
    await waitForCapture(() => document.fonts.ready, signal);
  }
  await waitForCapture(() => decodeVisibleImages(node), signal);
  await waitForCapture(nextPaint, signal);
  assertImagesAvailable(node);

  if (
    Array.from(node.querySelectorAll('img')).some(
      (image) => !image.complete || image.naturalWidth <= 0,
    )
  ) {
    throw new Error('ReportFourCut image loading failed');
  }

  // 숨겨진 긴 뒷면도 scrollHeight에 포함된다. 앞면의 실제 경계만 사용해
  // 사진·글자 비율을 유지하고 저장본 아래에 불필요한 검정 여백이 생기지 않게 한다.
  const { width, height } = node.getBoundingClientRect();
  if (width <= 0 || height <= 0) throw new Error('ReportFourCut is not laid out');

  // html-to-image는 복제한 <img>를 다시 fetch한다. 그 요청이 실패하면 빈 사진으로
  // 계속 진행하므로, 먼저 모든 사진을 데이터 URL로 고정하고 실패를 사용자에게 알린다.
  const images = Array.from(node.querySelectorAll('img'));
  const imageData = await waitForCapture(
    () =>
      Promise.all(images.map((image) => frontImageDataUrl(image.currentSrc || image.src, signal))),
    signal,
  );
  // 폰트 캐시는 공유하되 이번 대기만 취소한다. 늦은 응답은 DOM을 다시 변경하지 않는다.
  const fontEmbedCSS = await waitForCapture(reportFontEmbedCSS, signal);
  const layers = Array.from(
    node.querySelectorAll<HTMLElement>(
      `.${reportStyles.cell}, .${reportStyles.flipper}, .${reportStyles.cut}, .${reportStyles.back}`,
    ),
  );
  const originalLayers = layers.map((layer) => layer.getAttribute('style'));
  const originals = images.map((image) => ({
    src: image.getAttribute('src'),
    srcset: image.getAttribute('srcset'),
    style: image.getAttribute('style'),
  }));
  try {
    // 앞면 캡처 동안만 3D 합성층을 평면화한다. 공용 CSS와 뒷면 캡처는 바꾸지 않는다.
    layers.forEach((layer) => {
      layer.style.transition = 'none';
      layer.style.perspective = 'none';
      layer.style.transformStyle = 'flat';
      layer.style.backfaceVisibility = 'visible';
      layer.style.transform = 'none';
    });
    await waitForCapture(nextPaint, signal);
    const options = {
      width,
      height,
      canvasWidth: REPORT_IMAGE_WIDTH,
      canvasHeight: REPORT_IMAGE_HEIGHT,
      backgroundColor: '#0c0c0d',
      pixelRatio: 1,
      filter: (child: HTMLElement) =>
        !(child instanceof HTMLElement && child.hasAttribute('data-report-flip-control')),
      fontEmbedCSS,
    };
    async function render(): Promise<Blob> {
      return withCaptureDeadline(
        (attempt) =>
          waitForCapture(
            () => renderIOSReportImage(node, images, imageData, options, attempt),
            attempt,
          ),
        10_000,
        'ReportFourCut PNG render timed out',
        signal,
      );
    }
    let png: Blob | null;
    try {
      png = await render();
    } catch {
      assertCaptureActive(signal);
      // Safari의 SVG foreignObject 디코딩은 간헐적으로 Load failed를 낸다.
      // 같은 원본 DOM을 한 프레임 뒤 다시 그리면 빈 사진으로 진행하지 않고 재시도할 수 있다.
      await waitForCapture(nextPaint, signal);
      png = await render();
    }

    if (!png || png.type !== 'image/png') throw new Error('ReportFourCut PNG capture failed');
    return png;
  } finally {
    layers.forEach((layer, index) => {
      const style = originalLayers[index];
      if (style === null) layer.removeAttribute('style');
      else layer.setAttribute('style', style);
    });
    images.forEach((image, index) => {
      const { src, srcset, style } = originals[index];
      if (src === null) image.removeAttribute('src');
      else image.setAttribute('src', src);
      if (srcset === null) image.removeAttribute('srcset');
      else image.setAttribute('srcset', srcset);
      if (style === null) image.removeAttribute('style');
      else image.setAttribute('style', style);
    });
    // 시간 초과 후에는 paint 대기 없이 즉시 복원하고 호출자에게 실패를 넘긴다.
    if (!signal.aborted) await waitForCapture(nextPaint, signal);
  }
}

async function frontImageDataUrl(url: string, signal: AbortSignal): Promise<string> {
  // 화면에 보이는 사진도 fetch는 CORS에 막힐 수 있어 TMDB는 같은 출처로 읽는다.
  const source = new URL(url, window.location.href);
  const captureUrl =
    source.origin === 'https://image.tmdb.org' &&
    /^\/t\/p\/(?:original|w[1-9][0-9]*)\/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp)$/.test(
      source.pathname,
    )
      ? `/tmdb-images${source.pathname.slice('/t/p'.length)}`
      : url;
  const response = await waitForCapture(
    () => fetch(captureUrl, { mode: 'cors', credentials: 'omit', signal }),
    signal,
  );
  if (!response.ok) throw new Error('ReportFourCut image fetch failed');
  const blob = await waitForCapture(() => response.blob(), signal);
  if (!blob.type.startsWith('image/')) throw new Error('ReportFourCut image response is invalid');
  const raster = new Image();
  raster.src = await waitForCapture(() => readDataUrl(blob), signal);
  try {
    await waitForCapture(() => raster.decode(), signal);
  } catch (error) {
    assertCaptureActive(signal);
    if (!raster.complete || raster.naturalWidth <= 0) throw error;
  }
  if (raster.naturalWidth <= 0 || raster.naturalHeight <= 0) {
    throw new Error('ReportFourCut image decoding failed');
  }
  // WebP/JPEG와 원본 크기에 따른 Safari의 중첩 SVG 이미지 디코딩 차이를 없앤다.
  // 투명 누끼도 보존하며 출력 폭의 두 배까지만 유지해 캡처 메모리를 제한한다.
  const scale = Math.min(
    1,
    (REPORT_IMAGE_WIDTH * 2) / Math.max(raster.naturalWidth, raster.naturalHeight),
  );
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(raster.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(raster.naturalHeight * scale));
  const context = canvas.getContext('2d');
  if (!context) throw new Error('ReportFourCut image conversion failed');
  context.drawImage(raster, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/png');
}

function isIOS(): boolean {
  return /iP(hone|ad|od)/.test(navigator.userAgent);
}

async function captureReadyReportImage(
  node: HTMLElement,
  canvasHeightOf: (width: number, height: number) => number,
  frontOnly = false,
): Promise<Blob> {
  assertImagesAvailable(node);
  if (document.fonts) {
    await document.fonts.ready;
  }
  await decodeVisibleImages(node);
  await nextPaint();
  assertImagesAvailable(node);

  if (
    Array.from(node.querySelectorAll('img')).some(
      (image) => !image.complete || image.naturalWidth <= 0,
    )
  ) {
    throw new Error('ReportFourCut image loading failed');
  }

  // 앞면은 숨겨진 뒷면의 넘치는 내용 높이를 제외한다. 뒷면 캡처는 기존처럼
  // scrollHeight를 사용해 긴 설명까지 포함한다.
  const { width, height } = frontOnly
    ? node.getBoundingClientRect()
    : { width: node.scrollWidth, height: node.scrollHeight };
  if (width <= 0 || height <= 0) throw new Error('ReportFourCut is not laid out');

  // html-to-image는 복제한 <img>를 다시 fetch한다. 그 요청이 실패하면 빈 사진으로
  // 계속 진행하므로, 먼저 모든 사진을 데이터 URL로 고정하고 실패를 사용자에게 알린다.
  const images = Array.from(node.querySelectorAll('img'));
  const imageData = await Promise.all(
    images.map((image) => imageDataUrl(image.currentSrc || image.src)),
  );
  const originals = images.map((image) => ({
    src: image.getAttribute('src'),
    srcset: image.getAttribute('srcset'),
  }));
  try {
    images.forEach((image, index) => {
      image.removeAttribute('srcset');
      image.src = imageData[index];
    });
    await decodeVisibleImages(node);
    const options = {
      width,
      height,
      canvasWidth: REPORT_IMAGE_WIDTH,
      canvasHeight: canvasHeightOf(width, height),
      backgroundColor: '#0c0c0d',
      pixelRatio: 1,
      filter: (child: HTMLElement) =>
        !(child instanceof HTMLElement && child.hasAttribute('data-report-flip-control')),
      // iOS Safari에서 동적 서브셋 웹폰트 수십 개를 SVG 안에 다시 넣으면
      // 캡처가 끝나지 않는다. 기기 글꼴로 그려 PNG 생성과 사진 보존을 우선한다.
      skipFonts: isIOS(),
    };
    let png: Blob | null;
    try {
      png = await withTimeout(toBlob(node, options), 'ReportFourCut PNG render timed out', 10_000);
    } catch (error) {
      if (!isIOS()) throw error;
      // Safari의 SVG foreignObject 디코딩은 간헐적으로 Load failed를 낸다.
      // 같은 원본 DOM을 한 프레임 뒤 다시 그리면 빈 사진으로 진행하지 않고 재시도할 수 있다.
      await nextPaint();
      png = await withTimeout(toBlob(node, options), 'ReportFourCut PNG render timed out', 10_000);
    }

    if (!png || png.type !== 'image/png') throw new Error('ReportFourCut PNG capture failed');
    return png;
  } finally {
    images.forEach((image, index) => {
      const { src, srcset } = originals[index];
      if (src === null) image.removeAttribute('src');
      else image.setAttribute('src', src);
      if (srcset === null) image.removeAttribute('srcset');
      else image.setAttribute('srcset', srcset);
    });
  }
}

async function decodeVisibleImages(node: HTMLElement): Promise<void> {
  await Promise.all(
    Array.from(node.querySelectorAll('img')).map(async (image) => {
      try {
        await image.decode();
      } catch (error) {
        // iOS는 표시된 사진에도 간헐적으로 decode()를 거절한다.
        if (!image.complete || image.naturalWidth <= 0) throw error;
      }
    }),
  );
}

async function imageDataUrl(url: string): Promise<string> {
  // 화면에 보이는 사진도 fetch는 CORS에 막힐 수 있어 TMDB는 같은 출처로 읽는다.
  const source = new URL(url, window.location.href);
  const captureUrl =
    source.origin === 'https://image.tmdb.org' &&
    /^\/t\/p\/(?:original|w[1-9][0-9]*)\/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp)$/.test(
      source.pathname,
    )
      ? `/tmdb-images${source.pathname.slice('/t/p'.length)}`
      : url;
  const response = await fetch(captureUrl, { mode: 'cors', credentials: 'omit' });
  if (!response.ok) throw new Error('ReportFourCut image fetch failed');
  const blob = await response.blob();
  if (!blob.type.startsWith('image/')) throw new Error('ReportFourCut image response is invalid');
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('ReportFourCut image conversion failed'));
    reader.readAsDataURL(blob);
  });
}

/** API-061의 서명 URL을 브라우저 다운로드로 연결한다. */
export async function downloadReportImage(downloadUrl: string, reportId: number): Promise<void> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS);
  let png: Blob;
  try {
    const response = await fetch(downloadUrl, {
      credentials: 'same-origin',
      signal: controller.signal,
    });
    if (!response.ok) throw new Error('ReportFourCut PNG download failed');

    png = await response.blob();
  } finally {
    window.clearTimeout(timeout);
  }
  if (png.type !== 'image/png') throw new Error('ReportFourCut PNG response is invalid');
  downloadBlob(png, `feelm-report-${reportId}.png`);
}

/** 손에 든 파일을 브라우저 다운로드로 내려받는다. */
export function downloadBlob(blob: Blob, fileName: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName;
  link.hidden = true;
  try {
    document.body.append(link);
    link.click();
  } finally {
    link.remove();
    // 클릭 직후 해제하면 일부 브라우저가 읽기 전에 URL이 사라질 수 있다.
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000);
  }
}

function assertImagesAvailable(node: HTMLElement): void {
  if (node.querySelector('[data-image-error="true"]')) {
    throw new Error('ReportFourCut image loading failed');
  }
}

function nextPaint(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
  });
}

/**
 * 실패를 `Error`로 맞춘다. **`instanceof`만 보지 않는다** — 다른 실행 환경(iframe · jsdom 등)에서
 * 만든 오류는 `Error`인데도 `instanceof Error`가 거짓이다. 그런 실패를 시간 초과 문구로 바꿔 버려
 * `FileReader`가 Blob을 거절한 것이 `capture timed out`으로 보였다(업무 S15P21E106-916).
 * 시간 초과 문구는 실제로 시간이 다 됐을 때만 쓴다. 오류가 아닌 값은 원인으로 붙여 감싼다.
 */
function asError(error: unknown): Error {
  if (error instanceof Error) return error;
  if (Object.prototype.toString.call(error) === '[object Error]') return error as Error;

  return new Error('ReportFourCut capture failed', { cause: error });
}

function withTimeout<T>(
  promise: PromiseLike<T>,
  message: string,
  duration = CAPTURE_TIMEOUT_MS,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => reject(new Error(message)), duration);
    Promise.resolve(promise).then(
      (value) => {
        window.clearTimeout(timeout);
        resolve(value);
      },
      (error: unknown) => {
        window.clearTimeout(timeout);
        reject(asError(error));
      },
    );
  });
}
