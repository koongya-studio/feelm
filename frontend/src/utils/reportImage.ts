/*
 * 저장용 취향 4컷.
 *
 * 별도 그림 코드를 두지 않고 화면에 보이는 `ReportFourCut` DOM을 그대로 PNG로 바꾼다.
 * CSS·서체·로고·사진·캐릭터 에셋이 바뀌면 저장 결과도 자동으로 함께 바뀐다.
 *
 * - 앞면(`captureReportImage`)은 API-061 규격인 1080×3268로 찍는다
 * - 뒷면(`captureReportBackImage`)은 칸 넷을 모두 뒤집은 같은 DOM이다. 뒷면 높이가 내용만큼
 *   늘어나므로 폭만 1080으로 맞추고 높이는 화면 비율을 따른다. 공유 메뉴로만 넘기고 서버에
 *   올리지 않는다(업무 -849)
 */
import { toBlob } from 'html-to-image';

export const REPORT_IMAGE_WIDTH = 1080;
export const REPORT_IMAGE_HEIGHT = 3268;
const CAPTURE_TIMEOUT_MS = 15_000;
const IOS_CAPTURE_TIMEOUT_MS = 30_000;
const DOWNLOAD_TIMEOUT_MS = 30_000;

/** 웹폰트와 사진 배치가 끝난 뒤 화면의 4컷 DOM을 고해상도 PNG로 캡처한다. */
export function captureReportImage(node: HTMLElement): Promise<Blob> {
  return withTimeout(
    captureReadyReportImage(node, () => REPORT_IMAGE_HEIGHT),
    'ReportFourCut PNG capture timed out',
    isIOS() ? IOS_CAPTURE_TIMEOUT_MS : CAPTURE_TIMEOUT_MS,
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

function isIOS(): boolean {
  return /iP(hone|ad|od)/.test(navigator.userAgent);
}

async function captureReadyReportImage(
  node: HTMLElement,
  canvasHeightOf: (width: number, height: number) => number,
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

  const width = node.scrollWidth;
  const height = node.scrollHeight;
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
  const response = await fetch(url, { mode: 'cors' });
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
        reject(error instanceof Error ? error : new Error(message));
      },
    );
  });
}
